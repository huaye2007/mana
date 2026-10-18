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

The harness was written in a temporary directory and is **not in this repository**, so the figures below are
measurements, not something this page lets a reader re-run. Treat the ratios as the transferable part and
the absolute rates as one machine's loopback ceiling.

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

## 3. Throughput ceilings

| Configuration | Rows/s |
| --- | --- |
| INSERT, batchSize 500 | 47,000 |
| INSERT, batchSize 2000 | 77,000 |
| UPDATE, batchSize 500 | 16,700 |
| UPDATE, batchSize 2000 | 10,800 |

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

Not measured: a remote database, a write mix rather than one operation at a time, indexed or wide tables,
MongoDB, and behaviour during a real outage rather than under overload.
