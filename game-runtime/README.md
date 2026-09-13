# game-runtime

[Package layout, responsibilities and import migration (Chinese)](../docs/package-layout.md)

English | [简体中文](README.zh-CN.md) | [Architecture and responsibilities](docs/architecture.md)

JDK-only Java 25 implementation of the [OGBS Game Runtime v0.1 draft](docs/OGBS%20Game%20Runtime%20Specification%20v0.1.md). It executes decoded commands, events, cron jobs, dynamic timers, and callbacks through a common route runtime.

## Build and example

From the repository root:

~~~sh
mvn -pl game-runtime verify
java -cp "game-runtime/target/classes;game-runtime/target/test-classes" cn.managame.runtime.execution.RuntimeExample
~~~

The example command uses Windows classpath separators; use `:` on Linux/macOS. [RuntimeExample](src/test/java/cn/managame/runtime/execution/RuntimeExample.java) is also runnable in an IDE with JDK 25. It demonstrates command routing, an inline event, callback re-entry, and a timer controlled by a test clock.

After `mvn -pl game-runtime install`, applications can depend on:

~~~xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-runtime</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
~~~

## Route execution

The game project declares marker types without instances or separate registration:

~~~java
public final class GameRoutes {
    private GameRoutes() {}
    public interface Player extends RouteType {}
    public interface Guild extends RouteType {}
    public interface Rank extends RouteType {}
}
~~~

Exact Class identity distinguishes namespaces; equal names or inheritance do not merge queues. Serialization is scoped to one GameRuntime, and types supply no real-time scheduling guarantee. The fixed enum API has been removed: see the [route-type contract and migration](docs/route-types.md), which supersedes the original v0.1 draft's fixed groups.

A route is `Class<? extends RouteType> + long routeKey`. Types are defined by the game project; Runtime has no built-in business categories. Each entity has an independent FIFO queue drained in serial batches by workers in its execution domain; BALANCED batches may use different threads, while KEY_AFFINITY maps each key to a fixed platform worker. Route queues are partitioned across configurable shards, each with its own lock. Different routes may run concurrently. Empty queues are removed.

~~~java
try (GameRuntime runtime = GameRuntime.builder().build()) {
    RouteTask task = runtime.dispatch(GameRoutes.Player.class, 10001, () -> {
        HandlerContext context = HandlerContexts.current();
        // Synchronous calls, including virtual-thread blocking, retain this route.
    });
    task.join();
}
~~~

Ordinary dispatch always queues, including dispatch to the current route. Platform-thread handlers cannot join/get unfinished RouteTasks; virtual-thread handlers cannot wait on unfinished tasks in the current execution domain. Completed results and callers outside handlers can still wait. Cross-domain cycles and arbitrary third-party waits are not detected.

Handler methods return `void`. Complete all state access before returning; detached futures are outside the invocation and must re-enter through a runtime callback or dispatch. The runtime guarantees route affinity, not physical thread affinity.

`HandlerContexts.current()` is dynamically scoped with JDK 25 `ScopedValue`; it throws outside an invocation. `currentOrNull()` supports optional access. Context is restored after nested events and is not injected as a business argument.

Bind route types to independently owned platform or virtual execution domains for resource isolation, finite entity batches and separate task quotas. Rooms can choose KEY_AFFINITY; players can keep BALANCED scheduling. Route identity is independent of method parameter position and may be omitted from the signature. See [execution domains and entity queues](docs/execution-domains.md). Without explicit mappings a default virtual domain is used; once mappings are configured all used types must be bound.

Execution domains support custom RouteDispatcher backends, including a separate Disruptor adapter. close(Duration) returns a bounded shutdown report; close() defaults to 30 seconds and throws RuntimeShutdownException if incomplete. See [backend SPI, wait guards and shutdown](docs/dispatchers.md).

## Protocols and command binding

Define protocols explicitly; request and response IDs can be equal or different:

~~~java
ProtocolRegistry protocols = ProtocolRegistry.builder()
    .register(1001, ProtocolType.REQUEST, UseItemReq.class)
    .build();

ParameterResolverRegistry parameters = ParameterResolverRegistry.builder()
    .registerRouteSource(RoleId.class,
        invocation -> ((Session) invocation.connection()).roleId())
    .build();

@Handler(routeType = GameRoutes.Player.class)
public class Items {
    @HandlerMethod
    public void use(UseItemReq request, RoleId roleId) {
        CommandContext context = (CommandContext) HandlerContexts.current();
        // Respond explicitly through your application/transport component.
    }
}

GameRuntime runtime = GameRuntime.builder()
    .protocols(protocols).parameters(parameters)
    .defaultRoute(GameRoutes.Player.class, RoleId.class, RoleId::value)
    .handler(new Items()).build();

runtime.command(new UseItemReq(7), session, 41);
~~~

Imports and business value types are shown in the complete example. Register only the commands Runtime executes. Response types, codecs and request/response relationships belong to application message bindings. The legacy `response(...)` and `responseTo(...)` APIs remain deprecated for migration compatibility and are not used by command execution.

Each command method must be public, non-static, non-varargs, return void, and accept exactly one registered REQUEST parameter. Other parameters bind by exact declared parameter type. `ParameterResolver<T>` always receives a `CommandHandlerInvocation`; register it with `register(ValueType.class, resolver)` or `registerRouteSource(ValueType.class, resolver)`. Registering the same semantic type twice fails immediately, including across these two methods. Ordinary resolvers execute inside the active route and may access that route's state through application services. Events receive their event directly and do not use this registry.

Route source types and resolver instances are registered explicitly. Use `.routeKeyResolver(Source.class, resolver)` with `@Handler(routeKeyResolver = Resolver.class)` for an explicit strategy, or `.defaultRoute(routeType, Source.class, resolver)` for a route-type default. The source can be a request, semantic argument, or an additional pre-bound semantic value. Semantic route sources must use `registerRouteSource(...)`; using an ordinary state resolver as a route source fails at initialization. Only the chosen route-source slot resolves before admission. It must be thread-safe, nonblocking, and read immutable request/session identity rather than protected game state. RouteKeyResolver implementations have the same restriction. All other parameter slots resolve after the task starts on its route. A value shared by arguments and routing resolves once and is reused. Ordering starts at route admission; concurrent callers do not acquire ordering merely by entering `command()`. Basic Java parameter types are supported with their boxed resolver results.

`build()` scans the supplied instances and validates bindings. The hot path uses immutable registries, pre-bound resolvers, argument slots, and method handles; it does not rescan annotations or search resolver registries. Dependency injection and object construction belong to the application. The registries are exposed through `protocols()`, `commands()`, and `events()`.
Binding is handled by `HandlerBinder`: `HandlerMethodScanner` discovers entries, the binder validates and connects protocols/parameters/routes, and `HandlerInvokers` adapts callable signatures once. This module does not emit dynamic Handler classes or bytecode. Cron and Event use zero/one-argument invokers; Command signatures with 1–3 parameters use fixed-arity invokers with no invocation argument array. Larger signatures use one array and reuse duplicate semantic values in place. This does not eliminate allocations elsewhere in message processing. See the [benchmark methodology](benchmarks/README.md) and [recorded results](benchmarks/RESULTS.md).

## Events

Events are ordinary business objects, explicitly declared using `.eventType(MyEvent.class)`; they need no runtime interface or base class.

~~~java
@EventHandler(routeType = GameRoutes.Player.class,
              routeKeyResolver = ItemEventRoute.class)
public class ItemEvents {
    @EventMethod(order = 0)
    public void onUsed(ItemUsed event) {
        // Exactly one parameter: the registered event type.
    }
}
~~~

Register the event type, resolver source/instance, and handler instance on the builder. A resolver may consume a business superclass of the event. Subscriber lookup uses the exact published class; registering a superclass subscription does not subscribe to every subclass.

`runtime.publish(event)` processes subscribers in ascending `order`. Ties follow handler registration order, then deterministic method-signature order. A subscriber on the current route in this runtime runs synchronously with its own context; the event is passed directly without an EventHandlerInvocation wrapper. Other subscribers queue without being awaited. Publishing outside a handler queues every subscriber. Subscriber and route-resolution failures are reported individually and do not stop remaining subscribers. Ordering controls processing/submission, not completion across routes.

## Clock, cron, and timers

The default clock is system time in UTC. Supply `.clock(GameClock.system(zone))` for another zone. `GameClock` exposes calendar time through `now()`, `nowMillis()`, and `zoneId()`, and independent monotonic time through `nanoTime()` (defaults to System.nanoTime for custom clocks).

~~~java
@Cron(value = "0 */5 * * * ?", routeType = GameRoutes.Rank.class, routeKey = 1)
public void refreshRank() {
    // Executed through GameRoutes.Rank.class + 1, never on the scheduler thread.
}

cn.managame.runtime.execution.TimerTask timer = runtime.schedule(
    GameRoutes.Player.class, 10001, Duration.ofSeconds(5), () -> refresh());
timer.cancel();
~~~

Cron methods must be public, non-static, return void, and have no parameters. Cron route keys are static longs and do not use resolvers.

The six cron fields are second, minute, hour, day-of-month, month, day-of-week. Supported syntax includes wildcards, lists, ranges, steps, month names, and weekday names. Weekdays use Quartz numbering (SUN=1 through SAT=7). Exactly one day field must be `?`. Optional year fields and Quartz extensions `L`, `W`, and `#` are rejected during initialization. Impossible dates are also rejected. The configured timezone governs calendar evaluation, including DST gaps and overlaps.

Only registered business timers/crons activate the 10 ms clock poller; ordinary entity dispatch does not use it. The scheduler reads calendar and elapsed time and only submits route work. Each clock has an ordered set; both share one capacity and polling budget. Individual cancellation is O(log N). Each poll takes a bounded batch; cron calculations, route dispatch and error observers run outside the timer queue lock. Forward clock jumps coalesce missed cron occurrences into one firing per cron in a poll and schedule the next occurrence after the current clock time. Each cron keeps a timer-capacity reservation while its next occurrence is calculated. Backward jumps do not replay already submitted occurrences. Relative delays via schedule(Duration) use monotonic time, so calendar adjustments cannot change their waiting duration. scheduleAt(Instant) and Cron use calendar time. Polling and route backlog can delay execution beyond a deadline; this is not a hard realtime scheduler.

A timer waits in the scheduler, outside the route. Cancellation atomically transitions `SCHEDULED -> CANCELLED` and removes pending work. Claiming transitions to DISPATCHING and prevents cancellation. Admission then transitions to DISPATCHED or REJECTED. timer.completion() is a stable RouteTask for business success, dispatch/business failure, or CancellationException on cancellation. DISPATCHED only means accepted; inspect completion for the business outcome. Accepted route tasks are not cancellable.

For deterministic tests:

~~~java
MutableGameClock clock = new MutableGameClock(
    Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
try (GameRuntime runtime = GameRuntime.builder()
        .clock(clock).automaticScheduling(false).build()) {
    runtime.schedule(GameRoutes.Player.class, 1, Duration.ofSeconds(5), () -> refresh());
    clock.advance(Duration.ofSeconds(5));
    runtime.runDueTimers(); // Submits due tasks; it does not await their execution.
    runtime.dispatch(GameRoutes.Player.class, 1, () -> {}).join();
}
~~~

## Callbacks and RPC integration

Create a callback in a handler to capture its route and propagated metadata:

~~~java
RuntimeCallback<MyResponse> callback = runtime.callback(
    body -> applyResponse(body),
    failure -> handleFailure(failure));
// Later, from a transport completion thread:
callback.onSuccess(decodedResponse);
// Or callback.onFail(originalFailure).
~~~

Both signal methods enqueue the business callback using callback-reserved admission capacity. Only one accepted success/failure is delivered. `isSignalled()` means a signal has been claimed; it becomes true before submission finishes and does not mean the business callback has run. `completion()` returns a stable `RouteTask` for business completion or failure. The old `isCompleted()` name is deprecated and still reports signal state. Retryable admission rejection reports and throws HandlerException, resets isSignalled() and leaves completion() pending. Permanent closure reports RuntimeClosedException wrapped in HandlerException, terminally fails completion() and prevents further signals; non-rejection backend faults are terminal as well. The adapter may explicitly retry, or call `abort(rejection)` after rejection to terminally fail the same completion handle without running business callbacks. Abort also works before any signal, returns false if a signal/abort already owns the callback, and never cancels an accepted signal. Completion listeners still use route admission; Runtime does not retain rejected responses or retry them automatically. Competing signal calls can return false while a submission is in progress. The lambda API inherits the current route; `CallbackDefinition<T>` permits a full route override or creation outside a handler.

The failure consumer now receives `Throwable`. `onFail(Throwable)` preserves the original exception, including checked exceptions, application details and cause. Without a failure consumer, a `HandlerException` with that original cause is reported and `completion()` fails. The deprecated `onFail(int)` helper wraps the code in `CallbackFailureException`; migrate consumers from integer handling to exception handling. An explicit failure consumer handles the error on the callback route. Callbacks retain their route and propagated metadata; their context is a plain `HandlerContext`. To send an asynchronous response, capture the command connection and request correlation values in the handler, then send explicitly from the callback.

The runtime passes positive failure codes through unchanged and defines no RPC wire error constants. Transport/application components own their error-code namespaces. For game-rpc integration, use `RpcError.TIMEOUT.code()` and the other codes defined by game-rpc; local `RuntimeOverloadedException` values are mapped to wire errors by the adapter.

This module has no RPC or Netty dependency. A game-rpc integration decodes its response envelope and supplies the response body to `RuntimeCallback.onSuccess`, or supplies the error code to `onFail`. game-rpc completion callbacks run on the completing thread and may expose borrowed buffers: the adapter must supply owned decoded values or manage buffer retention/release across the asynchronous handoff. No protocol response is sent automatically when a handler completes.

## Task completion notifications

Inside a handler, `onComplete(listener)` captures the caller's Route in the same Runtime, plus its metadata:

~~~java
// Called from a Player handler.
RouteTask guildTask = runtime.dispatch(GameRoutes.Guild.class, guildId, () -> updateGuild());
RouteTask notification = guildTask.onComplete(failure -> {
    if (failure == null) updatePlayer();
    else handleGuildFailure(failure);
});
~~~

The listener receives null on success or the task's failure. It always queues onto the captured route, even if the source task has already finished. Outside a handler, specify `task.onComplete(new Route(GameRoutes.Player.class, playerId), listener)`. A callback's `completion()` supports the same notification API.

The returned RouteTask describes listener completion, not the original task. Notifications use callback admission capacity; rejection fails that returned task and is reported without automatic retry. Registering a listener does not reserve admission. Once shutdown starts, a listener whose source task finishes later may therefore be rejected. An unsignalled RuntimeCallback does not count as admitted work and waits for the adapter to signal success, timeout or transport failure. A signal attempted after closure terminally fails its completion handle.

Timer outcomes, elapsed/calendar clocks, application shutdown coordination and live diagnostics are described in [timers and diagnostics](docs/timers-and-diagnostics.md).

## Command API migration

The implicit callback response mechanism has been removed, including `InvocationOrigin` and `ResponseTarget`. Use `new CommandHandlerInvocation(request, connection, requestId, metadata)` and `new CommandContext(route, protocol, invocation)`. Remove the old final target argument and any `responseTarget()` access. Move response handling into explicit callback consumers using the connection and request correlation values captured by the application. Runtime does not forward callback failures to a command connection.

## Metadata, errors, and shutdown

`Metadata` is an immutable sparse array of typed keys and values. Keys use unsigned 16-bit IDs; 0..199 are framework-reserved and 200..65535 belong to applications. `MetadataKey.application(id, type)` enforces the application range. Supported value classes are `Integer`, `Long`, and `String`. Define each ID once; conflicting types within a metadata value fail immediately.

`metadata.with(key, value)` returns a new metadata value. The default `MetadataPropagator.copy()` shares immutable metadata safely; customize `.metadataPropagator(...)` to select fields for children. Events, callbacks, ordinary dispatch, and timers propagate metadata; cron begins with empty metadata.

`exceptionHandler(...)` receives unified `HandlerException` values including source, optional context, and cause. Command failures additionally preserve `invocation()` and `commandStage()` (`RESOLUTION`, `ADMISSION`, `EXECUTION`). Resolution can have no context; admission failures retain the resolved command context. The observer receives each command failure once, so the synchronous catch must not send a second response. Runtime-created CommandContext also exposes the original invocation. Routing-identity resolution and command admission failures are reported and thrown synchronously. Ordinary parameter resolution happens inside the invocation and fails the returned `RouteTask`. Executing command/route failures also fail their `RouteTask`. Event subscribers fail independently; cron, timer, and callback failures are reported without stranding a route. Exception observers must return promptly; observer failures are logged and do not block the next route task.

`close(Duration)` stops admission, cancels pending timers/crons and drains accepted tasks under one shared deadline. It returns a `ShutdownReport` with pending routes, remaining domains and `backendFailures()` entries containing domain, operation and cause. A backend throwing during shutdown or termination checks does not prevent other domains from being cleaned up; captured backend errors make `terminated()` false. Each backend's shutdown is attempted once, and errors remain in subsequent reports; another close can wait for normal timed-out work but does not repair a failed backend. `close()` uses a configurable 30-second default and throws `RuntimeShutdownException` if the report is incomplete. Call either method outside a runtime handler. Timeout does not interrupt business or release its route ownership; accepted batches keep draining. Completion listeners not yet submitted and late callback signals are new submissions and may be rejected after shutdown starts.

Transport, codecs, discovery, persistence, dependency injection, and process lifecycle orchestration are outside this module.

## Capacity, overload, and metrics

Domain admission and business timers have separate configuration. The defaults are:

~~~java
DomainLimits capacity = new DomainLimits(
    100_000, // ordinary tasks in one domain
    4096,    // ordinary tasks on one Route
    10_000,  // additional callback slots in the domain
    64       // additional callback slots on one Route
);
TimerOptions timers = new TimerOptions(100_000, 1024); // timer capacity, entries per poll

// Used when there are no explicit execution-domain mappings.
GameRuntime runtime = GameRuntime.builder()
    .domainLimits(capacity).routeShards(64).timerOptions(timers).build();

// Explicit domains own their capacities and queue-management shards.
var players = ExecutionDomain.platform("players").threads(4)
    .limits(capacity).routeShards(64).build();
~~~

`GameRuntime.Builder.domainLimits` and `routeShards` configure only the default domain. With explicit mappings, use each `ExecutionDomain.Builder` instead. `timerOptions` always configures Runtime-wide business timers; execution domains contain no timer settings. Shard count controls queue locks, independently of workers. The deprecated `GameRuntime.Builder.limits(RuntimeLimits)` bridge applies all seven legacy fields to their respective settings; later builder calls override the corresponding values.

These defaults are not measured capacity recommendations. Task limits count running and queued work. Command, ordinary dispatch, cross-route Event, Cron and Timer use ordinary capacity. Callbacks can also use the reserved slots, and still execute in route FIFO order. Inline events remain part of the active task and consume no additional slot.

Full queues reject ordinary dispatch or timer registration with `RuntimeOverloadedException`: `GLOBAL_CAPACITY` (legacy default), `DOMAIN_CAPACITY` (explicit domains), `ROUTE_CAPACITY` or `TIMER_CAPACITY`. Command admission wraps this in `HandlerException`. Cross-route event and due timer/cron rejection is reported to the exception handler without stopping other subscribers. Rejected firings are not automatically retried; recurring cron remains armed for its next occurrence. Retryable callback signal rejection is also thrown to its caller and resets its signal flag for explicit adapter retry; a rejected RouteTask completion notification instead fails its returned notification task without automatic retry. If callback reserves are exhausted, the adapter chooses retry, fail-fast or upstream backpressure. The reserve does not guarantee delivery for arbitrarily many outstanding RPC calls.

Cancellation frees timer capacity; completion/failure frees task capacity. Each cron reserves a timer slot even while its next occurrence is being calculated. A build fails if registered crons exceed timer capacity.

`RuntimeMetrics` reports outstanding tasks, active routes, scheduled timers, successful/failed tasks, rejections (including timer registrations), and cumulative/maximum queue and execution durations. Snapshots are approximate under concurrency. Durations use monotonic nanoseconds and are independent of the adjustable business clock.

`runDueTimers()` processes at most `maxTimersPerPoll` due entries and returns zero if another poll is active. Reentrant calls from an observer also return zero rather than blocking; this does not imply the queue is empty. For deterministic tests, disable automatic scheduling and complete each poll before adjusting the clock again.

## Migration from the initial v0.1 implementation

Use `registerRouteSource(Type.class, resolver)` for semantic routing identities and `register(Type.class, resolver)` for other command values. The invocation-class argument and `ParameterResolver<I,T>` form have been removed; resolvers are now `ParameterResolver<T>` receiving a command invocation. Use `DomainLimits`, separate `routeShards`, and `TimerOptions` in place of the deprecated Runtime-wide limits bridge; an execution domain no longer accepts `RuntimeLimits`. Custom dispatchers must implement `reschedule` for reliable continuation. Replace callback `isCompleted()` checks with `isSignalled()` or `completion().isDone()` according to intent. These changes require recompiling 0.1.0-SNAPSHOT clients. Replace the removed `cn.managame.runtime.RpcErrorCode` with codes defined by the upstream RPC/application component.

## Validation

`mvn -pl game-runtime verify` runs tests for ordering under concurrent submissions, virtual-thread blocking, self-route wait rejection, context restoration, resolver binding and initialization errors, protocol relationships, event fan-out and failure isolation, timer cancellation, clock jumps, cron/DST behavior, callback re-entry, and failure propagation. Additional architecture tests cover nested callback provenance, callback reserves and retry after rejection, on-route state resolution, concurrent global admission, sharded FIFO ordering, shutdown races, timer-capacity reservations, observer calls outside queue locks, generic method overrides, reliable continuations, completion notifications and per-domain shutdown fault isolation. These are correctness tests, not capacity benchmarks.

### Infrastructure completion observation

`RouteTask.completionStage()` returns a read-only CompletionStage for bridges such as RPC adapters to observe terminal results, including backend failures. Observers follow JDK CompletionStage threading rules with no Route/HandlerContext guarantee; use `onComplete` for routed business notifications. Observer exceptions and completion/cancellation of futures derived from the stage cannot change the original RouteTask.

Request correlation is a fixed CommandHandlerInvocation.requestId field, exposed by CommandContext.requestId(). Submit with command(request, connection, requestId), or construct CommandHandlerInvocation(request, connection, requestId, metadata) when extension metadata is needed. Do not duplicate the ID in Metadata. Zero denotes an uncorrelated local/one-way command; the existing three-argument invocation constructor and command(request, connection) default to zero. Negative IDs are rejected. Resolution, admission and execution failures retain the original invocation, so correlation remains available before a context exists.
