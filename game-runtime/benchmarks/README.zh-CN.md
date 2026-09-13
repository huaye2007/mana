MethodHandle 与动态生成字节码的独立对照见[对比方法与运行脚本](BYTECODE-COMPARISON.md)、[实测报告](BYTECODE-RESULTS.md)。

# Runtime 调用性能基准

[使用说明](../README.zh-CN.md) · [实测结果](RESULTS.md)

基准不引入额外依赖，单独编译，不进入 Runtime JAR。对比普通 Java 调用、优化前的数组调用和当前预绑定调用器，并测量完整 Route 调度。

## 运行

使用 JDK 25 和 PowerShell 7，在仓库根目录执行：

~~~powershell
mvn -pl game-runtime test
pwsh -File game-runtime/benchmarks/run.ps1
~~~

默认每个场景启动 3 个独立 JVM；每个 JVM 先预热 3 轮，再测量 5 轮。微基准每轮 250ms，使用 8 个绑定目标，固定 256MiB 堆和 G1。不同 fork 轮换对比场景顺序。

~~~powershell
pwsh -File game-runtime/benchmarks/run.ps1 -Mode micro -Targets 1
pwsh -File game-runtime/benchmarks/run.ps1 -Mode load -Routes 1
~~~

每次运行覆盖 `target/benchmarks/results.jsonl` 和 `summary.json`。JSONL 保留配置、Java 环境和逐轮数据。编译、执行或业务校验失败时脚本直接报错。其他平台也可用 javac/java 执行相同源码，脚本使用平台对应的类路径分隔符。

## 单独调用测量

覆盖 2、3、4 参数 Command、单参数 Event 和无参 Cron。4 参数场景包含重复语义参数，用于验证通用路径仍然只解析一次。

- `direct`：经过相同基准调用接口的普通 Java 方法调用。
- `legacy`：基准中保留的旧实现，Command 使用两个数组，Event/Cron 使用一个数组；不会打入 Runtime JAR。
- `bound`：生产代码中的 `HandlerInvokers`。

多个目标为相同方法签名的不同对象和句柄，轮流调用。1024 个不同输入及语义值、Invocation 均预先构造；不包含路由解析与准入成本。业务结果通过校验和检查，避免空测量或漏执行。

每 4096 次调用读取一次计时器。分配量使用当前平台线程的 JDK `ThreadMXBean` 计数；被 JIT 消除的数组不计入分配量。该值不包含路由准备、请求构造、队列、Future、线程调度或业务自身分配，不能理解为完整消息处理零分配。

## 完整 Route 测量

`dispatch` 直接通过 Route 调用业务；`command` 还包含协议查找、路由身份解析、CommandContext 和当前预绑定方法调用。两者均使用当前 Runtime，不是新旧调度器对比。

默认 64 个 Route、4 个生产线程，每个生产线程最多保留 64 个未完成请求，每轮 1,000,000 条消息。每个 fork 预热 3 轮、测量 5 轮；等待全部已接受任务完成并校验业务结果。

吞吐计算完整批次至任务完成的耗时。延迟从请求构造/提交前计时，到 Handler 完成结果记录为止，包含准入、排队及此前的业务执行，不包括末尾 Future 完成通知。每轮计算 P50/P99，再取每个 fork 的轮次中位数，最后取多个 fork 的中位数；不是合并直方图后的百分位。分配量仅用于单独调用测量，不对虚拟线程上的完整执行做线程归属统计。

## 结果适用范围

这是有界闭环负载，不是固定到达速率的开放流量测试。过载尾延迟可能不同，简单业务及本地机器结果不能作为实际游戏服务器的容量或 SLO。自定义微基准也不是 JMH 套件，可能受 JVM 编译、共享机器负载与 CPU 调度影响。记录各 fork 的范围，但不把它当作置信区间。

同时观察耗时和分配量，并区分方法调用与完整消息处理。结果用于判断是否值得继续研究代码生成；最终容量还需用真实业务与接近生产的负载验证。
