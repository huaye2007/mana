[English](README.en.md) | 中文

# game-runtime

游戏服务器统一运行时：把网络消息、事件、定时器、异步回调四类入口统一收敛成"任务"，按路由键派发到固定 worker 串行执行，业务代码无锁。

## 设计原则

- **轻依赖**：只依赖 game-common、slf4j-api 和 Disruptor；不依赖 game-network / game-rpc，三者互不感知，桥接代码放宿主进程。
- **无装配门面**：没有 `GameRuntime.builder()` 这类统一入口。各组件是松散单例（`getInstance()`），宿主启动时自行注册执行器组、controller、事件监听器。
- **同 routerKey 串行**：一个任务带 `(group, routerKey)`，routerKey 哈希到组内固定 worker。同组同 routerKey 的事件可依据当前绑定的任务 context 内联执行。
- **过载明确拒绝**：worker 队列有界；兼容入口 `execute(...)` 保持 fire-and-forget，需处理拒绝时调用 `tryExecute(...)`，区分过载、停机和未注册组。不缓冲、不重试、不做死信。

## 任务模型

| 任务类型 | 入口 | 上下文传递 |
|---|---|---|
| COMMAND | 宿主桥接：查 `CommandRegistry` → 构造 `GameCommandTaskRunnable` → `ExecutorGroupRegistry.tryExecute(...)` | **隐式**：绑定到当前线程，handler 用 `current()` 获取 |
| EVENT | `EventBus.publishEvent(...)` | **隐式**：绑定到当前线程 |
| TIMER | `TimingWheel.schedule(delayMs, ...)`（延迟一次性）/ `CronTask.start()`（cron） | **隐式** |
| CALLBACK | 宿主构造 `GameCallbackTaskRunnable` 投递（如 rpc 回调桥接） | **隐式** |

隐式绑定规则：虚拟线程用 `ScopedValue`，平台线程用 `ThreadLocal`，业务代码统一通过 `GameTaskContextHolder.current()` 惰性获取，未绑定返回 null。COMMAND 也隐式绑定：handler 方法签名里**不含** context（首参是 busId 或 session、次参是消息，见下），需要时通过 `current()` 取（可转型为 `GameCommandTaskContext`）；这也使 handler 内部发布事件时 EventBus 的"同组同 routerKey 内联"对 COMMAND 生效。

## 执行器组

标准四组见 `ExecutorGroups`，注解不指定 group 时默认落 PLAYER：

| 组 | 用途 | 线程类型 |
|---|---|---|
| LOGIN (1) | 登陆洪峰隔离 | 虚拟线程 |
| PLAYER (2) | 玩家自身业务（默认组），按玩家 id 路由 | 虚拟线程 |
| SCENE (3) | 场景/房间同步，不能阻塞 | 平台线程 |
| COMMON (4) | 排行榜、公会等全局业务 | 虚拟线程 |

这四组之外的业务组由注解显式指定 group。宿主启动时注册：

```java
ExecutorGroupRegistry registry = ExecutorGroupRegistry.getInstance();
registry.register(DefaultExecutorGroup.virtualThreads(ExecutorGroups.LOGIN,  "login",  8,  10_000));
registry.register(DefaultExecutorGroup.virtualThreads(ExecutorGroups.PLAYER, "player", 64, 10_000));
registry.register(DefaultExecutorGroup.platformThreads(ExecutorGroups.SCENE, "scene",  16, 10_000));
registry.register(DefaultExecutorGroup.virtualThreads(ExecutorGroups.COMMON, "common", 16, 10_000));
```

SCENE 这类延迟敏感组可换用 Disruptor 实现（RingBuffer + 平台线程消费者，路由/满丢语义与 `DefaultExecutorGroup` 一致，bufferSize 必须是 2 的幂）：

```java
// blockingWait：空闲挂起省 CPU；yieldingWait：空闲自旋让出，延迟更低但每个空闲 worker 吃满一个核
registry.register(DisruptorExecutorGroup.yieldingWait(ExecutorGroups.SCENE, "scene", 16, 8192));
```

## 命令（COMMAND）

```java
@GameController(group = ExecutorGroups.PLAYER)
public class BagController {

    // handler 必须是 2 个参数：第一个参数由 runtime 按其类型分派——
    //   · 类型为 long/Long 时注入任务 busId；
    //   · 其它类型时注入宿主构造任务时传入的 session 对象；
    // 第二个参数是消息体。任务上下文不在参数里，需要时用 GameTaskContextHolder.current()。
    // routerKeyMethod 从「消息体」提取路由键；不配则用宿主传入的 defaultRouterKey。
    @GameMethod(value = 1001, routerKeyMethod = "playerId")
    public void useItem(Long roleId, UseItemMsg msg) {
        GameCommandTaskContext ctx = (GameCommandTaskContext) GameTaskContextHolder.current();
        // ...
    }

    // 或者第一个参数收宿主的 session 对象：
    // public void useItem(PlayerSession session, UseItemMsg msg) { ... }
}
```

runtime 不提供统一 dispatcher 门面（符合「无装配门面 / 桥接放宿主」）。启动期注册 controller，传输层收到消息后由**宿主自行桥接派发**：

```java
// 启动期：
CommandRegistry.getInstance().register(new BagController());

// 每条消息：查元数据 → 提取路由键 → 构造任务 → 投递执行器组
CommandMeta meta = CommandRegistry.getInstance().getCommandMeta(command);
if (meta == null) { /* 未注册命令，丢弃并记 error */ return; }
long routerKey = meta.extractRouterKey(message, defaultRouterKey);
GameCommandTaskRunnable task = new GameCommandTaskRunnable(
        meta, routerKey, busType, busId, seq, metadatas, message, session);
TaskSubmissionResult result = ExecutorGroupRegistry.getInstance().tryExecute(task);
if (!result.isAccepted()) { /* 回 SERVER_BUSY / INTERNAL_ERROR */ }
```

消息原样透传，是否/如何反序列化由 handler 自行决定。配置了 `routerKeyMethod` 后提取失败会拒绝请求，不再悄悄回退到 defaultRouterKey。
（宿主桥接的完整示例见 game-dev 的 `GameRouterManager`。）

## 事件（EVENT）

```java
@EventHandler(group = ExecutorGroups.PLAYER)   // 类级默认组
public class LevelUpListeners {

    @EventMethod                                // order 可选，默认 0
    public void sendMail(LevelUpEvent e) { ... }

    @EventMethod(group = ExecutorGroups.COMMON) // 方法级覆盖
    public void updateRank(LevelUpEvent e) { ... }
}

EventBus.getInstance().register(new LevelUpListeners());
EventBus.getInstance().publishEvent(new LevelUpEvent(playerId)); // routerKey 由事件自带
```

- 事件类型**精确匹配**：注册在父类/接口上的监听不会被子类事件触发（刻意为之）。
- `@EventMethod.order` 是可选字段，默认值为 `0`；值越小越先调用或提交，跨组不保证完成顺序。
- 事件对象必须**深度不可变**；不同 executor group 可能并发观察同一实例。事件 context 的 metadata 在派发边界深拷贝，对外只返回防御性副本。
- 监听者与发布方**同 group、同 routerKey 且发布方 context 当前已绑定**时内联同步执行；否则按事件 routerKey 投递到监听者所在组。
- 需要感知扇出拒绝时调用 `tryPublishEvent(...)`，返回内联、已接收和被拒绝的监听器数量。

## 定时（TIMER）

`TimingWheel` 与 cron 统一通过公开的 `GameTime` 获取当前时间。默认使用系统时钟；外部可以注入标准
`java.time.Clock` 调整游戏时间，而不修改操作系统时间：

```java
GameTime.setClock(Clock.offset(Clock.systemUTC(), Duration.ofDays(1))); // 游戏时间前进一天
long now = GameTime.currentTimeMillis();

// 测试结束或需要恢复真实时间时
GameTime.resetClock();
```

偏移时钟会继续自然流逝；`Clock.fixed(...)` 会冻结定时器时间，适合可控测试。注入的自定义 `Clock`
必须支持并发访问。执行器停机超时和任务耗时监控不使用游戏时间，修改 `GameTime` 不会破坏框架超时。

统一基于哈希时间轮（默认共享实例 tick 100ms × 512 槽），**禁止业务直接用 `ScheduledExecutorService`**。时间轮按 `GameTime` 绝对截止点调度，正常负载下绝不早于 deadline，最多晚一个 tick。取消会立即把 Timeout 推进到终态并更新 pendingCount；停机取消剩余 Timeout。`CronTask.start()` 只能调用一次，`cancel()` 会取消底层 Timeout。

```java
// 延迟一次性：时间轮只算时间点，到点把任务派发到执行器组（务必在轮回调里 execute，
// 不要直接在计时线程上跑业务）
GameTimerTaskRunnable body = new GameTimerTaskRunnable(group, routerKey, busType, busId, null, runnable);
TimingWheel.getInstance().schedule(5_000,
        () -> ExecutorGroupRegistry.getInstance().execute(body));

// cron：调用 start() 启动，触发后自动重排，直到 cancel()
CronTask cron = new CronTask("0 0 5 * * *", body);                       // 秒 分 时 日 月 周
new CronTask("0 0 5 * * *", ZoneId.of("Asia/Shanghai"), body).start();   // 跨时区部署显式指定
cron.start();
```

固定频率/周期等其它调度形态没有内置门面，业务在 `TimingWheel` + `CronTask` 之上自行封装。cron 的"日"与"周"采用标准 OR 语义（两者都被限制时任一匹配即触发）。

## 异常处理

任务内未捕获异常统一路由到 `GameTaskExceptionHandlers`（默认打 error 日志），宿主启动期可替换：

```java
GameTaskExceptionHandlers.setHandler((ctx, cause) -> { 日志 + 监控告警 });
```

业务 `Exception` 不会杀死 worker，也不会中断同事件的其他监听者；`Error` 作为 JVM/链接级致命错误继续向上传播。

## 可观测性

内置轻量监控，模式与异常处理器一致——默认实现开箱可用，宿主启动期可整体替换接入指标系统：

```java
// 默认行为：执行耗时 ≥ 阈值（默认 1000ms）打 warn；队列满丢弃打 error
GameTaskMonitors.setSlowTaskThresholdMs(500);

// 接入自有监控（回调在 worker 线程上同步执行，必须轻量非阻塞）
GameTaskMonitors.setMonitor(new GameTaskMonitor() {
    public void onTaskComplete(GameTaskContext ctx, long queueDelayMs, long execMs) { 上报耗时分布 }
    public void onTaskDropped(GameTaskContext ctx) { 丢弃告警 }
    public void onTaskRejected(GameTaskContext ctx, TaskSubmissionResult reason) { 按拒绝原因告警 }
});
```

采样型指标（宿主定时拉取）：

- `DefaultExecutorGroup.queuedTasks()` — 组内所有 worker 队列等待任务总数（`DisruptorExecutorGroup` 同名方法统计 ring 占用槽位）
- `DefaultExecutorGroup.droppedCount()` — 累计丢弃任务数（`DisruptorExecutorGroup` 同名）
- `TimingWheel.getInstance().pendingCount()` — 时间轮上等待触发的定时任务数

## 使用契约（重要）

1. **注册只能在启动期完成并冻结**：注册完成后调用 `CommandRegistry.freeze()` / `EventBus.freeze()`；EventBus 首次发布也会自动冻结。冻结后继续注册会 fail-fast。
2. **handler 方法和类必须 public**：注册期会生成 LambdaMetafactory 调用器替代反射（热路径零反射开销），非 public 在注册时直接抛异常 fail-fast。
3. **停机顺序**：先 `TimingWheel.getInstance().shutdown()` 停止产生新任务，再 `ExecutorGroupRegistry.getInstance().shutdownAll(timeoutMs)`。timeoutMs 是所有组共享的总预算，耗尽后剩余任务强制中断丢弃。
4. **SCENE 组的任务不允许阻塞**（IO、锁等待）；会阻塞的业务放虚拟线程组。
5. **routerKey 不能为 0**：0 会让所有"无路由键"任务挤到组内 worker-0 形成热点，因此构造任务上下文时 routerKey 为 0 直接抛异常。全局/无玩家归属的业务也必须给出可散列的键（如连接 id、公会 id）。
6. **事件必须深度不可变**：发布后不得修改事件及其引用的数组、集合或对象；跨组监听器没有全局完成顺序。
