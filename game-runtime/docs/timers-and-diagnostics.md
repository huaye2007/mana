# Timer outcomes, clocks and live diagnostics / 定时结果、时钟与线上诊断

## Timer 的投递和业务结果

```java
TimerTask timer = runtime.schedule(Player.class, playerId, Duration.ofSeconds(5), () -> refresh());
RouteTask finished = timer.completion();
RouteTask notified = finished.onComplete(new Route(Player.class, playerId), failure -> {
    if (failure == null) onRefreshDone();
    else onRefreshFailed(failure);
});
```

Timer 等待期间不占用 Route；到期认领后进入 DISPATCHING，不能再取消。成功准入后为 DISPATCHED；投递拒绝后为 REJECTED，不自动重试。DISPATCHED 不是业务成功标志：completion 在业务完成后才结束，包含投递错误或业务错误。其 onComplete 与普通 RouteTask 一样按目标 Route 入队，也可能因容量或关闭失败。

cancel 成功后变为 CANCELLED，completion 以 CancellationException 完成，completion.isCancelled() 为 true。关闭会取消尚在等待的 Timer 并结束其完成句柄。已经准入的任务不支持取消，仍遵守原有 FIFO 和关闭契约。Timer 注册本身被拒绝时 schedule/scheduleAt 直接抛异常，没有返回 Timer 句柄。

内部 Cron 每次触发独立投递，其错误仍通过统一异常处理器报告，不暴露周期性 completion。

## 两种时间语义

- schedule(type, key, Duration, action)：经过时长，使用 GameClock.nanoTime()，不受日历调时影响。
- scheduleAt(type, key, Instant, action)：日历时刻，使用 GameClock.now()，会受到调时影响。
- Cron：继续使用日历时刻与时区。

默认 nanoTime 使用 System.nanoTime。自定义 GameClock 应提供遵守单调时间契约的 nanoTime，不能将它实现成可回拨的日历时间。

MutableGameClock.setTime 只调整日历。advance(正 Duration) 同时推进日历与经过时间；为兼容已有测试，advance(负 Duration) 仅回拨日历。advanceElapsed(非负 Duration) 只推进经过时间。两种测试时钟在不主动推进时都保持不动。

两个定时集合共享 maxTimers 和 maxTimersPerPoll。在各自时钟下按截止时刻排序；同一轮同时到期时，优先提取逾期较久的队首，相等时按注册顺序。跨时钟调时后不存在统一的绝对先后保证。只有 Timer/Cron 注册存在时才启动轮询，不增加执行域或实体的调度定时器。

相对 Timer 的 deadline() 是注册时估算的日历时间，仅供展示；实际到期判断使用单调时间。isRelative() 可区分它与日历 Timer。

## Callback 与关闭

容量不足等可重试的 RejectedExecutionException：恢复 isSignalled=false，completion 保持等待，适配层可显式重试。RuntimeClosedException：永久拒绝，completion 以 HandlerException 失败，保留 isSignalled=true，后续重复通知返回 false。非拒绝类后端故障同样终态失败。错误观察器运行之前，完成句柄已经标记失败。

Runtime 不跟踪远端 RPC 的完整生命周期，也不保留拒绝的响应。没有发生任何通知的 Callback 仍然等待，适配层必须最终通知成功、超时或传输失败。

进程优雅关闭应由应用协调，顺序是：

1. 停止接收新的外部业务请求。
2. 保持 Runtime 和响应接收通道可用；等在途业务及其回流任务完成。跟踪的是业务链最终完成，不能只等网络响应到达。等待需要有应用自己的截止时间。
3. 对仍未完成的远端操作，在 Runtime 关闭前尽可能通知超时/传输失败，并处理通知准入失败。
4. 最后调用 runtime.close(timeout)，检查 ShutdownReport，然后关闭其余基础设施。

Runtime.close 仍然只等待已经准入的任务；它不会自动扩展为无限期等待远端业务。已注册但尚未投递的 onComplete 仍可能在关闭时失败。

## 按需查询与有界慢任务记录

```java
GameRuntime runtime = GameRuntime.builder()
    .slowTaskDiagnostics(Duration.ofMillis(20), 256)
    .build();

Optional<RouteDiagnostics> player = runtime.routeDiagnostics(new Route(Player.class, playerId));
List<RouteDiagnostics> delayed = runtime.routeDiagnostics(20);
List<SlowTask> completedSlowTasks = runtime.recentSlowTasks();
List<SlowTask> playerDomainSlowTasks = runtime.recentSlowTasks(Player.class);
```

单 Route 查询只访问对应分片；空结果表示当前没有该实体队列。批量查询扫描当前活跃 Route，返回最多 limit 条，按运行时长与最旧排队时长中的较大者降序排列，队列长度作为第二排序条件。limit 范围为 1..10000；建议由运维入口按需调用，避免在每条消息上扫描。

RouteDiagnostics 包含执行域、Route、当前 source、工作线程、运行时长、排队数量、最旧排队 source 和等待时长。运行时长与等待时长是单调时间；队列间的快照不是全局原子的。空闲 Route 被回收后不保留诊断状态。

慢任务记录默认关闭。启用后，仅为超过执行阈值的已结束任务记录样本。maxSamples 现在按执行域计算，容量满时淘汰该域最早记录；0 禁用，单域最大容量 100000，总存储上限为域数量乘以容量。recentSlowTasks() 在查询线程合并最新的最多 maxSamples 条样本；recentSlowTasks(RouteType.class) 查询该类型所在域，不受其他域高流量淘汰影响。记录包含 source、Route、线程、排队/执行时长和失败标记，不保留请求、Session 或异常对象。执行时长包括参数解析和异常观察器，与 RuntimeMetrics 一致。返回不可修改的近似快照，按记录时间排列；并发写入期间可能暂时缺少尚未发布的样本。

诊断不增加后台线程或周期扫描，不自动打印日志或执行用户观察器。每个执行域使用独立的原子环形样本区，读写不竞争 Runtime 全局锁，读取也不锁住业务写入。Route 分片锁内只复制队列快照的标量数据，时长转换和排序放在锁外。始终未返回的方法通过 live RouteDiagnostics 定位，不会提前出现在 completedSlowTasks 中。职责和生命周期约束见[架构说明](architecture.md)。
