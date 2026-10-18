# Data 保存路径压测（2026-10-18）

[English](2026-10-18-data-save-path-load-test.md) | **[简体中文](2026-10-18-data-save-path-load-test.zh-CN.md)**

> 非规范记录。当前契约由 [Data 标准](../ogbs/OGBS-Data-1.0.zh-CN.md)与
> [Data Java 开发规范](../ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md)持有。本页保存的是
> [Java §6.2](../ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md#62-批量吞吐与-jdbc-url) 吞吐建议
> 以及同日保存路径改动背后的条件与数据。

## 1. 测了什么，结论能外推多远

这是 game-data 第一次对真实数据库做压测。在此之前仓库里没有任何 Data 基准：game-benchmark 只有 Metadata、
RouteExecutor 与 RpcWire，而且并不依赖 game-data；[缺口分析](2026-10-17-framework-gap-analysis.zh-CN.md)
§3.1 与 §3.2 是从源码推理出来的，没有实测数据。

压测代码写在临时目录里，**没有进仓库**，所以下面是测量结果，本页并不让读者照着重跑。可外推的是比值，绝对速率
只是某一台机器在 loopback 上的上限。

| | |
| --- | --- |
| 数据库 | 同机 MySQL 9.7.1，走 loopback |
| 机器 | 18 核，JDK 25，`-Xmx2g` |
| 表结构 | 专用库 `ogbs_bench`，表 `bench_players`，三列，无二级索引 |
| 路径 | GameData → SingleRepository → 写回 → MysqlEntityMapper → JdbcMysqlAccess |
| 生产者 | 4 线程，实体 id 互不相同（不发生合并：每条变更都是一行） |

## 2. 不限速的生产在几秒内耗尽堆

`flushInterval=1s`、`batchSize=2000`、生产者不限速时，进程在 **7 秒**内因堆耗尽退出：

| 读数 | 值 |
| --- | --- |
| 结束时的 `DataStats.pendingChanges()` | 8,079,251 |
| 堆 | 806 MB → 2045 MB |
| `DataStats.flushes()` | **全程为 0** |

数据库全程健康，没有任何失败与重试。脏集合根本没有上限，于是超过排空能力的生产直接变成堆占用。`flushes()`
始终为 0 是另一半原因：一轮 pass 认领整个脏集合，所以那一轮正在跑的 pass，工作量由生产者而不是由管线决定，
运行期间从外部看不到任何进展。

同一套环境下限速运行完全稳定，这把问题定位在"生产快于排空"，而不是数据库或 Mapper 的任何地方：

| 生产速率 | `pendingChanges()` | 20 秒内的轮数 | 堆 |
| --- | --- | --- | --- |
| 30,000/s | 400~1,500，不增长 | 19 | 约 200 MB |
| 60,000/s | 400~1,500，不增长 | 19 | 约 200 MB |
| 不限速 | 8,079,251 且持续增长 | 0 | 2045 MB，随后 OOM |

## 3. 吞吐上限

| 配置 | 行/秒 |
| --- | --- |
| INSERT，batchSize 500 | 47,000 |
| INSERT，batchSize 2000 | 77,000 |
| UPDATE，batchSize 500 | 16,700 |
| UPDATE，batchSize 2000 | 10,800 |

JDBC URL 上的 `rewriteBatchedStatements=true` 对 INSERT 值 **3.8 倍**（10,000 → 38,500 行/秒），对 UPDATE
只有 **1.7 倍**。不对称来自驱动：一批 INSERT 会被改写成一条多行 `VALUES`，而 `UPDATE … WHERE id=?` 无法折叠成
单条语句，每行仍是一次往返。同样的原因使更大的批次有利于 INSERT、不利于 UPDATE，所以一个 batchSize 不可能对两
边都最优。每批次新开连接而不用连接池约损失 28%：部署时值得修，但不是瓶颈。

## 4. 由此改了什么

| 发现 | 落点 |
| --- | --- |
| 一轮 pass 认领整个脏集合 | `PendingBuffer.claim` 取有限边界，与 `LogRepository.drain` 既有做法一致（[Java §5.2](../ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md#52-一轮保存)） |
| 脏集合没有上限 | D-WRITE-04：加上限，写入方先等 `pendingChangeWait` 再被拒绝；绝不为腾空间丢弃已接纳的变更 |
| 缓存没有条目上限 | D-CACHE-03：按 Repository 加上限，只淘汰干净条目 |
| 文档完全没提 `rewriteBatchedStatements` | [Java §6.2](../ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md#62-批量吞吐与-jdbc-url) |

同样的洪水场景现在是一条测试 `DatabaseIntegrationTest.unthrottledProducersAreBoundedAndEveryAcceptedChangeIsStored`，
对着同一个本地 MySQL 运行，各项上限调低以便在几秒内结束。四个不限速生产者跑五秒，
`maxPendingChanges=5,000`、`maxCacheEntries=5,000`、`pendingChangeWait=200 毫秒`、`flushInterval=100 毫秒`：

| 读数 | 修复前 | 修复后 |
| --- | --- | --- |
| `pendingChanges()` 峰值 | 8,079,251 且持续增长 | 5,002（上限 5,000，超出的是四个写入方） |
| 缓存条目峰值 | 无上限 | 10,007（上限，加上不可淘汰的脏条目） |
| 完成的轮数 | 0 | 8 |
| 接纳 / 拒绝 | — | 50,007 / 84 |
| close 之后 MySQL 中的行数 | — | 50,007，与接纳数相等 |
| 结果 | 7 秒 OOM | 平稳 |

接纳速率约 10,000 行/秒，正是 §3 中不带参数的 URL 的数值：测试读取的环境变量里没有
`rewriteBatchedStatements`，而这正是 §3 要说的那个缺口。

未测：远程数据库、混合写入构成（而非单一操作）、带索引或宽表、MongoDB，以及真实故障期间（而非过载期间）的行为。
