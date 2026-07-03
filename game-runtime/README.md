# game-runtime

游戏服务器统一运行时：把网络消息、事件、定时器、异步回调四类入口统一收敛成"任务"，按路由键派发到固定 worker 串行执行，业务代码无锁。

## 设计原则

- **零依赖**：只依赖 slf4j-api。不依赖 game-network / game-rpc，三者互不感知，桥接代码放宿主进程。
- **无装配门面**：没有 `GameRuntime.builder()` 这类统一入口。各组件是松散单例（`getInstance()`），宿主启动时自行注册执行器组、controller、事件监听器。
- **同 routerKey 串行**：一个任务带 `(group, routerKey)`，routerKey 哈希到组内固定 worker。同一玩家（或同一房间、同一公会）的任务永远在同一线程上顺序执行，业务不需要加锁。
- **过载即丢弃**：worker 队列有界，队列满直接丢任务并记 error 日志。不缓冲、不重试、不做死信，丢失由上层协议层面自愈（客户端重发/重连拉状态）。

## 任务模型

| 任务类型 | 入口 | 上下文传递 |
|---|---|---|
| COMMAND | 宿主桥接：查 `CommandRegistry` → 构造 `GameCommandTaskRunnable` → `ExecutorGroupRegistry.execute(...)` | **隐式**：绑定到当前线程，handler 用 `current()` 获取 |
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
    //   · 类型为 Long 时注入任务 busId；
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
ExecutorGroupRegistry.getInstance().execute(task);
```

消息原样透传，是否/如何反序列化由 handler 自行决定。未注册的命令直接丢弃并记 error。
（宿主桥接的完整示例见 game-dev 的 `GameRouterManager`。）

## 事件（EVENT）

```java
@EventHandler(group = ExecutorGroups.PLAYER)   // 类级默认组
public class LevelUpListeners {

    @EventMethod(order = 1)                     // 同事件按 order 升序执行
    public void sendMail(LevelUpEvent e) { ... }

    @EventMethod(group = ExecutorGroups.COMMON) // 方法级覆盖
    public void updateRank(LevelUpEvent e) { ... }
}

EventBus.getInstance().register(new LevelUpListeners());
EventBus.getInstance().publishEvent(new LevelUpEvent(playerId)); // routerKey 由事件自带
```

- 事件类型**精确匹配**：注册在父类/接口上的监听不会被子类事件触发（刻意为之）。
- 监听者与发布方**同 group 且同 routerKey** 时内联同步执行；否则按事件 routerKey 投递到监听者所在组。

## 定时（TIMER）

统一基于哈希时间轮（默认共享实例 tick 100ms × 512 槽），**禁止业务直接用 `ScheduledExecutorService`**。时间轮只算时间点，到点后任务派发到业务执行器组执行，不占计时线程。调度完全按相对 `delayMs` 换算成 tick 数、用单调钟（`System.nanoTime()`）定速，不读墙钟时间戳：系统时钟回拨不会让定时器错乱；VM 挂起 / GC 长停顿恢复后按单调钟**追平**到期 tick（既不会永久冻结、也不会跳过任务，但停顿期内到期的定时器会在恢复后集中补触发）。

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

异常永远不会杀死 worker 线程，也不会中断同事件的其他监听者。

## 可观测性

内置零依赖监控，模式与异常处理器一致——默认实现开箱可用，宿主启动期可整体替换接入指标系统：

```java
// 默认行为：执行耗时 ≥ 阈值（默认 1000ms）打 warn；队列满丢弃打 error
GameTaskMonitors.setSlowTaskThresholdMs(500);

// 接入自有监控（回调在 worker 线程上同步执行，必须轻量非阻塞）
GameTaskMonitors.setMonitor(new GameTaskMonitor() {
    public void onTaskComplete(GameTaskContext ctx, long queueDelayMs, long execMs) { 上报耗时分布 }
    public void onTaskDropped(GameTaskContext ctx) { 丢弃告警 }
});
```

采样型指标（宿主定时拉取）：

- `DefaultExecutorGroup.queuedTasks()` — 组内所有 worker 队列等待任务总数（`DisruptorExecutorGroup` 同名方法统计 ring 占用槽位）
- `DefaultExecutorGroup.droppedCount()` — 累计丢弃任务数（`DisruptorExecutorGroup` 同名）
- `TimingWheel.getInstance().pendingCount()` — 时间轮上等待触发的定时任务数

## 使用契约（重要）

1. **注册只能在启动期单线程完成**：`CommandRegistry.register` / `EventBus.register` 开始处理任务后不允许再调用，运行期注册表只读、无锁。
2. **handler 方法和类必须 public**：注册期会生成 LambdaMetafactory 调用器替代反射（热路径零反射开销），非 public 在注册时直接抛异常 fail-fast。
3. **停机顺序**：先 `TimingWheel.getInstance().shutdown()` 停止产生新任务，再 `ExecutorGroupRegistry.getInstance().shutdownAll(timeoutMs)`。timeoutMs 是所有组共享的总预算，耗尽后剩余任务强制中断丢弃。
4. **SCENE 组的任务不允许阻塞**（IO、锁等待）；会阻塞的业务放虚拟线程组。
5. **routerKey 不能为 0**：0 会让所有"无路由键"任务挤到组内 worker-0 形成热点，因此构造任务上下文时 routerKey 为 0 直接抛异常。全局/无玩家归属的业务也必须给出可散列的键（如连接 id、公会 id）。
