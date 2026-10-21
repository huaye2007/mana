# Data save path: multi-row UPDATE and per-table parallelism (2026-10-21)

**[English](2026-10-21-data-save-path-parallel.md)** | [简体中文](2026-10-21-data-save-path-parallel.zh-CN.md)

> Non-normative record. The current contracts live in the [Data Specification](../ogbs/OGBS-Data-1.0.md) and
> the [Data Java Development Specification](../ogbs/OGBS-Data-Java-25-Specification-1.0.md). This page holds
> the conditions and numbers behind
> [Java §6.2](../ogbs/OGBS-Data-Java-25-Specification-1.0.md#62-batch-throughput-and-the-jdbc-url) and behind
> D-WRITE-02 (one lane per table, tables in parallel).

## 1. Why

game-data keeps state in memory and writes it back asynchronously in batches, so the save path decides how much
write load one game server can carry. The [2026-10-18 load test](2026-10-18-data-save-path-load-test.md) showed
two limits:

- **UPDATE was the slow operation, and larger batches made it slower.** `rewriteBatchedStatements=true` merges
  INSERT rows into one multi-row `VALUES`, but `UPDATE … WHERE id = ?` rows stay one statement and one round
  trip each. Game state is mostly UPDATE.
- **All tables were saved one after another on one thread**, so a pass took the sum of every table's save time.

This round changed both. On MySQL/MariaDB the mapper now merges an UPDATE batch into statements of the form
`UPDATE t SET c = CASE id WHEN ? THEN ? … END, … WHERE id IN (…)`, at most 100 rows each. Each entity type and
each log repository is now a lane, and lanes run on `saveParallelism` (default 4) threads. One table is still
saved by one thread at a time.

## 2. Conditions

| | |
| --- | --- |
| Database | MySQL 9.7.1 on the same machine, over loopback |
| Machine | 18 cores, JDK 25, `-Xmx2g` |
| Pool | HikariCP, at most 4 connections |
| Load | 4 producers on distinct ids, 200,000 rows per operation, `flushInterval` 100 ms |
| URL | `rewriteBatchedStatements=true` unless stated |
| Tables | `bench_players` (one-table phases); `bench_t1` to `bench_t4` (four-table phase). Columns `id`, `score`, `updated`, no secondary index. The four-table entities carry only `@Id` and `@Table`, so they also exercise the default mapping (D-MAP-01) |

The harness is [DataSavePathLoad](../../game-benchmark/src/main/java/cn/managame/benchmark/DataSavePathLoad.java),
in a disposable database:

```shell
mvn -pl game-benchmark -am package -DskipTests
java -Xmx2g -cp game-benchmark/target/benchmarks.jar cn.managame.benchmark.DataSavePathLoad \
  "--url=jdbc:mysql://127.0.0.1:3306/ogbs_bench?rewriteBatchedStatements=true" --user=root --password=secret \
  --phase=all --parallelisms=1,2,4
```

Every run ends by checking the row count and that every row carries the updated value; all runs below passed
those checks. Each figure is one run. Read the ratios as the transferable part, and the absolute rates as this
machine's loopback ceiling.

## 3. Multi-row UPDATE, one table

| | INSERT, batch 500 | UPDATE, batch 500 | INSERT, batch 2000 | UPDATE, batch 2000 |
| --- | --- | --- | --- | --- |
| Before (one UPDATE per row) | 49,212 | 14,262 | 99,154 | 9,310 |
| After (multi-row UPDATE) | 53,871 | 45,686 | 104,658 | 72,525 |
| Ratio | ≈1.1× | ≈3.2× | ≈1.06× | ≈7.8× |

Rows per second. The INSERT path did not change; its difference is within the spread between runs that the
earlier record saw. UPDATE now gains from a larger batch (≈1.6× from 500 to 2000) instead of losing (≈0.65×
before), because a larger batch now means more rows per statement rather than a longer transaction of
single-row round trips.

## 4. Rows per statement

| Rows per statement | UPDATE, batch 500 | UPDATE, batch 2000 |
| --- | --- | --- |
| 50 | 42,701 | 66,695 |
| 100 | 45,686 | 72,525 |
| 200 | 45,429 | 74,021 |
| 500 | 46,232 | 67,255 |

From 100 to 500 the rate stays within about 10%, and 50 pays more round trips. 100 is the chosen value. It sits
in the middle of the flat region, and it keeps statements, and the row locks each one holds, small. The
2000-row batch already falls off at 500 rows per statement. Wide tables use fewer rows, so that one statement
stays under 60,000 placeholders.

## 5. Per-table parallelism, four tables, batch 500

| saveParallelism | Run 1 INSERT | Run 1 UPDATE | Run 2 INSERT | Run 2 UPDATE |
| --- | --- | --- | --- | --- |
| 1 | 49,395 | 44,309 | 51,155 | 46,290 |
| 2 | 84,328 | 81,785 | 87,211 | 82,892 |
| 4 | 115,911 | 118,026 | 133,911 | 119,775 |

Rows per second, summed over the four tables. Run 2 is the same invocation as the "after" row of §3. From 1 to 4
threads INSERT gains 2.3–2.6× and UPDATE 2.6–2.7×. The gain is below 4×, and this run did not isolate why: the
database, the pool of four connections and the producers all share one machine.

## 6. Without the driver flag

One smaller run with a plain URL (20,000 rows, batch 500) gave INSERT 9,038 and UPDATE 38,777 rows/s, and
JdbcMysqlAccess logged its startup WARNING that `rewriteBatchedStatements` is off. INSERT still depends on the
flag (≈6× against §3). UPDATE now depends on it only a little, because the component merges UPDATEs itself;
the row count differs from §3, so ≈1.2× is not a precise figure.

## 7. Correctness on the real database

- `DatabaseIntegrationTest` against the same MySQL: 249 rows are updated in three statements. A batch with a
  missing row fails with SQLState 02000, and the row that was present keeps its old value, because the
  statement rolled back. The unthrottled-producer flood still keeps its bounds and stores every accepted row.
- H2 is excluded from the multi-row path. It types CASE parameters as VARCHAR, so a boolean bound as "TRUE"
  fails against a TINYINT column, and it turns a JSON parameter into a string literal. H2 tests therefore cover
  only the per-row path, and the multi-row path is verified only against a real MySQL. The CI `database` job
  runs the same test on MySQL 8.4; that run has not been observed yet.

## 8. Not covered

- MariaDB takes the multi-row path by product name but was not run.
- A remote database, a production write mix and long runs.
- Wide tables near the placeholder limit: the row cap and the statement shape are unit-tested
  (`MysqlMappingTest.multiRowUpdatesListEveryRowOnceAndFailTheBatchWhenOneIsMissing`), but no such table was
  run against a server.
- The effect of save threads holding pool connections on concurrent Route loads.
- MongoDB: its save path did not change, and it is still unverified on a real server.
