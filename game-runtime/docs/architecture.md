# game-runtime 架构与职责

Java 包的目录职责与迁移说明见[包结构](../../docs/package-layout.md#game-runtime)。

本说明以当前代码和公开 API 为准。最初的 OGBS v0.1 草案作为历史设计输入保留，不覆盖后续已经明确的执行域、路由类型和异步完成语义。

## 三个独立概念

- Route = 游戏定义的 RouteType + long key，是一个 GameRuntime 内的实体串行边界。
- ExecutionDomain 是资源与任务额度的配置。同一个配置实例可绑定多个 RouteType，共享一个资源域。
- RouteDispatcher 决定可执行实体批次如何运行。内置 BALANCED 可在批次间换线程；KEY_AFFINITY 固定到线程分区；CUSTOM 可接入 Disruptor。

同一个 Route 在同一 Runtime 内的业务方法互斥执行，普通入队任务按准入顺序 FIFO。资源域独立不等于进程 CPU、GC 或内存完全隔离。批次只能在业务方法返回后轮转，不抢占正在运行的 Handler。

## 实现职责

| 组件 | 负责 | 边界 |
| --- | --- | --- |
| GameRuntime / Builder | 对外入口、配置验证、资源装配和关闭协调 | 不实现实体队列或线程调度算法 |
| HandlerMethodScanner / HandlerBinder | 初始化时扫描、校验并生成不可变绑定计划 | 不在消息热路径扫描方法，不创建业务线程 |
| HandlerInvokers | 预绑定方法调用与参数槽适配 | 不负责路由准入、资源选择或协议响应 |
| RouteRuntime | RouteType 到资源域的映射、异常报告、跨域查询聚合和生命周期协调 | 不保存跨域共享的慢任务写入队列 |
| RouteDomain | 实体队列、准入额度、串行所有权、有限批次执行、已准入任务完成 | 是实体 FIFO 的唯一维护者；不实现后端线程策略 |
| DomainScheduler / RouteDispatcher | 异步、非阻塞地调度实体批次，可靠接续已准入实体 | 不另建一套业务 FIFO；不内联执行提交批次 |
| RouteTask / CompletionNotifications | 完成状态、等待保护和内部通知的迭代投递 | 完成传播不等于运行业务监听器；监听器仍须准入目标 Route |
| RuntimeCallback | 将外部成功/失败通知转换成一次 Route 回流 | 不拥有 RPC 超时、传输重试或远端操作生命周期 |
| GameScheduler / TimerTask | 两类时间源的到期管理、取消、投递与结果句柄 | 定时器是可选业务触发，不驱动普通实体调度或战斗 Tick |
| DomainDiagnostics | 每个资源域独立的有界慢任务样本 | 不获取实体锁、不执行用户观察器、不写日志、不创建后台线程 |
| RouteDiagnostics / SlowTask / RuntimeMetrics | 不可变诊断结果与近似指标 | 不持有请求、连接或可变业务对象 |
| adapters/disruptor | 独立可选的批次执行后端 | 核心模块不依赖 Disruptor、RPC 或 Netty |

这些组件保留在一个 JDK-only 核心模块内。分离的是职责和资源所有权；当前没有必要为每个内部帮助类建立新的 Maven 模块或可插拔接口。

## 关键执行路径

Command：不可变协议绑定查询 → 解析路由身份 → RouteDomain 准入 → 实体排队 → 后端执行批次 → 在 Route 内解析普通参数并调用 void Handler → 释放任务额度并完成 RouteTask。

路由源不依赖 Handler 的参数位置，也不必出现在方法签名中。它在准入前执行，必须线程安全、非阻塞且只读身份；受实体保护的业务状态必须在 Route 内解析或访问。并发调用进入 command 的时间不能代替实际准入顺序。

Event 的同 Route 同步分发是明确的例外：它是当前任务中的嵌套调用，不额外排队。跨 Route Subscriber 入队，发布方不等待其完成。需要原子提交多个实体或可靠持久事件时，由业务/上层组件负责。

## 完成传播与失败

RuntimeCallback.onFail(Throwable) 保留原始异常。abort(Throwable) 可结束未通知或被容量拒绝后放弃的回调，使 completion 失败且不执行成功/失败业务回调；已接受的通知不能被取消。

命令失败的 HandlerException 保留原 invocation 和 RESOLUTION、ADMISSION、EXECUTION 阶段。解析失败不伪造 Route；准入失败保留已解析的 Context。游戏服可从同一异常入口完成映射和回复，提交 catch 不应再次回复。

响应编解码和请求响应关系由游戏服消息绑定维护。Runtime 只需要执行的命令登记；ProtocolRegistry 的旧响应关系 API 保留为已弃用兼容入口。

RouteTask 通过完成锁保护单次终态和观察器列表，用 CountDownLatch 唤醒等待线程。终态发布后在锁外通知观察器，观察器异常不会替换结果或阻断其他观察器。onComplete 捕获目标 Route 和 Metadata，再提交业务监听器；observeCompletion 仅供基础设施清理和失败通知，不保证路由，也不提供业务续接链。显式 callback(Route, Metadata, ...) 和 dispatch(type, key, Metadata, action) 原样使用传入的 Metadata，原有重载继续按当前 Context 传播。

内部完成投递由 CompletionNotifications 在当前完成线程上按队列迭代处理。投递拒绝会让通知任务失败，再迭代处理后继通知；不会因完成链长度增加 Java 调用栈深度。其线程局部队列在传播结束后移除，不引入新执行池，也不会绕过业务准入额度。外部异常观察器仍必须及时返回。

Callback 的暂时容量拒绝可由适配层重试；永久关闭或非拒绝类后端错误进入终态。没有收到任何信号的远端操作仍由适配层管理，不能把它等同于已准入的 Runtime 任务。

## 生命周期与资源所有权

资源工厂必须为每个 Runtime 返回独立后端。工厂自身在返回前失败时，负责清理自己尚未交出的资源；一旦成功交付，Runtime 负责关闭。

RouteDomain 先构造队列管理与诊断数据，再获取后端。GameScheduler 在创建轮询执行器前读取外部时钟。RouteRuntime 在部分执行域创建失败时逆序关闭先前成功创建的域；GameRuntime 在 Scheduler 构造或后续初始化失败时，逆序清理已经创建的 Scheduler 和执行域。原始错误保持为主异常，清理错误作为 suppressed 信息保留。

正常 close 继续使用共享截止时间等待已准入队列排空。超时不释放正在执行实体的所有权，不强行中断业务。完整远端业务链的退出由应用协调，详见[关闭流程](timers-and-diagnostics.md)。

## 诊断与业务执行的隔离

慢任务使用每个执行域自己的原子环形样本区。写入和读取不共用 Runtime 全局锁，读取不会锁住业务写入。延迟到达的旧写入不能覆盖该槽位的新一轮记录。

slowTaskDiagnostics(threshold, maxSamples) 中 maxSamples 现在是每个执行域的容量。recentSlowTasks() 在调用方线程合并最新的最多 maxSamples 条记录；recentSlowTasks(RouteType.class) 查询该类型所在域，其他域的高流量不会淘汰这些样本。总存储上界是执行域数量乘以容量，默认容量为零。

Route 的在线快照仍需短暂获取实体分片锁，以一致地提取当前执行项和队首信息。锁内只复制标量数据；Duration 转换、聚合及排序在锁外完成。批量查询仍是按需扫描，不能宣称无成本或硬实时；limit 约束返回条数，不是扫描条数。

## 验证边界

回归测试覆盖 Route FIFO、执行域隔离、可靠批次接续、路由源与状态解析边界、等待保护、上下文传播、Timer 结果、时钟分离及关闭错误隔离。

本次新增 20,000 层连续拒绝完成链、初始化各阶段失败和逆序清理、执行域间诊断保留、诊断并发读写等测试。性能结论仍需针对实际业务负载测量；这些正确性测试不提供生产吞吐或实时性承诺。
