[中文](README.md) | English

# game-runtime

Unified runtime for game servers: network messages, events, timers and async callbacks — the four entry types — are all converged into "tasks" that are dispatched by routing key to a fixed worker for serial execution, so business code needs no locks.

## Design Principles

- **Lightweight dependencies**: depends only on game-common, slf4j-api and Disruptor. It does not depend on game-network / game-rpc; bridging code lives in the host process.
- **No assembly facade**: there is no unified entry point like `GameRuntime.builder()`. Components are loose singletons (`getInstance()`); the host registers executor groups, controllers and event listeners itself at startup.
- **Serial per routerKey**: a task carries `(group, routerKey)` and hashes to a fixed worker. Events for the same group and routerKey may run inline based on the currently bound task context.
- **Explicit rejection on overload**: queues are bounded. The compatible `execute(...)` API remains fire-and-forget; callers that need rejection handling use `tryExecute(...)` to distinguish overload, shutdown and an unregistered group. There is no buffering, retry or dead letter.

## Task Model

| Task type | Entry | Context propagation |
|---|---|---|
| COMMAND | Host bridge: look up `CommandRegistry` → build `GameCommandTaskRunnable` → `ExecutorGroupRegistry.tryExecute(...)` | **Implicit**: bound to the current thread; handlers fetch it via `current()` |
| EVENT | `EventBus.publishEvent(...)` | **Implicit**: bound to the current thread |
| TIMER | `TimingWheel.schedule(delayMs, ...)` (one-shot delay) / `CronTask.start()` (cron) | **Implicit** |
| CALLBACK | Host builds and submits `GameCallbackTaskRunnable` (e.g. rpc callback bridging) | **Implicit** |

Implicit binding rules: virtual threads use `ScopedValue`, platform threads use `ThreadLocal`; business code uniformly fetches lazily via `GameTaskContextHolder.current()`, which returns null when unbound. COMMAND is implicitly bound too: the handler method signature does **not** include a context (first parameter is busId or session, second is the message — see below); fetch it via `current()` when needed (castable to `GameCommandTaskContext`). This also makes EventBus's "inline when same group and same routerKey" apply to COMMAND when a handler publishes events.

## Executor Groups

The four standard groups are in `ExecutorGroups`; when an annotation specifies no group, PLAYER is the default:

| Group | Purpose | Thread type |
|---|---|---|
| LOGIN (1) | Isolates login surges | Virtual threads |
| PLAYER (2) | Per-player business (default group), routed by player id | Virtual threads |
| SCENE (3) | Scene/room synchronization, must not block | Platform threads |
| COMMON (4) | Global business such as leaderboards and guilds | Virtual threads |

Business groups beyond these four are specified explicitly via the annotations. The host registers at startup:

```java
ExecutorGroupRegistry registry = ExecutorGroupRegistry.getInstance();
registry.register(DefaultExecutorGroup.virtualThreads(ExecutorGroups.LOGIN,  "login",  8,  10_000));
registry.register(DefaultExecutorGroup.virtualThreads(ExecutorGroups.PLAYER, "player", 64, 10_000));
registry.register(DefaultExecutorGroup.platformThreads(ExecutorGroups.SCENE, "scene",  16, 10_000));
registry.register(DefaultExecutorGroup.virtualThreads(ExecutorGroups.COMMON, "common", 16, 10_000));
```

Latency-sensitive groups like SCENE can switch to the Disruptor implementation (RingBuffer + platform-thread consumers, same routing and drop-when-full semantics as `DefaultExecutorGroup`; bufferSize must be a power of two):

```java
// blockingWait: parks when idle to save CPU; yieldingWait: spin-yields when idle — lower latency,
// but each idle worker burns a full core
registry.register(DisruptorExecutorGroup.yieldingWait(ExecutorGroups.SCENE, "scene", 16, 8192));
```

## Commands (COMMAND)

```java
@GameController(group = ExecutorGroups.PLAYER)
public class BagController {

    // A handler must take exactly 2 parameters: the first is dispatched by its type —
    //   · when the type is long/Long, the task busId is injected;
    //   · otherwise, the session object the host passed when building the task is injected;
    // the second parameter is the message body. The task context is not a parameter —
    // use GameTaskContextHolder.current() when needed.
    // routerKeyMethod extracts the routing key from the MESSAGE BODY; when unset, the
    // host-supplied defaultRouterKey is used.
    @GameMethod(value = 1001, routerKeyMethod = "playerId")
    public void useItem(Long roleId, UseItemMsg msg) {
        GameCommandTaskContext ctx = (GameCommandTaskContext) GameTaskContextHolder.current();
        // ...
    }

    // Or take the host's session object as the first parameter:
    // public void useItem(PlayerSession session, UseItemMsg msg) { ... }
}
```

The runtime provides no unified dispatcher facade (consistent with "no assembly facade / bridging lives in the host"). Register controllers at startup; when the transport layer receives a message, the **host does the dispatch bridging itself**:

```java
// At startup:
CommandRegistry.getInstance().register(new BagController());

// Per message: look up metadata → extract routing key → build task → submit to executor group
CommandMeta meta = CommandRegistry.getInstance().getCommandMeta(command);
if (meta == null) { /* unregistered command: drop and log error */ return; }
long routerKey = meta.extractRouterKey(message, defaultRouterKey);
GameCommandTaskRunnable task = new GameCommandTaskRunnable(
        meta, routerKey, busType, busId, seq, metadatas, message, session);
TaskSubmissionResult result = ExecutorGroupRegistry.getInstance().tryExecute(task);
if (!result.isAccepted()) { /* reply SERVER_BUSY / INTERNAL_ERROR */ }
```

Messages pass through as-is; whether/how to deserialize is up to the handler. Once `routerKeyMethod` is configured, extraction failure rejects the request instead of silently falling back to defaultRouterKey.
(For a complete host bridging example, see `GameRouterManager` in game-dev.)

## Events (EVENT)

```java
@EventHandler(group = ExecutorGroups.PLAYER)   // class-level default group
public class LevelUpListeners {

    @EventMethod                                // order is optional and defaults to 0
    public void sendMail(LevelUpEvent e) { ... }

    @EventMethod(group = ExecutorGroups.COMMON) // method-level override
    public void updateRank(LevelUpEvent e) { ... }
}

EventBus.getInstance().register(new LevelUpListeners());
EventBus.getInstance().publishEvent(new LevelUpEvent(playerId)); // routerKey comes with the event
```

- Event types match **exactly**: listeners registered on a parent class/interface are not triggered by subclass events (deliberate).
- `@EventMethod.order` is optional and defaults to `0`; smaller values are invoked or submitted first, while cross-group completion order is not guaranteed.
- Event objects must be **deeply immutable** because different executor groups may observe the same instance concurrently. Context metadata is deep-copied at dispatch boundaries and exposed only as defensive copies.
- Execution is inlined only when group and routerKey match **and the publisher context is currently bound**; otherwise it is submitted to the listener group.
- Use `tryPublishEvent(...)` when the caller needs inline/accepted/rejected listener counts.

## Timers (TIMER)

Timing uses a hashed wheel (shared default: 100ms × 512 slots) and monotonic absolute deadlines. Under normal load a callback never runs before its deadline and may be late by at most one tick. Cancellation immediately makes the Timeout terminal and updates pendingCount; shutdown cancels outstanding handles. `CronTask.start()` is single-shot and `cancel()` cancels its underlying Timeout.

```java
// One-shot delay: the wheel only computes the due time; on firing, dispatch the task to an
// executor group (always execute inside the wheel callback — never run business logic
// directly on the timing thread)
GameTimerTaskRunnable body = new GameTimerTaskRunnable(group, routerKey, busType, busId, null, runnable);
TimingWheel.getInstance().schedule(5_000,
        () -> ExecutorGroupRegistry.getInstance().execute(body));

// cron: call start() to begin; it reschedules itself after each firing until cancel()
CronTask cron = new CronTask("0 0 5 * * *", body);                       // sec min hour day month weekday
new CronTask("0 0 5 * * *", ZoneId.of("Asia/Shanghai"), body).start();   // specify explicitly for multi-timezone deployments
cron.start();
```

Fixed-rate/periodic and other scheduling shapes have no built-in facade; business code composes them on top of `TimingWheel` + `CronTask`. Cron's "day" and "weekday" follow standard OR semantics (when both are restricted, matching either fires).

## Exception Handling

Uncaught exceptions inside tasks are routed to `GameTaskExceptionHandlers` (default: error log); the host can replace it at startup:

```java
GameTaskExceptionHandlers.setHandler((ctx, cause) -> { /* log + monitoring alert */ });
```

Business `Exception`s never kill a worker or interrupt other listeners; fatal JVM/linkage `Error`s continue upward.

## Observability

Built-in lightweight monitoring follows the exception-handler pattern; the host can replace it at startup:

```java
// Default behavior: execution time ≥ threshold (default 1000ms) logs warn; drop on full queue logs error
GameTaskMonitors.setSlowTaskThresholdMs(500);

// Plugging in your own monitoring (callbacks run synchronously on worker threads —
// must be lightweight and non-blocking)
GameTaskMonitors.setMonitor(new GameTaskMonitor() {
    public void onTaskComplete(GameTaskContext ctx, long queueDelayMs, long execMs) { /* report latency distribution */ }
    public void onTaskDropped(GameTaskContext ctx) { /* drop alert */ }
    public void onTaskRejected(GameTaskContext ctx, TaskSubmissionResult reason) { /* reason-aware alert */ }
});
```

Sampled metrics (host pulls periodically):

- `DefaultExecutorGroup.queuedTasks()` — total tasks waiting across all worker queues in the group (`DisruptorExecutorGroup`'s method of the same name counts occupied ring slots)
- `DefaultExecutorGroup.droppedCount()` — cumulative dropped task count (same name on `DisruptorExecutorGroup`)
- `TimingWheel.getInstance().pendingCount()` — timer tasks waiting to fire on the wheel

## Usage Contract (Important)

1. **Registration must complete and freeze at startup**: call `CommandRegistry.freeze()` / `EventBus.freeze()` after registration; EventBus also freezes automatically on first publish. Later registration fails fast.
2. **Handler methods and classes must be public**: registration generates LambdaMetafactory invokers to replace reflection (zero reflection overhead on the hot path); non-public members fail fast with an exception at registration.
3. **Shutdown order**: first `TimingWheel.getInstance().shutdown()` to stop producing new tasks, then `ExecutorGroupRegistry.getInstance().shutdownAll(timeoutMs)`. timeoutMs is a total budget shared by all groups; when exhausted, remaining tasks are forcibly interrupted and dropped.
4. **SCENE-group tasks must not block** (IO, lock waits); blocking business goes to a virtual-thread group.
5. **routerKey must not be 0**: 0 would funnel every "no routing key" task onto worker-0 of the group, creating a hotspot, so building a task context with routerKey 0 throws outright. Global / player-less business must also supply a hashable key (e.g. connection id, guild id).
6. **Events must be deeply immutable**: do not mutate an event or referenced arrays, collections or objects after publication; listeners in different groups have no global completion order.
