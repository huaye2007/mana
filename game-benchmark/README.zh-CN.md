# game-benchmark

[English](README.md) | **[简体中文](README.zh-CN.md)**

守护其他模块热点路径的基准与压测驱动。这里的任何东西都不随框架 artifact 发布，也都不在构建中运行：
`mvn clean verify` 只编译本模块，不执行其中任何一项。它不是框架组件，因此没有自己的 OGBS 规范对——它产出的每个
数字都属于被测量的那个组件，契约由该组件的规范持有。

## 构建与运行

```shell
mvn -pl game-benchmark -am package -DskipTests
java -jar game-benchmark/target/benchmarks.jar            # 全部 JMH 基准
java -jar game-benchmark/target/benchmarks.jar RpcWire    # 按名字指定
```

| JMH 基准 | 测量内容 |
| --- | --- |
| [MetadataBenchmark](src/main/java/cn/managame/benchmark/MetadataBenchmark.java) | 从编码后的 Metadata 读取类型化取值：键扫描加编解码 |
| [RouteExecutorBenchmark](src/main/java/cn/managame/benchmark/RouteExecutorBenchmark.java) | 每个任务的准入、信箱交接与消费者唤醒开销，virtual 与 striped 对比，Route 数从 1 到 10,000 |
| [RpcWireBenchmark](src/main/java/cn/managame/benchmark/RpcWireBenchmark.java) | 单个 RPC 帧的编码与解码 |

<a id="data-保存路径压测"></a>

## DataSavePathLoad：game-data 保存路径

[DataSavePathLoad](src/main/java/cn/managame/benchmark/DataSavePathLoad.java) 把业务写入经 `SingleRepository`、
写回管线、`MysqlEntityMapper` 与 `JdbcMysqlAccess` 一路压到真实 MySQL。它重跑
[保存路径压测记录](../docs/reviews/2026-10-18-data-save-path-load-test.zh-CN.md)里的测量，使那些数字可以在另一台
机器上复核，而不只是被引用：

- 每个 `batchSize` 下的 INSERT 与 UPDATE 吞吐，从第一次写入计时到 flush 栅栏返回，因此速率涵盖保存路径对一条
  变更所做的全部工作。
- `rewriteBatchedStatements=true` 对各操作值多少：同一配置分别带与不带该参数各测一次。
- 生产速率超过排空能力会发生什么：`DataStats.pendingChanges()` 的峰值与稳态、窗口期内的
  `DataStats.flushes()`、堆占用峰值，以及接纳与拒绝的写入条数。
- `saveParallelism` 值多少：同样的行数平均分到四张表（`bench_t1` 到 `bench_t4`），按 `--parallelisms` 的每个取值
  先 INSERT 再 UPDATE，使用带改写参数的 URL 与第一个 batch size。

它是 main 类而不是上面三个那样的 JMH 基准。JMH 测量的是靠重复单个操作达到的稳态；这里关心的是整条管线在数秒
量级上的行为——pass 不断完成、脏集合停在上限、堆增长——而且数据库是有状态的，一次调用无法在不决定如何处理它
留下的行的前提下重复，warmup 也会在测量开始前就把表填满。因此各阶段由手工计时，每个阶段都针对压测自己建立并
删除的表。

```shell
mvn -pl game-benchmark -am package -DskipTests
java -Xmx2g -cp game-benchmark/target/benchmarks.jar cn.managame.benchmark.DataSavePathLoad \
  "--url=jdbc:mysql://127.0.0.1:3306/ogbs_bench?rewriteBatchedStatements=true" --user=root --password=secret
```

URL 要加引号：`?` 与 `&` 是 shell 元字符。不给 `--url`（或 `OGBS_BENCH_MYSQL_URL`）时，压测只打印选项说明并
退出，不碰任何东西。

| 选项 | 默认值 | 含义 |
| --- | --- | --- |
| `--url` / `OGBS_BENCH_MYSQL_URL` | 无；不给就什么都不跑 | JDBC URL，含库名 |
| `--user` / `OGBS_BENCH_MYSQL_USER` | `root` | 数据库用户 |
| `--password` / `OGBS_BENCH_MYSQL_PASSWORD` | 空 | 数据库口令 |
| `--phase` | `all` | `throughput`、`sweep`、`tables` 或 `all` |
| `--parallelisms` | `1,4` | 四表阶段要测量的 `saveParallelism` 取值 |
| `--rows` | 200,000 | 吞吐阶段每个操作的变更条数 |
| `--producers` | 4 | 写入线程数，各自使用互不相同的 id，因此不会有变更被合并 |
| `--batch-sizes` | `500,2000` | 要测量的 `GameDataBuilder.batchSize` 取值 |
| `--jdbc` | `both` | 吞吐阶段测不带参数的 URL、带改写参数的 URL，还是两者都测 |
| `--pool` | `on` | 用 HikariCP，还是每批次新开连接；`JdbcMysqlAccess` 每次操作取一个连接，所以 `off` 意味着每批次付一次握手 |
| `--flush-ms` | 100 | 吞吐阶段的 `flushInterval`，取短值让管线尽快开始 |
| `--max-pending` | 50,000 | `maxPendingChanges` |
| `--pending-wait-ms` | 200 | `pendingChangeWait` |
| `--rates` | `30000,60000,0` | 速率扫描的生产速率（变更/秒），0 表示不限速 |
| `--sweep-seconds` | 20 | 每个速率保持多久 |
| `--sweep-batch` | 2000 | 扫描期间的 `batchSize` |
| `--sweep-flush-ms` | 1000 | 扫描期间的 `flushInterval` |

吞吐阶段自己设置 `rewriteBatchedStatements`，带与不带各测一次，并忽略 URL 原本写了什么。速率扫描保持 URL 原样
并打印出来，因为哪些生产速率能保持平稳取决于管线排空多快，而那不是一个数字：它是「URL 与数据库能达到的速率」和
`maxPendingChanges / flushInterval` 两者中较低的那个——一轮 pass 最多认领上限那么多，然后把间隔余下的时间等完。
`--sweep-flush-ms` 与 `--max-pending` 对这个上限的影响不亚于 URL。

每次运行都以检查结尾，而不只是给出数字：一个吞吐配置会校验表中恰好有 `rows` 行、且每一行都带着更新后的取值（四表阶段按四张表合计）；
一次速率扫描会在 `close` 之后校验表中行数恰好等于被接纳的变更数。不相符即运行失败。

### 它需要什么，以及它不会做什么

- 真实 MySQL 与一个**可丢弃**的库。压测会删除并重建 `bench_players` 与 `bench_t1` 到 `bench_t4`，并在该库还有任何其他表时拒绝启动，因此
  不可能被指向一个有价值的 schema。
- 别无其他：没有任何测试对着数据库执行，因此 `mvn clean verify` 与 `mvn clean verify -Pquality` 从不建立连接。
  game-data 中那些可选的 MySQL 测试是另一回事，它们读取 `OGBS_DATA_MYSQL_URL`。
- 当初那次堆耗尽无法原样复现，因为 `maxPendingChanges` 现在给脏集合加了上限；速率扫描展示的是上限生效、同时
  pass 持续完成。想再看到内存增长，把 `--max-pending` 往无上限的方向调大。
- HikariCP 会在 stderr 上打印一条 SLF4J "Failed to load class StaticLoggerBinder" 提示，因为 classpath 上没有
  日志绑定。那只是噪声，测量结果走 stdout。

## 构建与验证

`mvn -pl game-benchmark -am package -DskipTests` 就是本模块的全部检查：它没有测试，因为基准不做断言。改动它不会
改变框架行为，但它依赖 game-core、game-rpc、game-runtime 与 game-data，所以在改动这些模块发布的内容之后，要从
根目录运行 `mvn clean verify`。
