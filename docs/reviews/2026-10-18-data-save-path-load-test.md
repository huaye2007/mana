# Data save path load test (2026-10-18)

**[English](2026-10-18-data-save-path-load-test.md)** | [简体中文](2026-10-18-data-save-path-load-test.zh-CN.md)

> Non-normative record. The current contracts live in the [Data Specification](../ogbs/OGBS-Data-1.0.md) and
> the [Data Java Development Specification](../ogbs/OGBS-Data-Java-25-Specification-1.0.md). This page holds
> the conditions and the numbers behind the throughput guidance in
> [Java §6.2](../ogbs/OGBS-Data-Java-25-Specification-1.0.md#62-batch-throughput-and-the-jdbc-url) and
> behind the save-path work of the same day.

## 1. What was measured and how far it carries

The first load test of game-data against a real database. Until this run the repository had no Data
benchmark at all: game-benchmark holds Metadata, RouteExecutor and RpcWire and does not depend on game-data,
and the [gap analysis](2026-10-17-framework-gap-analysis.md) §3.1 and §3.2 were reasoned from the sources
without measurement.

The harness behind §2 and §3 was written in a temporary directory. It has since been rebuilt in the
repository as [DataSavePathLoad](../../game-benchmark/src/main/java/cn/managame/benchmark/DataSavePathLoad.java),
so these measurements can be re-run rather than only cited:

```shell
mvn -pl game-benchmark -am package -DskipTests
java -Xmx2g -cp game-benchmark/target/benchmarks.jar cn.managame.benchmark.DataSavePathLoad \
  "--url=jdbc:mysql://127.0.0.1:3306/ogbs_bench?rewriteBatchedStatements=true" --user=root --password=secret
```

It wants a disposable database: it drops and recreates `bench_players`, and refuses to start when the database
holds any other table. Without `--url` (or `OGBS_BENCH_MYSQL_URL`) it prints its options and does nothing, and
no build runs it. Its options, and why it is a main class rather than a JMH benchmark, are in the
[module README](../../game-benchmark/README.md#data-保存路径压测).
[§5](#5-the-same-measurements-from-the-committed-harness) is its own run against the same MySQL on the same
machine, beside the figures below; where the two disagree, §5 says so and is the one anybody can repeat. Treat
the ratios as the transferable part and the absolute rates as one machine's loopback ceiling.

| | |
| --- | --- |
| Database | MySQL 9.7.1 on the same machine, over loopback |
| Machine | 18 cores, JDK 25, `-Xmx2g` |
| Schema | dedicated database `ogbs_bench`, table `bench_players`, three columns, no secondary index |
| Path | GameData → SingleRepository → write-behind → MysqlEntityMapper → JdbcMysqlAccess |
| Producers | 4 threads, distinct entity ids (no coalescing: every change is a row) |

## 2. Unthrottled production exhausts the heap in seconds

With `flushInterval=1s`, `batchSize=2000` and no rate limit on the producers, the process died of heap
exhaustion **7 seconds** in:

| Reading | Value |
| --- | --- |
| `DataStats.pendingChanges()` at the end | 8,079,251 |
| Heap | 806 MB → 2045 MB |
| `DataStats.flushes()` | **0 for the entire run** |

The database was healthy throughout; nothing failed and nothing was retried. The dirty set simply has no
limit, so producers that outrun storage turn into heap. `flushes()` staying at zero is the second half of
it: one pass claimed the whole dirty set, so the one pass that was running had an amount of work set by the
producers rather than by the pipeline, and no progress was observable from outside while it ran.

Rate-limited runs on the same setup were steady, which places the failure squarely at "producers faster than
drain" rather than anywhere in the database or the mapper:

| Producer rate | `pendingChanges()` | Passes in 20 s | Heap |
| --- | --- | --- | --- |
| 30,000/s | 400–1,500, not growing | 19 | ≈200 MB |
| 60,000/s | 400–1,500, not growing | 19 | ≈200 MB |
| unthrottled | 8,079,251 and growing | 0 | 2045 MB, then OOM |

[§5](#5-the-same-measurements-from-the-committed-harness) re-runs these rates. The two rate-limited rows are
the weakest part of the first run, though not for the reason an earlier draft of §5 gave. Their flushInterval
was 1 s, which the 19 passes in 20 seconds already say, and §5.2 shows a 1 s interval at 30,000/s leaving
about 15,000 waiting rather than 400–1,500. The low figures are a sampling artefact: the monitor slept one
second in a loop started together with the flush loop, so every sample landed just after a pass and read the
trough, and the distribution was never observed at all. What these rows support is that the dirty set did not
grow. Their magnitude says nothing reliable.

## 3. Throughput ceilings

| Configuration | Rows/s |
| --- | --- |
| INSERT, batchSize 500 | 47,000 |
| INSERT, batchSize 2000 | 77,000 |
| UPDATE, batchSize 500 | 16,700 |
| UPDATE, batchSize 2000 | 10,800 |

[§5.1](#51-throughput) re-measures all four, and two of the figures below with them: the INSERT multiplier for
the flag is not one number but grows with batchSize, and the pool is worth anywhere from a few percent to
about 90% depending on the configuration, with the 28% here inside that range rather than beside it.

`rewriteBatchedStatements=true` on the JDBC URL was worth **3.8×** for INSERT (10,000 → 38,500 rows/s) and
only **1.7×** for UPDATE. The asymmetry is in the driver: a batch of INSERTs rewrites into one multi-row
`VALUES`, while `UPDATE … WHERE id=?` rows cannot be folded into a single statement and keep a round trip
each. For the same reason a bigger batch helps INSERT and hurts UPDATE, which is why one batchSize is not
best for both. Taking a connection per batch instead of pooling cost about 28%: worth fixing in a
deployment, not the limit.

## 4. What this changed

| Finding | Where it landed |
| --- | --- |
| A pass claims the whole dirty set | `PendingBuffer.claim` takes a finite boundary, as `LogRepository.drain` already did ([Java §5.2](../ogbs/OGBS-Data-Java-25-Specification-1.0.md#52-one-pass)) |
| The dirty set has no limit | D-WRITE-04: bounded, writers wait `pendingChangeWait` and are then refused; nothing accepted is ever discarded to make room |
| The cache has no entry limit | D-CACHE-03: bounded per repository, clean entries only |
| `rewriteBatchedStatements` is absent from the documentation | [Java §6.2](../ogbs/OGBS-Data-Java-25-Specification-1.0.md#62-batch-throughput-and-the-jdbc-url) |

The same flood is now a test, `DatabaseIntegrationTest.unthrottledProducersAreBoundedAndEveryAcceptedChangeIsStored`,
run against the same local MySQL with the bounds set low so it finishes in seconds. Four unthrottled
producers for five seconds, `maxPendingChanges=5,000`, `maxCacheEntries=5,000`, `pendingChangeWait=200 ms`,
`flushInterval=100 ms`:

| Reading | Before | After |
| --- | --- | --- |
| Peak `pendingChanges()` | 8,079,251 and growing | 5,002 (bound 5,000; the overshoot is the four writers) |
| Peak cached entries | unbounded | 10,007 (bound, plus dirty entries that cannot be evicted) |
| Passes completed | 0 | 8 |
| Accepted / refused | — | 50,007 / 84 |
| Rows in MySQL after close | — | 50,007, equal to the accepted count |
| Outcome | OOM at 7 s | steady |

The accepted rate, about 10,000 rows/s, is the plain-URL figure from §3: the environment variable the test
reads carries no `rewriteBatchedStatements`, which is the gap §3 is about.

<a id="5-the-same-measurements-from-the-committed-harness"></a>

## 5. The same measurements from the committed harness

Runs of [DataSavePathLoad](../../game-benchmark/src/main/java/cn/managame/benchmark/DataSavePathLoad.java)
on the machine and MySQL of §1, with the conditions the first run left unrecorded now fixed by the harness
and printed by it. §5.1 holds three throughput runs, which is how its spread became visible; §5.2 is one run
of the rate sweep and has not been repeated:

| | First run (§2, §3) | These runs |
| --- | --- | --- |
| Harness | a temporary directory, not kept | DataSavePathLoad, in game-benchmark |
| Connections | not recorded | HikariCP 5.1.0, at most 4 |
| Driver | not recorded | Connector/J 9.7.0 |
| Changes per measurement | not recorded | 200,000, timed from the first write to the flush barrier returning |
| flushInterval | 1 s (stated for the flood only) | 100 ms for throughput; 1 s and 100 ms for the rates |

### 5.1 Throughput

Three runs of the harness exist on this machine, and they do not agree equally everywhere, so this section
gives the two that reproduce each other and says where the third parted company. Pooled, 200,000 changes per
cell, each counted in MySQL afterwards:

| Configuration | Plain URL | `rewriteBatchedStatements=true` | The flag is worth |
| --- | --- | --- | --- |
| INSERT, batchSize 500 | 13,640 | 58,080 | ×4.26 |
| INSERT, batchSize 2000 | 14,503 | 103,318 | ×7.12 |
| UPDATE, batchSize 500 | 10,822 | 16,546 | ×1.53 |
| UPDATE, batchSize 2000 | 10,395 | 11,147 | ×1.07 |

A second run of the same command on the same machine and database landed within 5% of every cell above
(13,541 / 14,691 / 10,746 / 10,213 plain, 60,913 / 103,435 / 16,642 / 11,189 rewriting), giving ×4.50, ×7.04,
×1.55 and ×1.10. A third, earlier run reported much lower plain-URL rates — 11,331 and 9,717 for INSERT —
and therefore ×5.3 and ×10.9 for the flag. That run also had plain-URL INSERT *slower* at batchSize 2000 than
at 500, which the other two reverse, so it is read here as a bad run rather than a third opinion. The
rewriting rates are the stable half of the measurement; the plain-URL ones move, and any multiplier built on
them inherits that.

So the flag is worth about ×4.3–4.5 for INSERT at batchSize 500 and about ×7 at 2000, and about ×1.5 and
×1.1 for UPDATE. §3's single ×3.8 does not survive, not because it was far off, but because one number
cannot express it: the merged `VALUES` grows with the batch, so the flag is worth more the larger the batch,
while for UPDATE, which cannot merge at all, it moves the other way.
[Java §6.2](../ogbs/OGBS-Data-Java-25-Specification-1.0.md#62-batch-throughput-and-the-jdbc-url) carries the
ranges rather than any one run's digits.

One figure is now confirmed by two independent harnesses rather than two runs of one: UPDATE at batchSize 500
with the flag on reaches 16,546 and 16,642 here and 16,700 in §3, whose harness shared no code with this one.
That is the number a live game meets, since UPDATE is the hot path once players exist, and it is the one
ceiling worth planning against.

Pooling is worth less than an earlier draft of this section claimed, and not where it claimed. With
`--pool=off`, which is `JdbcMysqlAccess` taking its connection per operation from a plain `MysqlDataSource`,
the same eight configurations reach 10,743 / 13,821 / 8,602 / 9,841 plain and 49,461 / 56,044 / 8,594 / 9,334
rewriting, so HikariCP is worth ×1.27, ×1.05, ×1.26 and ×1.06 on a plain URL and ×1.17, ×1.84, ×1.93 and
×1.19 with the flag on. That is anywhere from 5% to 93% depending on the configuration, not a flat half, and
the pattern is not "small batches": the handshake hurts most when the batch's own work is cheap, which is
true both of UPDATE at 500 and of a 2000-row INSERT the driver has merged into one statement. §3's +28% from
a hand-rolled reuser sits inside that range. A pool is worth having; it is not the limit.

Read as multipliers for the flag rather than for the pool, the same cells say something the rates alone hide.
Unpooled, the flag is worth about ×4.6 for INSERT at batchSize 500 and ×4.0 at 2000, and ×1.00 and ×0.95 for
UPDATE; the second run agrees (×4.51, ×4.98, ×0.99, ×0.88). So pooling is what makes the flag worth setting
for state writes at all, and the ×7 at 2000 is a pooled figure, not a property of the flag.

### 5.2 The producer rates

`batchSize 2000`, `maxPendingChanges 50,000`, `pendingChangeWait 200 ms`, four producers, a rewriting URL,
20 s per rate. At `flushInterval 1 s`, the condition §2 states for its flood:

| Producer rate | pendingChanges (steady / peak) | Passes | Peak heap | Accepted | Accepted/s | Refused |
| --- | --- | --- | --- | --- | --- | --- |
| 30,000/s | 14,998 / 29,967 | 19 | 230 MB | 598,005 | 29,900 | 0 |
| 60,000/s | 29,677 / 50,000 | 19 | 108 MB | 1,000,004 | 50,000 | 0 |
| unthrottled | 50,001 / 50,003 | 19 | 118 MB | 1,000,028 | 50,001 | 320 |

At `flushInterval 100 ms`, where §2's own steady figures sit:

| Producer rate | pendingChanges (steady / peak) | Passes | Peak heap | Accepted | Accepted/s | Refused |
| --- | --- | --- | --- | --- | --- | --- |
| 30,000/s | 1,524 / 3,060 | 198 | 169 MB | 598,046 | 29,902 | 0 |
| 60,000/s | 3,024 / 7,968 | 197 | 189 MB | 1,194,236 | 59,711 | 0 |
| unthrottled | 50,001 / 50,003 | 43 | 217 MB | 2,250,065 | 112,503 | 340 |

Four things these show, none of which §2 could:

1. **The interval, not the database, decides how much waits.** Below the drain rate the dirty set cycles
   between empty and one interval's production: at 30,000/s that is a peak of 3,060 and a median of 1,524
   with a 100 ms interval, against 29,967 and 14,998 with a 1 s one. This does not explain §2's 400–1,500,
   as an earlier draft of this section claimed: that run completed 19 passes in 20 seconds, so its interval
   was 1 s, and a 1 s interval at that rate leaves about 15,000 waiting. Its sampler read the trough (§2).
2. **maxPendingChanges and flushInterval together cap the accept rate.** A pass claims at most the bound and
   then waits out the rest of the interval, so while a pass still fits inside the interval the ceiling is the
   bound divided by it: with 50,000 and 1 s nothing above 50,000/s is accepted however fast the producers
   push — which is why 60,000/s and unthrottled both settle there, and why the same unthrottled producers
   reach 112,503/s once the interval is 100 ms. A deployment that wants a higher ceiling raises the bound or
   shortens the interval; the database was not the limit in either row.
3. **Backpressure arrives before refusal.** At 60,000/s against a 50,000/s ceiling every write was accepted
   and none refused: writers waited inside `admit` and room appeared within `pendingChangeWait`. Refusals
   (320 and 340) appear only for producers with no rate limit at all.
4. **The bound holds and nothing accepted is lost.** Peak pendingChanges never passed 50,003 — the bound plus
   the writers that were already inside — against 8,079,251 in §2, peak heap stayed between 108 and 230 MB
   against 2,045 MB and an OOM, and after `close` the table held exactly as many rows as there were accepted
   changes, up to 2,250,065 of them. The harness fails the run if it does not.

§2's heap exhaustion itself cannot be reproduced any more: D-WRITE-04 is in the code, so the dirty set stops
at its bound. Raising `--max-pending` toward the unbounded behaviour is the only way back to it.

Not measured, by either run: a remote database, a write mix rather than one operation at a time, indexed or
wide tables, MongoDB, behaviour during a real outage rather than under overload, and anything about the log
path, which has its own queue and bound.
