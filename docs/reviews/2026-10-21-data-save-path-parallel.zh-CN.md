# Data 保存路径：多行 UPDATE 与按表并行（2026-10-21）

[English](2026-10-21-data-save-path-parallel.md) | **[简体中文](2026-10-21-data-save-path-parallel.zh-CN.md)**

> 非规范性记录。现行契约见 [Data 规范](../ogbs/OGBS-Data-1.0.zh-CN.md) 与
> [Data Java 开发规范](../ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md)。本页记录
> [Java §6.2](../ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md#62-批量吞吐与-jdbc-url) 与 D-WRITE-02
> （每张表一条通道、不同表并行）背后的测量条件与数字。

## 1. 为什么做

game-data 把状态放在内存里、异步批量写回，因此一台游戏服务器能承受多大的写入量取决于保存路径。
[2026-10-18 的压测](2026-10-18-data-save-path-load-test.zh-CN.md)暴露了两个上限：

- **UPDATE 是慢的那个操作，而且批次越大越慢。** `rewriteBatchedStatements=true` 会把 INSERT 的行合并成一条多行
  `VALUES`，但 `UPDATE … WHERE id = ?` 的每一行仍是一条语句、一次往返。游戏状态的写入以 UPDATE 为主。
- **所有表都在一个线程上依次保存**，一轮的耗时是每张表保存耗时之和。

这一轮两处都改了。在 MySQL/MariaDB 上，Mapper 现在把一个 UPDATE 批次合并成
`UPDATE t SET c = CASE id WHEN ? THEN ? … END, … WHERE id IN (…)` 形式的语句，每条最多 100 行。每个实体类型、每个
日志仓储各是一条通道，通道在 `saveParallelism`（默认 4）个线程上并行；同一张表仍然一次只由一个线程保存。

## 2. 测量条件

| | |
| --- | --- |
| 数据库 | 同机 MySQL 9.7.1，经 loopback |
| 机器 | 18 核，JDK 25，`-Xmx2g` |
| 连接池 | HikariCP，最多 4 个连接 |
| 负载 | 4 个生产者，各自使用不同的 id；每个操作 200,000 行；`flushInterval` 100 毫秒 |
| URL | 除非另行说明，均为 `rewriteBatchedStatements=true` |
| 表 | 单表阶段用 `bench_players`；四表阶段用 `bench_t1` 到 `bench_t4`。列为 `id`、`score`、`updated`，没有二级索引。四表阶段的实体只有 `@Id` 与 `@Table`，因此也顺带验证了默认映射（D-MAP-01） |

压测程序是 [DataSavePathLoad](../../game-benchmark/src/main/java/cn/managame/benchmark/DataSavePathLoad.java)，
在可丢弃的库上运行：

```shell
mvn -pl game-benchmark -am package -DskipTests
java -Xmx2g -cp game-benchmark/target/benchmarks.jar cn.managame.benchmark.DataSavePathLoad \
  "--url=jdbc:mysql://127.0.0.1:3306/ogbs_bench?rewriteBatchedStatements=true" --user=root --password=secret \
  --phase=all --parallelisms=1,2,4
```

每次运行结束时都会校验行数，以及每一行都带着更新后的值；下面所有运行都通过了校验。每个数字来自一次运行。
可迁移的是比值，绝对速率只是这台机器 loopback 上的上限。

## 3. 多行 UPDATE，单表

| | INSERT，batch 500 | UPDATE，batch 500 | INSERT，batch 2000 | UPDATE，batch 2000 |
| --- | --- | --- | --- | --- |
| 改动前（每行一条 UPDATE） | 49,212 | 14,262 | 99,154 | 9,310 |
| 改动后（多行 UPDATE） | 53,871 | 45,686 | 104,658 | 72,525 |
| 比值 | 约 1.1 倍 | 约 3.2 倍 | 约 1.06 倍 | 约 7.8 倍 |

单位为行/秒。INSERT 路径没有改动，差异在上一份记录观察到的运行间离散之内。UPDATE 现在随批次变大而变快（从 500
到 2000 约 1.6 倍），而不是像以前那样变慢（约 0.65 倍）：更大的批次现在意味着每条语句更多行，而不是一个更长的、
由单行往返组成的事务。

## 4. 每条语句的行数

| 每条语句行数 | UPDATE，batch 500 | UPDATE，batch 2000 |
| --- | --- | --- |
| 50 | 42,701 | 66,695 |
| 100 | 45,686 | 72,525 |
| 200 | 45,429 | 74,021 |
| 500 | 46,232 | 67,255 |

100 到 500 之间速率相差约 10% 以内，50 则多付往返。选定 100：它位于平坦区中间，而且让每条语句以及它持有的行锁都
保持较小；2000 行的批次在每条 500 行时已经开始下降。宽表使用更少的行，使一条语句的占位符不超过 60,000 个。

## 5. 按表并行，四张表，batch 500

| saveParallelism | 第 1 次 INSERT | 第 1 次 UPDATE | 第 2 次 INSERT | 第 2 次 UPDATE |
| --- | --- | --- | --- | --- |
| 1 | 49,395 | 44,309 | 51,155 | 46,290 |
| 2 | 84,328 | 81,785 | 87,211 | 82,892 |
| 4 | 115,911 | 118,026 | 133,911 | 119,775 |

单位为行/秒，四张表合计。第 2 次与 §3 的"改动后"是同一次运行。从 1 个线程到 4 个线程，INSERT 提升 2.3~2.6 倍，
UPDATE 提升 2.6~2.7 倍。提升不到 4 倍，这次测量没有分离出原因：数据库、4 个连接的连接池和生产者都在同一台机器上。

## 6. 不设驱动参数

一次较小的运行使用不带参数的 URL（20,000 行，batch 500），INSERT 9,038、UPDATE 38,777 行/秒，JdbcMysqlAccess 在
启动时记录了 `rewriteBatchedStatements` 未开启的 WARNING。INSERT 仍然依赖这个参数（与 §3 相比约 6 倍）。UPDATE
现在只略微依赖它，因为组件自己合并 UPDATE；这次的行数与 §3 不同，所以约 1.2 倍不是一个精确的数字。

## 7. 真实数据库上的正确性

- 在同一个 MySQL 上运行 `DatabaseIntegrationTest`：249 行分三条语句更新。含一条缺失行的批次以 SQLState 02000
  失败，存在的那一行保持旧值，因为整条语句已回滚。不限速生产者的洪水测试仍守住各项上限，并保存了每一条被接纳的行。
- H2 被排除在多行路径之外。它把 CASE 的参数当作 VARCHAR，以"TRUE"绑定的 boolean 写进 TINYINT 列会失败；它还把
  JSON 参数变成字符串字面量。因此 H2 测试只覆盖逐行路径，多行路径只在真实 MySQL 上验证。CI 的 `database` 任务
  会在 MySQL 8.4 上运行同一测试，但这次还没有观察到那次运行。

## 8. 未覆盖

- MariaDB 按产品名走多行路径，但没有运行过。
- 远程数据库、生产环境的写入组合与长时间运行。
- 接近占位符上限的宽表：行数上限与语句形状有单元测试
  （`MysqlMappingTest.multiRowUpdatesListEveryRowOnceAndFailTheBatchWhenOneIsMissing`），但没有在服务器上跑过这样的表。
- 保存线程占用连接池连接对并发 Route 加载的影响。
- MongoDB：保存路径没有改动，仍未在真实服务器上验证。
