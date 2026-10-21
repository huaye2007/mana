# game-benchmark

**[English](README.md)** | [简体中文](README.zh-CN.md)

Benchmarks and load drivers that guard the hot paths of the other modules. Nothing here ships in a framework
artifact and nothing here runs during the build: `mvn clean verify` compiles this module and executes none of
it. It is not a framework component, so it has no OGBS specification pair of its own — every number it
produces belongs to the component it measures, whose specification holds the contract.

<a id="构建与运行"></a>

## Build and run

```shell
mvn -pl game-benchmark -am package -DskipTests
java -jar game-benchmark/target/benchmarks.jar            # every JMH benchmark
java -jar game-benchmark/target/benchmarks.jar RpcWire    # by name
```

| JMH benchmark | What it measures |
| --- | --- |
| [MetadataBenchmark](src/main/java/cn/managame/benchmark/MetadataBenchmark.java) | Reading typed values out of encoded Metadata: key scan plus codec decode |
| [RouteExecutorBenchmark](src/main/java/cn/managame/benchmark/RouteExecutorBenchmark.java) | Admission, mailbox handoff and consumer wake-up per task, virtual versus striped, over 1 to 10,000 Routes |
| [RpcWireBenchmark](src/main/java/cn/managame/benchmark/RpcWireBenchmark.java) | Encoding and decoding one RPC frame |

<a id="data-保存路径压测"></a>

## DataSavePathLoad: the game-data save path

[DataSavePathLoad](src/main/java/cn/managame/benchmark/DataSavePathLoad.java) drives business writes through a
`SingleRepository`, the write-behind pipeline, `MysqlEntityMapper` and `JdbcMysqlAccess` into a real MySQL. It
re-runs what the [save-path load test](../docs/reviews/2026-10-18-data-save-path-load-test.md) recorded, so
those figures can be checked on another machine instead of only cited:

- INSERT and UPDATE throughput at each `batchSize`, measured from the first write to the flush barrier
  returning, so the rate covers everything the save path does with a change.
- What `rewriteBatchedStatements=true` is worth for each operation, by measuring the same configuration with
  the flag and without it.
- What a producer rate above the drain rate does: peak and steady `DataStats.pendingChanges()`,
  `DataStats.flushes()` while the window runs, peak heap, and how many writes were accepted and refused.
- What `saveParallelism` is worth: the same rows spread evenly over four tables (`bench_t1` to `bench_t4`),
  INSERTed then UPDATEd at each value of `--parallelisms`, with the rewriting URL and the first batch size.

A main class rather than a JMH benchmark, unlike the three above. JMH measures a steady state reached by
repeating one operation; here the subject is what a whole pipeline does over seconds — passes completing, a
dirty set sitting at its bound, heap growing — the database is stateful, so an invocation cannot be repeated
without deciding what to do with the rows it left behind, and warmup would have filled the table before the
measurement started. The phases are therefore timed by hand, each against a table the harness creates and
drops.

```shell
mvn -pl game-benchmark -am package -DskipTests
java -Xmx2g -cp game-benchmark/target/benchmarks.jar cn.managame.benchmark.DataSavePathLoad \
  "--url=jdbc:mysql://127.0.0.1:3306/ogbs_bench?rewriteBatchedStatements=true" --user=root --password=secret
```

Quote the URL: `?` and `&` are shell metacharacters. Without `--url` (or `OGBS_BENCH_MYSQL_URL`) the harness
prints its options and exits without touching anything.

| Option | Default | Meaning |
| --- | --- | --- |
| `--url` / `OGBS_BENCH_MYSQL_URL` | none; nothing runs without it | JDBC URL, including the database |
| `--user` / `OGBS_BENCH_MYSQL_USER` | `root` | Database user |
| `--password` / `OGBS_BENCH_MYSQL_PASSWORD` | empty | Database password |
| `--phase` | `all` | `throughput`, `sweep`, `tables` or `all` |
| `--parallelisms` | `1,4` | `saveParallelism` values for the four-table phase |
| `--rows` | 200,000 | Changes per operation in the throughput phase |
| `--producers` | 4 | Writing threads, each on distinct ids so no change merges into another |
| `--batch-sizes` | `500,2000` | `GameDataBuilder.batchSize` values to measure |
| `--jdbc` | `both` | Whether the throughput phase measures a plain URL, a rewriting one, or both |
| `--pool` | `on` | HikariCP, or a new connection per batch; `JdbcMysqlAccess` takes a connection per operation, so `off` pays a handshake per batch |
| `--flush-ms` | 100 | `flushInterval` during the throughput phase, short so the pipeline starts promptly |
| `--max-pending` | 50,000 | `maxPendingChanges` |
| `--pending-wait-ms` | 200 | `pendingChangeWait` |
| `--rates` | `30000,60000,0` | Sweep producer rates in changes per second; 0 is unthrottled |
| `--sweep-seconds` | 20 | How long each rate is held |
| `--sweep-batch` | 2000 | `batchSize` during the sweep |
| `--sweep-flush-ms` | 1000 | `flushInterval` during the sweep |

The throughput phase sets `rewriteBatchedStatements` itself, both ways, and ignores whatever the URL said. The
sweep keeps the URL as given and prints it, because which producer rates stay steady depends on what the
pipeline drains, and that is not one number: it is the lower of what the URL and the database manage and of
`maxPendingChanges / flushInterval`, since a pass claims at most the bound and then waits out the rest of the
interval. `--sweep-flush-ms` and `--max-pending` move that ceiling as much as the URL does.

Every run ends in checks, not only in numbers: a throughput configuration verifies that the table holds
exactly `rows` rows and that every one of them carries the updated value (summed over the four tables in the
tables phase), and a sweep verifies after `close`
that the table holds exactly as many rows as there were accepted changes. A mismatch fails the run.

### What it needs, and what it will not do

- A real MySQL and a **disposable** database. The harness drops and recreates `bench_players` and
  `bench_t1` to `bench_t4`, and refuses to start when the database holds any other table, so it cannot be
  aimed at a schema that matters.
- Nothing else: no test executes against a database, so `mvn clean verify` and `mvn clean verify -Pquality`
  never open a connection. The opt-in MySQL tests in game-data are separate and read `OGBS_DATA_MYSQL_URL`.
- The heap exhaustion the original run hit cannot be reproduced as it was, because `maxPendingChanges` now
  bounds the dirty set; what the sweep shows instead is the bound holding while passes keep completing.
  Raising `--max-pending` toward the unbounded behaviour is how to watch memory grow again.
- HikariCP prints an SLF4J "Failed to load class StaticLoggerBinder" notice on stderr, because no logging
  binding is on the classpath. It is noise, and the measurements go to stdout.

<a id="构建与验证"></a>

## Build and validation

`mvn -pl game-benchmark -am package -DskipTests` is the whole check for this module: it has no tests, because
a benchmark asserts nothing. Changing it cannot change framework behaviour, but it does depend on game-core,
game-rpc, game-runtime and game-data, so run `mvn clean verify` from the root after changing anything those
modules publish.
