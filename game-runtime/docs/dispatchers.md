# Custom route dispatchers / 自定义路由调度后端

Runtime retains entity FIFO and exclusive route ownership. The dispatcher chooses how a ready batch reaches a worker. Switching backends does not change Handler signatures, protocol entry points or route-ID resolution.

~~~java
public interface RouteDispatcher {
    void dispatch(Route route, Runnable batch);
    void reschedule(Route route, Runnable batch);
    void shutdown();
    boolean awaitTermination(Duration timeout) throws InterruptedException;
}

var rooms = ExecutionDomain.custom("rooms",
    () -> new DisruptorRouteDispatcher("rooms", 4, 2048))
    .tasksPerTurn(32)
    .limits(new DomainLimits(1000, 64, 100, 8))
    .routeShards(64)
    .build();

builder.executionDomain(GameRoutes.Room.class, rooms);
~~~

The optional [Disruptor adapter](../adapters/disruptor/README.md) is a separate artifact. Core game-runtime remains JDK-only. A project can also implement this interface with its own event loop, executor or queues. These choices require workload measurements; there is no claim that one backend is universally faster.

## Admission and continuation / 首次准入与可靠续批

- `dispatch` activates an idle entity. It may reject before acceptance, normally with RejectedExecutionException. Runtime rolls back that initial admission and throws to the submitter.
- `reschedule` is mandatory and continues an already admitted entity. The current batch calls it before returning. Capacity saturation must never reject this continuation: retain an admission token or use a continuation queue whose size is bounded by admitted entities.
- Both methods must be thread-safe, asynchronous and nonblocking. They must not wait for queue space or run business inline. Accept a batch exactly once, never throw after acceptance and never silently discard it.
- Runtime guards custom backends against inline execution and duplicate delivery. Same-Route business remains serial even when batches use different workers. A fixed-key backend can use route.key() for worker selection; route.type() remains part of entity identity.
- A true backend fault during continuation, including violating the reschedule contract, fails the remaining tasks on that route and releases their admission capacity. Normal ingress saturation must not cause this path. Runtime does not replay business actions or move failed batches onto the caller.
- A backend that silently loses accepted work cannot be repaired automatically; shutdown diagnostics expose the pending route.

`dispatch` 的拒绝属于新业务准入；`reschedule` 负责已接受实体继续执行，必须为它保留调度能力。不能因为 RingBuffer 或其他入口队列满了，就让已排队的玩家消息整队失败。继续调度不依赖 Timer，也不通过阻塞重试完成。真正的后端故障仍会使受影响的剩余任务明确失败。

The optional Disruptor implementation hashes keys to fixed platform consumers and keeps reliable continuations separate from first-activation ingress. See the adapter README for its queue contract and capacity behavior.

## Ownership and lifecycle / 所有权与生命周期

`shutdown` must be nonblocking and idempotent, leaving running batches able to return. `awaitTermination` must respect its timeout. Runtime calls shutdown only after admitted entity queues drain.

The factory creates a fresh, exclusively owned backend per Runtime. Several route types sharing one ExecutionDomain configuration share its backend within that Runtime. A factory must clean up its own partially constructed resources before throwing; Runtime cleans up earlier created domains if a later factory fails.

Custom backends own worker counts and thread choices. Built-in `threads`, `maxConcurrentRoutes` and `scheduling` options do not configure custom backends. Custom configuration reports Mode.CUSTOM and Scheduling.CUSTOM. DomainLimits, routeShards and tasksPerTurn still apply; optional dispatcher metrics return -1 if unavailable.

这层扩展决定“把实体批次交给哪个工作线程”，协议绑定和实体串行保证仍由 Runtime 负责。批次返回后，业务不能继续访问该 Route 的可变状态；异步结果应重新入队。

## Waiting and completion / 等待与完成通知

Platform-thread handlers cannot join/get any unfinished RouteTask, even in another Runtime. A virtual-thread handler cannot wait on unfinished work in its current execution domain, including another Route: a bounded domain can otherwise exhaust all running slots. Completed results can be read, and callers outside handlers can wait.

This guard covers RouteTask only. Arbitrary Future.get, blocking I/O or cross-domain dependency cycles are not automatically detected. Use route completion notifications:

~~~java
// Inside a Player handler in the same Runtime.
RouteTask guildTask = runtime.dispatch(GameRoutes.Guild.class, guildId, () -> updateGuild());
RouteTask notification = guildTask.onComplete(failure -> {
    if (failure == null) updatePlayer();
    else handleGuildFailure(failure);
});

// Outside a handler, give the notification destination explicitly.
RouteTask externalNotification = guildTask.onComplete(
    new Route(GameRoutes.Player.class, playerId), failure -> recordResult(failure));
~~~

The implicit overload captures the caller's current Route in the same Runtime, its metadata. The explicit Route overload is also usable externally. The listener receives null on success or the original task's failure and always queues on the destination; it never executes inline, even for an already completed source task. The returned RouteTask describes listener completion or notification failure.

Notifications use callback-reserved admission. Registering a listener does not reserve a slot. Rejected notification dispatch is reported and fails the returned notification task without automatic retry.

`RuntimeCallback.isSignalled()` reports signal claim/acceptance, including an in-progress submission. `completion()` is the stable RouteTask for callback business completion. The deprecated `isCompleted()` keeps its old signal-state meaning. Retryable signal rejection resets isSignalled and leaves completion pending so the transport can retry. Permanent RuntimeClosedException or a non-rejection backend fault terminally fails completion and prevents further signals; Runtime does not retain or retry rejected responses.

## Bounded shutdown / 限时关闭

~~~java
ShutdownReport report = runtime.close(Duration.ofSeconds(5));
if (!report.terminated()) {
    report.pendingRoutes().forEach(System.err::println);
    System.err.println(report.remainingDomains());
    report.backendFailures().forEach(failure ->
        System.err.println(failure.domain() + " " + failure.operation() + ": " + failure.cause()));
}
~~~

One monotonic deadline covers all execution domains. Admission stops first; accepted batches keep draining through reschedule. The report includes domain, Route, source/method, worker thread, running duration and queued count, with up to 100 route details plus an omitted count. Snapshots are approximate; a worker may still be cleaning up even when no entity queue remains.

A backend throwing from shutdown or awaitTermination does not prevent other domains from being cleaned up. `backendFailures()` contains the domain, operation and cause. Errors remain in subsequent reports, and Runtime attempts each backend's shutdown only once; `terminated()` stays false when captured backend failures exist.

Timeout does not interrupt business methods or release entity ownership. Accepted work may finish in the background, then close its backend normally. Calling close(Duration) again can wait for that normal draining to finish; it does not repair a failed backend or retry its shutdown.

关闭开始后，尚未投递的完成监听器和迟到的 Callback 通知属于新提交，会被拒绝。即使 listener 已注册，原任务在关闭后才完成时，通知任务仍可能失败。从未收到通知的 RuntimeCallback 不计入已准入任务，仍等待适配层最终通知；关闭后实际尝试的通知会以 RuntimeClosedException 终态失败。进程应先停止外部请求、等待在途业务及回流完成，再关闭 Runtime；详见[关闭协调与诊断](timers-and-diagnostics.md)。

AutoCloseable close() uses a 30-second default, configurable with builder.shutdownTimeout(Duration), and throws RuntimeShutdownException carrying the report if shutdown is incomplete or has backend failures. Call shutdown outside a handler. These are cooperative deadlines: custom backend methods and exception observers must honor their nonblocking contracts.

## Business timers are optional / 定时器属于可选业务触发

Entity dispatch and batch rotation do not use a clock or Timer. Timer/Cron are separate business features such as delayed refreshes or scheduled activities. Automatic polling starts only while such registrations exist. A Runtime handling ordinary commands does not start timer polling; automaticScheduling(false) supports manual clock-driven tests.

`DomainLimits(maxTasks, maxTasksPerRoute, callbackReserve, callbackReservePerRoute)` configures only task admission. Queue-management shards use a separate routeShards option. `GameRuntime.Builder.timerOptions(new TimerOptions(maxTimers, maxTimersPerPoll))` configures Runtime-wide business timers. This does not introduce per-domain timers or a battle Tick engine.

## Entity lifecycle / 实体生命周期

One application lifecycle issue is a player starting a remote query in session version 1, reconnecting into version 2, then receiving the old session-specific result. Same-route FIFO does not identify an obsolete response. The game can compare a captured session/entity version before applying it. Durable rewards may instead need idempotency and must not simply be discarded because a session changed; Runtime adds no automatic session-generation policy.
