[English](README.md) | 中文

# managame-benchmark

为每个框架组件提供 JMH 基准测试，并为每个组件记录一份基线。这样一次改动让组件变快、变慢，或者内存分配变多，都能马上看出来。它是工具，不是组件：没有任何项目依赖它；测某个组件的基准只用这一个组件，不会把它和其他组件拼在一起测。

| 包 | 基准 | 测量内容 |
| --- | --- | --- |
| `cn.managame.benchmark.common` | `MetadataBenchmark` | 构建 metadata、读取一个值、包装收到的字节（ns/op） |
| `cn.managame.benchmark.network` | `EchoThroughputBenchmark`、`EchoLatencyBenchmark` | 在回环地址上回显带长度前缀的消息：多个消息在途时的每秒吞吐，以及单条消息的往返时间 |
| `cn.managame.benchmark.runtime` | `ExecuteThroughputBenchmark`、`DispatchThroughputBenchmark` | 每秒经过 `GameRuntime.execute` 执行的任务数，以及每秒经 `Dispatcher` 交给注解处理器的玩家请求数 |
| `cn.managame.benchmark.rpc` | `CallThroughputBenchmark`、`CallLatencyBenchmark`、`NotifyThroughputBenchmark` | 多个请求在途时每秒应答的调用数和处理的通知数，以及单个调用的往返时间 |

network 回显基准给传输层施加的负载和 rpc 基准相同，所以两者的差值就是 RPC 本身带来的开销。

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
| `common`、`network`、`runtime`、`rpc`、`all` | 要运行的组件 |
| `--save` | 把本次结果记录为新的基线 |
| `--include=<regex>` | 只运行名字匹配的基准，比如 `--include=CallLatency` |
| `--threshold=<percent>` | 结果变化超过多少才算变好或变差，默认 10 |
| `--results=<dir>` | 基线文件所在目录，默认 `results` |

`jdk.net.unixdomain.tmpdir` 会传给运行基准的 JVM。在 Windows 上，JDK 的 selector 需要用它指定一个可写目录。每个基准 JVM 都使用 1 GiB 堆，并关闭 Netty 的泄漏检测。跑完一个组件需要一到四分钟。

## 基线

`results/<component>.csv` 第一行写明记录时的机器和日期，之后每行对应一个基准的一组参数：

| 列 | 含义 |
| --- | --- |
| `benchmark`、`params`、`mode` | 哪个基准、哪组参数、怎样测量（`thrpt`、`avgt`、`sample`） |
| `score`、`error`、`unit` | 结果，及其 99.9% 置信误差 |
| `alloc_bytes_per_op` | 每次操作分配的字节数，来自 JMH 的 GC profiler |
| `p50`、`p99` | 采样延迟的中位数和第 99 百分位 |

每次运行都会把各项结果和基线并排打印出来：

```
== rpc: against the baseline 2026-10-28 on Windows 11 amd64, 18 CPUs, Java 25.0.3
CallThroughputBenchmark.call   connections=1 payload=64 window=1024   612,000 -> 598,000 ops/s  -2.3% same  alloc 690 B/op
```

判定规则：
- 结果的变化同时超过阈值和两次误差之和，才标为 `better` 或 `WORSE`，否则标为 `same`。
- 分配量的变化同时超过阈值和 32 字节，才会被标出。
- 只要有任何一项变差，退出码就是 1。

## 工作流程

1. 改组件之前，先跑一次它的基准，了解它在你机器上的表现。
2. 改完再跑一次，看对比结果。出现变差的结果，就说明这次改动值得仔细检查。可以用 `-prof gc`，或者通过 JMH 自己的主类挂 profiler 来分析：`java -cp target/benchmarks.jar org.openjdk.jmh.Main <regex> -prof stack`。
3. 如果改动是有意让组件变快或变慢，或者新增了基准，就用 `--save` 保存新基线，并把 `results/` 和改动一起提交。这样这些文件的历史就是组件性能的历史。

数字只在同一台机器上才可比。这里的基线是在第一行写明的那台机器上记录的。在别的机器上，请用那台机器上的多次运行互相比较，不要拿来和这里的基线比。测量时请关掉其他重负载的程序。

## 新增基准

- 放在它所测组件对应的包里，只使用这个组件（以及它依赖的下层组件）。
- 使用 `@Fork(1)`、`@Warmup(iterations = 3, time = 2)`、`@Measurement(iterations = 5, time = 2)`；纳秒级的基准每轮用 1 秒。这样每个组件只需几分钟，而不是几小时。
- 不需要测量的准备工作放在 `@Setup` 里：payload 用同一个 buffer 的副本，context 只构建一次。
- 测异步操作时，用一个窗口（由完成回调释放的 `Semaphore`）限制在途数量，并在 `@TearDown(Level.Iteration)` 里等本轮全部完成。调用失败就让基准失败，免得一条坏掉的路径因为"跑得快"而被当成正常。
- 用 `--save` 运行一次，把新的基线行一起提交。

## 构建

```bash
mvn -o clean package
```

请先安装 managame-common、managame-network、managame-runtime 和 managame-rpc。
