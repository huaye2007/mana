# MethodHandle 与动态字节码对照

这套基准只增加 benchmark 源码，不修改生产调用器或 Runtime JAR。实测数据见[对比报告](BYTECODE-RESULTS.md)。

## 运行

JDK 25、PowerShell 7，在仓库根目录执行：

~~~powershell
mvn -pl game-runtime test
pwsh -NoProfile -File game-runtime/benchmarks/compare-bytecode.ps1
~~~

默认每个场景使用 3 个独立 JVM，每个 JVM 预热 3 轮、测量 5 轮。
微基准每轮 500ms；完整 Runtime 每轮 500,000 条消息。
两种实现及场景的运行顺序按 fork 轮换；所有 JVM 串行运行。

~~~powershell
# 快速功能验证；此配置不用于性能结论。
pwsh -NoProfile -File game-runtime/benchmarks/compare-bytecode.ps1 `
    -Forks 1 -Warmups 1 -Samples 1 -Millis 100 -Messages 10000 -RunName smoke

# 单独重测一层。
pwsh -NoProfile -File game-runtime/benchmarks/compare-bytecode.ps1 -Mode micro
pwsh -NoProfile -File game-runtime/benchmarks/compare-bytecode.ps1 -Mode load
~~~

每次结果保存在 target/bytecode-comparison/<RunName>/，包括 samples.jsonl、
summary.json、用于检查的生成类及 generated-invoker.txt。默认 RunName 是时间戳；
显式重用名字会替换该次原始数据。既有历史性能结果不受影响。

## 如何保持对照一致

- bound：生产 HandlerBinder 与 HandlerInvokers，使用预绑定 MethodHandle.invokeExact。
- bytecode：复制当前 HandlerBinder 到 benchmark 的 target 目录，只把
  HandlerInvokers.command(...) 换成 BytecodeInvokers.command(...)；
  在独立 JVM 中优先装载这一份 Binder。生产源文件、生产 class 文件与 JAR 均不替换。
- 字节码生成器使用 JDK Class-File API；生成的 invoke 方法通过 invokevirtual
  直接调用具体 Handler，不在该方法内调用 MethodHandle。构造生成类时使用的
  MethodHandle 不属于消息热路径。[JDK ClassFile 文档](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/classfile/ClassFile.html)
- 两种调用器复用完全相同的 ArgumentSlot、参数解析函数和预先解析的路由值。
  重复语义参数只解析一次。生成器把解析结果放入局部变量，不创建调用参数数组。
- 每个 JVM 启动时验证实际装载的调用器类型，避免误测成同一种实现。

行为检查在单独 JVM 中运行，覆盖重复参数身份、隐藏路由源、异常传播与八种基本类型拆箱。
所有测量轮均消费业务结果；完整 Runtime 每轮校验精确校验和及全部消息的延迟记录。

## 场景

每组都注册同样的 32 种请求及 32 个不同的 Handler 方法；区分的是实际活跃协议数：

| 参数数 | 活跃协议数 | 目的 |
| --- | --- | --- |
| 2 | 1 | 单一热方法 |
| 2 | 32 | 多方法混合调用 |
| 4 | 1 | 单一热方法与当前数组路径 |
| 4 | 32 | 多方法混合及数组分配 |

四参数形状是 Id、Request、Extra、Extra，重复 Extra 用于验证解析结果复用。
业务方法做带协议标记的累加，两种实现执行完全相同的业务。测试涵盖可访问的实例 void 方法；
这份生成器是实验原型，没有替换生产系统的访问权限、模块边界或类生命周期设计。

微基准直接调用从真实 CommandBinding 取出的 invoker；使用预分配的 1024 个变化输入，
不计路由准备、队列、Future、线程调度。时间每 4096 次调用采样一次。
分配量来自当前线程的 ThreadMXBean，JIT 已消除的分配不会被计入。

完整 Runtime 都调用 runtime.command，包含协议查找、路由与参数解析、Context、
准入、实体队列、调度、调用器和 RouteTask 完成。固定使用：

- 64 个 Route，4 个生产线程，每个生产线程最多 64 个未完成请求。
- 独立的 4 平台工作线程，BALANCED 调度，tasksPerTurn=32。
- 确定性混合 Route key，使协议号和 Route 分布不直接一一绑定。
- 256MiB 固定堆，G1；等待全部已接受任务完成。

吞吐时间覆盖消息提交到全部任务完成；延迟从请求构造前开始，在业务 Handler 内记录，
包含排队，排除之后的 Future 完成通知。GC 次数只作为辅助原始观测，不用于直接归因。
生成类、扫描绑定、类加载以及基准数据初始化均不计入稳态结果。

## 解读范围

summary.json 先取每个 fork 内各轮的中位数，再取 fork 中位数的中位数；
同时提供各 fork 的结果和范围。范围不是置信区间，P50/P99 也不是合并直方图。

这是使用当前生产代码的自定义诊断基准，不是 JMH，也不是生产容量测试。
单协议和多协议结果要分别看；纯调用加速比例不能直接套用到完整 Runtime。
未测启动延迟、类元数据占用、真实游戏业务或开放到达率过载场景。
正式结果与原始样本保存在本目录的 results 中，结论应以具体运行配置为准。
