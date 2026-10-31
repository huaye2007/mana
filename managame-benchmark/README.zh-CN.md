[English](README.md) | 中文

# managame-benchmark

为每个框架组件提供 JMH 基准测试，并为每个组件记录一份基线。这样一次改动让组件变快、变慢，或者内存分配变多，都能马上看出来。它是工具，不是组件：没有任何项目依赖它；测某个组件的基准只用这一个组件，不会把它和其他组件拼在一起测。

| 包 | 基准 | 测量内容 |
| --- | --- | --- |
| `cn.managame.benchmark.common` | `MetadataBenchmark` | 构建 metadata、读取一个值、包装收到的字节（ns/op） |
| `cn.managame.benchmark.network` | `EchoThroughputBenchmark`、`EchoLatencyBenchmark`，以及对应的 `…ProcessBenchmark` | 在回环地址上回显带长度前缀的消息：多个消息在途时的每秒吞吐，以及单条消息的往返时间 |
| `cn.managame.benchmark.runtime` | `ExecuteThroughputBenchmark`、`DispatchThroughputBenchmark` | 每秒经过 `GameRuntime.execute` 执行的任务数，以及每秒经 `Dispatcher` 交给注解处理器的玩家请求数 |
| `cn.managame.benchmark.rpc` | `CallThroughputBenchmark`、`CallLatencyBenchmark`、`NotifyThroughputBenchmark`，以及对应的 `…ProcessBenchmark` | 多个请求在途时每秒应答的调用数和处理的通知数，以及单个调用的往返时间 |
| `cn.managame.benchmark.data` | `RepositoryBenchmark`、`UpdateThroughputBenchmark`、`WriteThroughputBenchmark` | 实体都在内存里时业务线程的开销（读取玩家或背包、登记修改；ns/op），四个线程同时登记修改的每秒次数，以及经一个什么都不保存的 Mapper 每秒写出的修改数：不含数据库的 managame-data 自身开销 |
| `cn.managame.benchmark.mysql` | `SaveThroughputBenchmark` | 经 managame-data-mysql 每秒写入 MySQL 模式的内存 H2 数据库的行数：更新，以及插入加删除 |
| `cn.managame.benchmark.mysqlserver` | `SaveBenchmark`、`LoadBenchmark` | 对真实 MySQL（`MANAGAME_DATA_MYSQL_URL`、`_USER`、`_PASSWORD`）：每秒写入的行数（标量列、JSON 列、分组的道具、插入加删除），以及 8 个线程每秒冷加载的实体数（按 id、`getAll` 100 个、一组 20 个） |
| `cn.managame.benchmark.mongo` | `SaveBenchmark`、`LoadBenchmark` | 同样的测试，对真实 MongoDB（`MANAGAME_DATA_MONGO_URI`） |

network 回显基准给传输层施加的负载和 rpc 基准相同，所以两者的差值就是 RPC 本身带来的开销。每个基准跑两种形态：两端都在同一个基准 JVM 里；以及对应的 `…ProcessBenchmark`，服务端在单独的进程里，见[两个进程](#两个进程)。

## 运行

先安装各组件，再把基准打成一个 jar：

```bash
cd managame-benchmark && mvn -o clean package
```

在本目录下按组件名运行，或者用 `all` 运行全部：

```bash
java -Djdk.net.unixdomain.tmpdir=D:\tmp -jar target/benchmarks.jar rpc
```

| 参数 | 含义 |
| --- | --- |
| `common`、`network`、`runtime`、`rpc`、`data`、`mysql`、`mysqlserver`、`mongo`、`all` | 要运行的组件（`mysql` 是跑在 H2 上的 managame-data-mysql；`mysqlserver` 和 `mongo` 需要真实服务器，`all` 不包含它们） |
| `--save` | 把本次结果记录为新的基线 |
| `--include=<regex>` | 只运行名字匹配的基准，比如 `--include=CallLatency` |
| `--threshold=<percent>` | 结果变化超过多少才算变好或变差，默认 10 |
| `--results=<dir>` | 基线文件所在目录，默认 `results` |
| `--against=<jar>` | 不和基线比，而是和另一个版本的基准 jar 交替运行并比较，见[与改动前的代码对比](#与改动前的代码对比) |

`jdk.net.unixdomain.tmpdir` 会传给运行基准的 JVM。在 Windows 上，JDK 的 selector 需要用它指定一个可写目录。每个基准 JVM 都使用 1 GiB 堆，并关闭 Netty 的泄漏检测。跑完一个组件需要一到四分钟，用 `--against` 时是四倍。

## 基线

`results/<component>.csv` 第一行写明记录时的机器和日期，之后每行对应一个基准的一组参数。除了得分，每次运行还会用 JMH 的 GC profiler 和本项目的 `CostProfiler`，在基准 JVM 里围绕每一轮迭代测量开销：

| 列 | 含义 |
| --- | --- |
| `benchmark`、`params`、`mode` | 哪个基准、哪组参数、怎样测量（`thrpt`、`avgt`、`sample`） |
| `score`、`error`、`unit` | 结果，及其 99.9% 置信误差 |
| `alloc_bytes_per_op`、`alloc_mb_per_s` | 每次操作、每秒在堆上分配的字节数 |
| `p50`、`p99` | 采样延迟的中位数和第 99 百分位 |
| `gc_count`、`gc_ms` | 测量期间的 GC 次数和耗时 |
| `cpu_ns_per_op` | 整个基准 JVM 每次操作消耗的 CPU 时间 |
| `cpu_caller_ns_per_op` | 其中花在基准自己的线程（调用组件的线程）上的部分 |
| `cpu_eventloop_ns_per_op` | 花在 Netty 线程上的部分：连接两端的 EventLoop |
| `cpu_other_ns_per_op` | 花在其他 Java 线程上的部分：定时器、执行器、虚拟线程的载体线程 |
| `cpu_jvm_ns_per_op` | 花在 JVM 自身工作上的部分：GC 和 JIT 编译 |
| `direct_mib` | 迭代结束时 Netty 占用的直接内存，取各轮中的最大值 |
| `sent_bytes_per_op`、`received_bytes_per_op` | 每次操作在线上收发的字节数，不含 TCP/IP 头，由主动连接一方的 `CountingSocketChannel` 统计 |
| `busy_percent` | 其他程序占用了机器多少 CPU，取各轮中的最大值；对端进程不算在内 |
| `peer_cpu_ns_per_op`、`peer_cpu_eventloop_ns_per_op`、`peer_cpu_other_ns_per_op`、`peer_cpu_jvm_ns_per_op` | `…ProcessBenchmark` 中服务端进程每次操作的 CPU 时间，拆分方式同上 |
| `peer_alloc_bytes_per_op`、`peer_gc_count`、`peer_gc_ms`、`peer_direct_mib` | 服务端进程每次操作的堆分配、GC 次数和耗时、直接内存 |

线程结束后就读不到它的 CPU 时间了，而 JMH 会在最后一轮迭代取数之前就关闭基准的 fixture，连同其中的 EventLoop。所以 profiler 每 50 毫秒采样一次所有线程，已结束的线程按最后一次读数计算。

每次运行都会把各项结果和基线并排打印出来，第二行是开销；带宽等于每次操作的字节数乘以每秒操作数：

```
== rpc: against the baseline 2026-10-28 on Windows 11 amd64, 18 CPUs, Java 25.0.3
CallThroughputBenchmark.call   connections=1 payload=64 window=1024   612,000 -> 598,000 ops/s  -2.3% same  alloc 1,010 B/op
    cpu 4,210 -> 4,305 ns/op (caller 610, eventloop 3,400, other 25, jvm 270)  |  gc 41 in 52 ms  |  direct 16.0 MiB  |  net 100+79 -> 100+79 B/op, 107.0 MB/s  |  busy 4%
```

判定规则：
- 结果的变化同时超过阈值和两次误差之和，才标为 `better` 或 `WORSE`，否则标为 `same`。
- 分配量的变化同时超过阈值和 32 字节，线上字节数的变化同时超过阈值和 4 字节，才会被标出。这两项不受机器状态影响，所以 `MORE ALLOCATION` 和 `MORE BYTES` 都算变差。
- 每次操作的 CPU 时间只显示、不参与判定，因为被调度到较慢的核上时它也会升高。
- 其他程序占用超过机器十分之一的 CPU 时，得分会被拖慢：这时变差的得分标为 `unsure (machine N% busy)`，`--save` 也不会保存这个组件的结果。
- 有任何一项变差，退出码为 1；没有变差但有 `unsure` 时为 3；否则为 0。

## 两个进程

游戏服务器之间是进程间通信，每个进程有自己的 JVM、GC 和线程。`…ProcessBenchmark` 把服务端（回显服务器或 world 节点）放进单独的进程：`PeerProcess` 用同一个 jar 和基准 JVM 相同的 JVM 参数启动它，基准 JVM 自己作为客户端（game 节点）。每轮迭代前后，对端进程测量自己的消耗，结果中两端分开显示：

```
CallThroughputProcessBenchmark.call   connections=1 payload=64 window=1024   598,000 ops/s  new  alloc 610 B/op
    client cpu 2,450 ns/op (caller 610, eventloop 1,600, other 40, jvm 200)  |  gc 4 in 15 ms  |  direct 3.4 MiB  |  net 100+79 B/op, 107.0 MB/s  |  busy 4%
    server cpu 1,700 ns/op (eventloop 1,550, other 0, jvm 150)  |  alloc 400 B/op  |  gc 2 in 8 ms  |  direct 2.0 MiB
```

两个进程仍然共享这台机器的 CPU。在单独进程里的 world 节点无法像进程内那样直接释放客户端的窗口，所以对于通知：它每处理 64 个就回一个通知，并在每轮迭代结束时，通过一次调用回报还没确认的数量。服务端的分配量变化也会标为 `SERVER ALLOCATION`，规则和客户端相同。

## 与改动前的代码对比

基线是在另一个时间点记录的，而机器此刻的状态未必和当时一样。在同时有性能核和能效核的笔记本上，前台程序忙的时候，Windows 会把后台运行的基准挪到较慢的核上，所有结果可能在几分钟内下降一半，而代码一行没变。每次操作的分配量不随机器变化，所以"结果变差、分配量没变"正是需要核实的情况。

`--against` 用来区分是机器的原因还是代码的原因。先用改动前的代码构建一个基准 jar，放在 `target/` 之外，再用当前版本和它对比：

```bash
git stash                                          # 回到改动前的代码
cd ../managame-rpc && mvn -o install -DskipTests
cd ../managame-benchmark && mvn -o clean package && cp target/benchmarks.jar D:/tmp/before.jar
git stash pop                                      # 恢复改动
cd ../managame-rpc && mvn -o install -DskipTests
cd ../managame-benchmark && mvn -o clean package
java -Djdk.net.unixdomain.tmpdir=D:\tmp -jar target/benchmarks.jar rpc --against=D:\tmp\before.jar
```

两个 jar 交替运行，对方先跑，各跑两次，这样两者面对的是同一时段里的机器。对方 jar 的输出写进一个临时目录下的日志文件，运行结束时会打印这个目录。

```
== rpc: against D:\tmp\before.jar, each run 2 times in turn, it first
CallThroughputBenchmark.call   connections=1 payload=64 window=1024   629,824/627,016 -> 621,989/618,503 ops/s  -1.3% same (it moved 0.4% itself)  alloc 1,010 -> 1,010 B/op
NotifyThroughputBenchmark.notifyPeer   connections=1 payload=64 window=1024   774,468/378,191 -> 798,003/791,224 ops/s  +38.0% unsure (it moved 69.0% itself)  alloc 404 -> 405 B/op
```

判定规则：
- 同时满足以下三条，才标为 `better` 或 `WORSE`：变化超过阈值；当前版本的两次结果都落在对方两次结果的同一侧；两者均值之差大于对方自己两次运行之间的波动。
- 变化超过阈值、但没有这么明确的，标为 `unsure`，意思是机器本身的波动和结果的变化一样大。
- 分配量的判定和基线模式相同。
- 这个模式不保存任何结果。有变差时退出码为 1；没有变差但有 `unsure` 时为 3；否则为 0。

出现 `unsure` 时，请关掉让机器忙碌的程序，或者把电源模式切到"最佳性能"，然后重跑。

## 工作流程

1. 改组件之前，先跑一次它的基准，了解它在你机器上的表现。
2. 改完再跑一次，看对比结果。如果某项变差了、但分配量没变，可能是机器的原因：先用 `--against` 核实，再去代码里找原因。核实后确实变差，就说明这次改动值得仔细检查。可以用 `-prof gc`，或者通过 JMH 自己的主类挂 profiler 来分析：`java -cp target/benchmarks.jar org.openjdk.jmh.Main <regex> -prof stack`。
3. 如果改动是有意让组件变快或变慢，或者新增了基准，就用 `--save` 保存新基线，并把 `results/` 和改动一起提交。这样这些文件的历史就是组件性能的历史。

数字只在同一台机器上才可比。这里的基线是在第一行写明的那台机器上记录的。在别的机器上，请用那台机器上的多次运行互相比较，不要拿来和这里的基线比。测量时请关掉其他重负载的程序；机器繁忙时跑出的结果不要保存为基线。

## 新增基准

- 放在它所测组件对应的包里，只使用这个组件（以及它依赖的下层组件）。
- 使用 `@Fork(1)`、`@Warmup(iterations = 3, time = 2)`、`@Measurement(iterations = 5, time = 2)`；纳秒级的基准每轮用 1 秒。这样每个组件只需几分钟，而不是几小时。
- 不需要测量的准备工作放在 `@Setup` 里：payload 用同一个 buffer 的副本，context 只构建一次。
- 测异步操作时，用一个窗口（由完成回调释放的 `Semaphore`）限制在途数量，并在 `@TearDown(Level.Iteration)` 里等本轮全部完成。调用失败就让基准失败，免得一条坏掉的路径因为"跑得快"而被当成正常。
- 走网络的基准，主动连接一方要使用 `CountingSocketChannel`（在 `CountingSocketChannel.newGroup` 创建的 group 上设置 `channel(CountingSocketChannel.class)`），这样才能统计线上字节数。
- 有服务端的基准，也要能把服务端放到单独进程里跑：fixture 在需要时用 `PeerProcess.start(<主类>)` 启动服务端；这个主类的 `main` 启动服务端后调用 `PeerProcess.serve`；再写一个 `…ProcessBenchmark` 子类，覆盖 `otherProcess()` 打开这个形态。
- 用 `--save` 运行一次，把新的基线行一起提交。

## 构建

```bash
mvn -o clean package
```

请先安装 managame-common、managame-network、managame-runtime、managame-rpc、managame-data、managame-data-mysql 和
managame-data-mongo。`mysqlserver` 和 `mongo` 基准会在环境变量指定的服务器里重新建表或建库：请给一个可以丢弃的库。服务器
和基准在同一台机器上时，机器忙碌度总会超过 Bench 允许保存基线的上限，所以它们的结果要手工对比，或用 `--against`。
