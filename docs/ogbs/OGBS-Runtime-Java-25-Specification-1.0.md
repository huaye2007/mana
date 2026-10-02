# OGBS Runtime Java 25 Development Specification 1.0

**[English](OGBS-Runtime-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md)

Document type: **Java Development Specification**. Standard: [Runtime Specification](OGBS-Runtime-1.0.md).

This document defines public Java APIs, defaults, exceptions, threads/resources, extensions, and validation. Implementations satisfy both specifications, including behavior, not signatures alone. Fix implementation deviations; design changes update both layers. Pending/unverified capabilities are not completed features.

Status: current repository implementation contract. Semantics: [OGBS Runtime](OGBS-Runtime-1.0.md). Shared types: [Core](OGBS-Core-1.0.md).

<a id="1-模块与主-api"></a>

## 1. Module and main API

Maven: `cn.managame:game-runtime:1.0.0-SNAPSHOT`. Entry package: `cn.managame.runtime`, with responsibility-based subpackages. Depends on `game-core`, `game-network`, and Jackson Databind 2.21.3 (transitive Core/Annotations) for HTTP result encoding and JSON Key extraction; requires Java 25. HTTP is a subpackage of this artifact, with native Netty HTTP types, not another Maven module. Runtime has no RPC dependency.

Signature excerpts below; source defines the types.

```java
public interface GameRuntime extends AutoCloseable {
    void dispatch(HandlerContext context);
    HttpDispatcher http();
    <T> void call(int routeDomain, long routeKey,
                  Supplier<T> action, RouteCallback<T> callback);
    EventBus eventBus();
    RuntimeTimer timer();
    CronScheduler cron();
    ProtocolRegistry protocols();
    RouteKeyRegistry routeKeys();
    void close();
}

public interface RouteCallback<T> {
    void onSuccess(T result);
    void onFail(int errorCode);
}
```

No separate start, dynamic registration, or public arbitrary Runnable dispatch. Successful build is usable; ordinary network messages need integration-created HandlerContext and dispatch. HTTP connects through `asyncHandler(runtime.http()::dispatch)`; see [§13](#runtime-http-api).

Public packages:

| Package | Contents |
| --- | --- |
| cn.managame.runtime | GameRuntime, GameRuntimeBuilder |
| cn.managame.runtime.context | Context hierarchy, defaults, read-only Contexts |
| cn.managame.runtime.route | Domain, Key binding/extraction/registry, RouteCallback |
| cn.managame.runtime.executor | Executor SPI, bindings, official implementations |
| cn.managame.runtime.protocol | Descriptors, registration, request/response association |
| cn.managame.runtime.handler | Handler annotations |
| cn.managame.runtime.http | HttpHandler, HttpMethod, HttpDispatcher, HttpContext, DefaultHttpContext, HttpContextFactory, HttpResultCallback, HttpResultCodec |
| cn.managame.runtime.event | Event, EventBus, annotations |
| cn.managame.runtime.timer | RuntimeTimer, TimerRef, Cron, CronScheduler |
| cn.managame.runtime.time | GameTime |
| cn.managame.runtime.error | RuntimeError, handlers, dispatch exceptions |
| cn.managame.runtime.internal | Registration compilation, scheduling, context binding; not application API |

Former `cn.managame.runtime.*` types moved into corresponding subpackages. Wildcard imports do not include subpackages; update imports. One game-runtime artifact remains, without separate publication units. See [module layout](../../game-runtime/README.md).

<a id="2-构建"></a>

## 2. Build

```java
GameRuntimeBuilder.builder()
    .routeDomains(Iterable<RouteDomain>)
    .routeExecutors(Iterable<RouteExecutorBinding>)
    .protocols(Iterable<? extends ProtocolProvider>)
    .routeKeys(Iterable<RouteKeyBinding<?>>)
    .handlers(Iterable<?>)
    .httpHandlers(Iterable<?>)
    .httpContextFactory(HttpContextFactory)
    .httpResultCodec(HttpResultCodec)
    .eventHandlers(Iterable<?>)
    .cronHandlers(Iterable<?>)
    .errorHandler(RuntimeErrorHandler)
    .cronZone(ZoneId)
    .build();
```

List setters replace previous configuration and copy each Iterable, without accumulation. Defaults: empty lists, UTC Cron zone, System.Logger error handler.

`RouteDomain.of(int id, String name)` requires id>0 and nonnull name. `RouteExecutorBinding.of(executor, int... domains)` needs at least one Domain. Every registered Domain binds exactly one Executor; unknown Domains reject.

Build parses annotations and compiles MethodHandles, then freezes registries. No classpath scanning; callers provide instances. Handler/Event/Cron methods must be public instance void methods without varargs. HTTP methods may return ordinary business objects instead of void; transport response types and primitive return declarations are rejected. Invalid configuration generally throws IllegalArgumentException; null may throw NullPointerException.

Failed build does not close supplied Executors; successful Runtime owns their closure. Independently managed Runtimes must not share a closable Executor without explicit external-ownership adaptation.

<a id="21-构建阶段应完成什么"></a>

### 2.1 Required build work

Do not defer parsing until the first message. Before returning, compile Domain/Executor bindings, protocols/response relations, Key extractors, Handler/Event/Cron and HTTP methods, and expressions. Reject duplicate/unresolvable definitions at startup, preventing arrival order from choosing a Handler.

```text
Application creates business objects and Executors explicitly
→ Builder copies each supplied list
→ RuntimeCompiler validates relationships and compiles methods
→ establish fixed registries and execution entries
→ assemble Timer/Cron
→ return usable Runtime
```

List snapshots freeze membership, not business-object internals. Handler instances remain the supplied objects and may serve several Routes concurrently. Instance fields are not inherently serialized; organize mutable state by Route.

handlers(A) then handlers(B) registers only B. Merge module lists first to register both. This differs from Data's appended registrations and Network's appended pipelines; builder names do not imply identical semantics.

<a id="22-注册失败的定位"></a>

### 2.2 Locating registration failures

| Check | Typical error | Fix location |
| --- | --- | --- |
| Domain/Executor | Duplicate ID, unknown Domain, missing binding | Startup assembly |
| Protocol | Duplicate message type or type+command | ProtocolProvider |
| Request/response | Unregistered type or wrong direction | bindResponse registration |
| Handler | Unregistered message, multiple methods, invalid Domain | Annotation/handlers list |
| Context | Signature cannot accept HandlerContext hierarchy | Handler signature |
| Event | Not a single Event parameter | Listener definition |
| Cron | Invalid expression/Route, duplicate declaring class+method | Annotation/registration |

Applications clean Executors created before failed build. Successful Runtime closes bound Executors; unrelated lifetimes require ownership adaptation before sharing.

<a id="3-最小接入示例"></a>

## 3. Minimal integration example

```java
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeBuilder;
import cn.managame.runtime.context.DefaultHandlerContext;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.runtime.protocol.ProtocolProvider;
import cn.managame.runtime.protocol.Protocols;
import cn.managame.runtime.route.RouteDomain;
import cn.managame.runtime.route.RouteKeyBinding;
import java.util.List;

public class RuntimeExample {
    public record Ping(long playerId) {}

    @Handler(domain = 1)
    public static final class PingHandler {
        @HandlerMethod
        public void onPing(HandlerContext context, Ping message) {
            System.out.println(context.routeKey() + ": " + message);
        }
    }

    public static void main(String[] args) {
        ProtocolProvider protocols = registrar ->
            registrar.register(Protocols.notify(1001, Ping.class));

        try (GameRuntime runtime = GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "player")))
                .routeExecutors(List.of(RouteExecutorBinding.of(
                    RouteExecutors.platformThreads(2), 1)))
                .protocols(List.of(protocols))
                .routeKeys(List.of(RouteKeyBinding.of(Ping.class, Ping::playerId)))
                .handlers(List.of(new PingHandler()))
                .build()) {
            Ping message = new Ping(42);
            long key = runtime.routeKeys().getRouteKey(message);
            runtime.dispatch(new DefaultHandlerContext(1, key, message));
        }
    }
}
```

Key extraction above is explicitly invoked by integration. close does not wait for completion; official platform executors continue accepted tasks. Services arrange admission shutdown and in-flight completion themselves.

<a id="4-context-与作用域"></a>

## 4. Context and scope

```java
interface Context {
    int routeDomain();
    long routeKey();
}
interface InvocationContext extends Context {
    int businessIdType();
    long businessId();
    Metadata metadata();
}
interface HandlerContext extends InvocationContext { Object message(); }
interface EventContext extends InvocationContext { Event event(); }
interface TimerContext extends Context {}
interface RouteCallContext extends InvocationContext {}
// cn.managame.runtime.http; full contract in section 13
interface HttpContext extends Context {
    FullHttpRequest request();
    HttpResultCallback responseCallback();
}
```

Metadata is in `cn.managame.core`. Default constructors:

| Type | Parameters |
| --- | --- |
| DefaultContext | (int domain, long key) |
| DefaultInvocationContext | (int domain, long key, int businessIdType, long businessId, Metadata metadata) |
| DefaultHandlerContext | (int domain, long key, Object message) |
| DefaultHandlerContext | (int domain, long key, int businessIdType, long businessId, Metadata metadata, Object message) |
| DefaultEventContext | (Event event, int businessIdType, long businessId, Metadata metadata) |
| DefaultTimerContext | (int domain, long key) |
| DefaultRouteCallContext | (int domain, long key, int businessIdType, long businessId, Metadata metadata) |

Short HandlerContext construction uses identity 0 and empty Metadata. businessIdType is 0–255; Metadata/message cannot be null. Defaults are extensible for connections/correlation fields, which do not automatically copy into EventContext/RouteCallContext.

```java
Context Contexts.current();
Context Contexts.currentOrNull();
<T extends Context> T Contexts.current(Class<T> type);
```

Uses Java 25 ScopedValue. Unbound current throws NoSuchElementException; currentOrNull returns null; wrong casts throw ClassCastException. Only Runtime execution binds context; no public manual bind. Nested completion restores the outer value.

<a id="41-绑定与恢复的实现约束"></a>

### 4.1 Binding and restoration constraints

Internal RuntimeContexts binds Runtime identity and Context together. Pseudocode, omitting errors and not defining another API:

```text
Execute:
    run action inside ScopedValue scope for this Runtime + Context
    restore previous scope afterward, including exceptional exit

Submit:
    validate target Route
    if current Runtime identity, Domain, and Key all match: execute wrapper immediately
    otherwise: use bound RouteExecutor.tryExecute
```

Comparing only routeKey is insufficient. Callbacks retain the original context object. With a custom outer HandlerContext, target computation uses DefaultRouteCallContext; only returning to source restores the custom object.

Contexts.current(MyHandlerContext.class) is valid only when that is the actual context. Event, Timer, and target RouteCall code must not assume network-request context. No public bind lets arbitrary threads impersonate Route execution.

<a id="5-protocol-与-routekey-注册"></a>

## 5. Protocol and RouteKey registration

```java
interface ProtocolProvider { void register(ProtocolRegistrar registrar); }

interface ProtocolRegistrar {
    void register(ProtocolDescriptor<?> descriptor);
    void bindResponse(Class<?> requestType, Class<?> responseType);
}

interface ProtocolDescriptor<T> {
    ProtocolType type();
    int command();
    Class<T> messageType();
}
```

ProtocolType: REQUEST, RESPONSE, NOTIFY. Factories: `Protocols.request(command, type)`, `response`, `notify`. command uses the full int bit pattern without positive-only or 24-bit restrictions.

Index by ProtocolType+command and exact message Class; both must be unique. REQUEST/RESPONSE may share command. bindResponse requires registered REQUEST and RESPONSE endpoints; at most one response per request, but not every request requires binding.

```java
ProtocolDescriptor<?> ProtocolRegistry.get(ProtocolType type, int command);
ProtocolDescriptor<?> ProtocolRegistry.get(Class<?> messageType);
Class<?> ProtocolRegistry.getResponseType(Class<?> requestType);

long RouteKeyExtractor<T>.extract(T message);
RouteKeyBinding<T> RouteKeyBinding.of(
    Class<T> messageType, RouteKeyExtractor<T> extractor);
long RouteKeyRegistry.getRouteKey(Object message);
```

Nonnull-type lookup misses return null. Key registration is independent; exact-Class lookup without extractor returns 0. Extractor exceptions propagate. Zero cannot dispatch. Registration never makes dispatch automatically correct Context.

## 6. Handler

Objects in handlers require the inheritable type annotation `@Handler(domain = ...)`. Nonzero method-level `@HandlerMethod(domain = ...)` overrides the class Domain; zero uses it.

Methods require exactly one registered REQUEST/NOTIFY parameter and optionally one Context parameter, in either order:

```java
@HandlerMethod
public void onMessage(MyMessage message) { /* ... */ }

@HandlerMethod
public void onMessage(MyHandlerContext context, MyMessage message) { /* ... */ }
```

Context parameter may be Context, InvocationContext, HandlerContext, or custom HandlerContext implementation. EventContext/TimerContext and unrelated types cannot substitute. Dispatch validates the actual context against the method parameter.

Exact Class matching; at most one Handler per message. Preconditions/admission failures throw RuntimeDispatchException. Once execution starts, exceptions go to RuntimeErrorHandler, including inline execution, without becoming business responses.

<a id="61-方法签名判定示例"></a>

### 6.1 Signature examples

M is a registered REQUEST/NOTIFY; CustomContext is a HandlerContext subtype:

| Form | Valid Handler | Reason |
| --- | --- | --- |
| public void handle(M m) | Yes | One message |
| public void handle(HandlerContext c, M m) | Yes | One message, valid context |
| public void handle(M m, CustomContext c) | Yes | Either order; actual context checked |
| public M handle(M m) | No | Must return void; no automatic response sending |
| public static void handle(M m) | No | Instance method required |
| public void handle(M a, M b) | No | Message not unique |
| public void handle(TimerContext c, M m) | No | Wrong context hierarchy |
| public void handle(M... messages) | No | No varargs |

RuntimeDispatchException means precondition/admission failure; Handler exceptions go to RuntimeErrorHandler. Even inline callers cannot obtain business results by catching original Handler exceptions.

## 7. RouteExecutor

```java
interface RouteExecutor extends AutoCloseable {
    RouteExecuteStatus tryExecute(int domain, long key, Runnable task);
    default void close() {}
}
```

Statuses: ACCEPTED, OVERLOADED, CLOSED.

| Construction | Serialization | Capacity |
| --- | --- | --- |
| RouteExecutors.platformThreads(workers) | Fixed platform-thread shards | Default 65,536 waiting tasks per shard |
| new StripedRouteExecutor(workers, queueCapacity) | Complete Route hashes to single-thread shard | Per-shard waiting queue, excluding running task |
| RouteExecutors.virtualThreads() | Serial Mailbox per active Route | Default 65,536 unfinished tasks total |
| new VirtualThreadRouteExecutor(capacity) | Virtual threads process active Mailboxes | All Routes combined, including running tasks |

Parameters must be positive. Different Routes on one platform shard wait for each other. Virtual-thread Mailbox state is removed when idle.

Both close without waiting and continue accepted tasks. Custom executors must honor admission/serialization; an ordinary multithreaded pool alone is insufficient.

<a id="71-两种容量的计算示例"></a>

### 7.1 Capacity examples

With 2 StripedRouteExecutor shards and queueCapacity=100, shard A containing 1 running +100 waiting tasks rejects the next A task even if B is idle. Do not borrow B and break A's queue boundary. Running work is excluded from this capacity.

VirtualThreadRouteExecutor(capacity=100) counts every unfinished task across Routes: 99 waiting +1 running is full. Virtual threads do not mean unlimited admission.

Same-Route inlining bypasses capacity checks and adds no queued share. Recursive same-Route Event/call still consumes stack; capacity does not prevent infinite recursion.

<a id="72-自定义-executor-的验收边界"></a>

### 7.2 Custom Executor acceptance criteria

Verify complete-Route mutual exclusion, enqueue order, rejected tasks never executing, admitted tasks executing once, and close/submit races. Executing after rejection misleads callers; accepting then silently dropping also violates the contract.

Runtime binds Context in task wrappers; Executor owns admission/scheduling only. Do not infer player IDs, protocols, or business exception meaning. Capacity/closure changes affect Java contracts; observable admission changes also update the standard.

<a id="8-跨-route-调用与回调"></a>

## 8. Cross-Route calls and callbacks

Call only inside this Runtime's execution context:

```java
runtime.call(2, guildId,
    () -> loadGuildSnapshot(guildId),
    new RouteCallback<GuildSnapshot>() {
        public void onSuccess(GuildSnapshot snapshot) {
            // Back on source Route; Contexts.current() is the original source context.
        }
        public void onFail(int errorCode) {
            // Framework error; represent business failures in the result object.
        }
    });
```

Target action uses DefaultRouteCallContext, inheriting InvocationContext identity/Metadata, not custom fields.

Absent/foreign current context throws IllegalStateException. Target validation/admission failure calls onFail. Action exceptions report ROUTE_CALL_EXECUTION_ERROR (3008) and attempt onFail. Callback exceptions only report RUNTIME_EXECUTION_ERROR (3009).

Source callback rejection reports ROUTE_CALLBACK_DISPATCH_FAILED (3010), without fallback on another thread/Route. Same-Route calls may finish before call returns; do not assume asynchronous completion. No Future, timeout parameter, or cancellation handle.

## 9. EventBus

```java
interface Event {
    int routeDomain();
    long routeKey();
}
interface EventBus { void publish(Event event); }

@EventMethod(order = 10)
public void onChanged(MyEvent event) { /* Use Contexts.current() for context. */ }
```

Register objects explicitly with builder.eventHandlers. Optional marker @EventHandler is not currently required. Listener methods take one Event subtype, no extra Context parameter.

Match exact Class, ascending order, unspecified ties. Report listener failure as 3009 and continue. Even without listeners, validate Route and admission.

Use DefaultEventContext. Same-Runtime InvocationContext publication inherits identity/Metadata; other sources use identity 0/empty. Same-Route publication may inline; publish return does not mean every cross-Route listener finished.

<a id="10-gametimetimer-与-cron"></a>

## 10. GameTime, Timer, and Cron

### 10.1 GameTime

```java
import cn.managame.runtime.time.GameTime;

long nowMillis = GameTime.currentTimeMillis();
LocalDateTime localNow = GameTime.now(ZoneId.of("Asia/Shanghai"));
GameTime.setClock(Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC));
GameTime.resetClock();
```

Default Clock.systemUTC(); volatile Clock reference is process-wide, not per Runtime. now requires explicit ZoneId; setClock/now reject null. Tests resetClock afterward to avoid affecting other Runtimes.

setClock/resetClock only replace business wall time, without scheduler notification, automatic rescheduling, or replay. Network timeout/duration measurement remains monotonic. Business code holds TimerRef, cancels, recomputes Duration from new GameTime, and schedules dynamic timers again.

<a id="102-一次性-timer"></a>

### 10.2 One-shot Timer

```java
TimerRef RuntimeTimer.schedule(
    int domain, long key, Duration delay, Runnable task);
boolean TimerRef.cancel();

@Cron(value = "0 */5 * * * ?", domain = 1, routeKey = 42)
public void refresh() { /* ... */ }
```

schedule validates Runtime/Route immediately. Zero delay is allowed, negative is not. One daemon scheduler thread per Runtime submits due business work to Route, where it runs in DefaultTimerContext.

cancel succeeds only before trigger eligibility; repeated/already-triggered cancellation returns false and cannot retract queued work. Report overload/execution failures through error handling. Closure stops scheduling without individual timer cancellation notifications.

<a id="103-cron-表达式"></a>

### 10.3 Cron expressions

Cron methods are public instance void with no parameters, registered through cronHandlers. cronZone defaults UTC; configure explicitly, e.g. `ZoneId.of("Asia/Shanghai")`.

Supported syntax:

| Item | Range/convention |
| --- | --- |
| Fields | Second, minute, hour, day, month, weekday: six |
| Numeric ranges | Seconds/minutes 0–59, hours 0–23, days 1–31, months 1–12, weekdays 1–7 |
| Weekday | 1 = Sunday |
| Syntax | Numbers, *, comma lists, ranges, steps; ? only in day/weekday |
| Day and weekday | AND when both restricted |
| Unsupported | Names, L, W, #, year field, full Quartz extensions |
| Search horizon | Next trigger within eight years; reject if none |

`0 */5 * * * ?` fires at second 0 every five minutes. Cron uses the same one-shot RuntimeTimer. After business completion, read current GameTime and calculate one future trigger. No business retries; Route rejection still schedules another cycle. No replay of every missed instant; unsuitable for real-time game Tick.

<a id="104-cron-取消与重排"></a>

### 10.4 Cron cancellation and rescheduling

```java
interface CronScheduler {
    boolean cancel(Class<?> type, String methodName);
    boolean reschedule(Class<?> type, String methodName);
    void rescheduleAll();
}

GameTime.setClock(testClock);
runtime.cron().rescheduleAll();
runtime.cron().cancel(SystemCron.class, "dailyReset");
runtime.cron().reschedule(SystemCron.class, "dailyReset");
```

SystemCron/testClock are application-defined. Key is reflected **DeclaringClass + methodName**; inherited non-overridden methods use the parent Class. Duplicate keys fail build even across different instances.

- cancel returns true for active items and stops future cycles; unknown/already canceled returns false. Already claimed methods may finish.
- reschedule cancels old TimerRef and recalculates from current GameTime, reactivating canceled items; unknown key returns false.
- rescheduleAll includes previously canceled items, one at a time, without cross-item atomicity.
- After close, cancel returns false; reschedule/rescheduleAll throw RuntimeDispatchException with RUNTIME_CLOSED.
- Generations isolate old/new schedules. Old callbacks cannot overwrite new schedules; queued old actions not yet claimed by Cron skip business execution.
- Neither cancellation nor rescheduling interrupts started methods. New tasks still obey same-Route serialization while old work runs.

Cron cancellation also stops future cycles, unlike one-shot TimerRef. Explicit rescheduling may revisit a calendar instant; no calendar deduplication or business idempotency.

<a id="105-timer-与-cron-的内部协作"></a>

### 10.5 Internal Timer/Cron cooperation

RuntimeTimers uses one ScheduledThreadPoolExecutor for expiry signals; RouteExecutor runs business work. A trigger/cancel flag arbitrates each one-shot task before Route submission. remove-on-cancel removes canceled scheduler entries; closure does not run future delayed tasks.

Cron adds registration state and generations. Timer expiry alone does not authorize the Cron method: after entering Route, check the current valid generation. Rescheduling invalidates queued unclaimed old actions; completed old methods cannot reinstall old cycles.

Different checkpoints:

```text
Timer trigger eligibility → attempt Route submission → Cron generation/execution check → business method
```

Ordinary Timer has no Cron generation check; do not apply Cron skipping semantics to TimerRef.cancel. Cron recalculates after method completion, so duration affects the next possible instant without accumulating historical triggers.

<a id="11-错误与关闭"></a>

## 11. Errors and closure

```java
interface RuntimeErrorHandler { void onError(RuntimeError error); }

record RuntimeError(int errorCode, Context context,
                    int routeDomain, long routeKey, Throwable cause) {}

int RuntimeDispatchException.errorCode();
```

Codes: [Core](OGBS-Core-1.0.md). RuntimeError carries the failing Context/Route. Error handlers should return quickly and avoid throwing; implementation logs their exceptions.

Idempotent close stops scheduling and closes each Executor once by object identity without awaiting queue drain. New dispatch/publish/schedule rejects. call from an already executing source may receive RUNTIME_CLOSED via onFail, though return submission may itself face executor closure. Protocol/Key registries remain readable.

<a id="111-api-结果与异步错误的区别"></a>

### 11.1 API results versus asynchronous errors

| Entry/stage | Direct caller observation | Error-handler observation |
| --- | --- | --- |
| dispatch / publish validation or rejection | RuntimeDispatchException | Rejection is not represented as successful execution |
| Handler / Event exception | No automatic business response | RUNTIME_EXECUTION_ERROR |
| call without valid source | IllegalStateException | No target call starts |
| call target rejects | Attempt onFail(errorCode) | Additional 3010 if return fails |
| call action throws | Attempt onFail(3008) | ROUTE_CALL_EXECUTION_ERROR |
| call callback throws | No second callback | RUNTIME_EXECUTION_ERROR |
| Timer Route rejection after expiry | No synchronous caller stack | Scheduling error path; closure stops scheduling |
| Registry reads after close | Read-only access remains | Execution not restarted |

No dedicated error thread is guaranteed; handlers run on the triggering path. Return quickly and avoid foreign-Route mutable state. External alerts need explicit asynchronous ownership/delivery boundaries.

<a id="112-后续维护检查点"></a>

### 11.2 Maintenance checkpoints

New execution entries define target Route, Context type, identity inheritance, admission failure, execution errors, and closure behavior. New Context fields specify whether preserved on the same instance or copied across contexts; never silently broaden identity propagation.

MethodHandle, ScopedValue, and platform/virtual threads are Java mechanisms. Other languages may use different mechanisms while preserving standard behavior; internal optimization need not redefine business Route identity.

<a id="12-源码与测试"></a>

## 12. Source and tests

- [GameTime](../../game-runtime/src/main/java/cn/managame/runtime/time/GameTime.java)
- [CronScheduler](../../game-runtime/src/main/java/cn/managame/runtime/timer/CronScheduler.java)
- [GameTimeTest](../../game-runtime/src/test/java/cn/managame/runtime/time/GameTimeTest.java)
- [CronSchedulerTest](../../game-runtime/src/test/java/cn/managame/runtime/timer/CronSchedulerTest.java)

- [GameRuntime / main API](../../game-runtime/src/main/java/cn/managame/runtime/GameRuntime.java)
- [GameRuntimeBuilder / registration validation](../../game-runtime/src/main/java/cn/managame/runtime/GameRuntimeBuilder.java)
- [DefaultGameRuntime / execution and closure](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java)
- [Contexts / ScopedValue](../../game-runtime/src/main/java/cn/managame/runtime/context/Contexts.java)
- [RuntimeTest](../../game-runtime/src/test/java/cn/managame/runtime/RuntimeTest.java)
- [RouteExecutorTest](../../game-runtime/src/test/java/cn/managame/runtime/executor/RouteExecutorTest.java)

Run root `mvn -pl game-runtime -am test` for Runtime/dependencies; `mvn verify` for full verification.

<a id="121-易错契约的测试定位"></a>

### 12.1 Tests for error-prone contracts

Existing regression entry points, not exhaustive enumeration of thread interleavings:

| Contract | Test class and method |
| --- | --- |
| Same-Route inline events, failure isolation, outer restoration | RuntimeTest.sameRouteInlineEventsAreOrderedAndIsolated |
| Equal Route values across Runtimes never inline | RuntimeTest.separateRuntimesNeverInlineOnEqualRoute |
| Cross-Route compute and exact source context restoration | RuntimeTest.crossRouteSuccessFailureAndSourceRestoration |
| Target rejection versus return rejection | RuntimeTest.rejectedTargetFailsOnSourceAndRejectedReturnOnlyReports |
| Fresh Timer Context and cancellation boundary | RuntimeTest.timerHasFreshContextAndCancellationIsBeforeSubmissionOnly |
| Clock changes do not auto-reschedule | CronSchedulerTest.changingGameClockNeedsExplicitRescheduleAndDoesNotChangeDynamicTimer |
| Queued old generation invalidated; running old completion cannot overwrite new | CronSchedulerTest.cancellationAndRescheduleInvalidateAlreadyQueuedGenerations / rescheduleDuringExecutionCannotBeUndoneByOldCompletion |
| Shared Executor closed once, error handler isolated | RuntimeTest.sharedExecutorClosesOnceAndErrorHandlerCannotEscape |

<a id="runtime-http-api"></a>

## 13. HTTP annotations and Route integration

This binding implements [RT-HTTP-01–08](OGBS-Runtime-1.0.md#runtime-http-profile) inside game-runtime. Network's [HttpResponseCallback](OGBS-Network-Java-25-Specification-1.0.md#http-async-response) is the adapter's transport boundary; business code uses HttpResultCallback with ordinary objects. There is no CompletionStage or separate business executor.

### 13.1 Registration, context, and public types

`GameRuntime.http()` returns the frozen HttpDispatcher. Its `void dispatch(FullHttpRequest, cn.managame.network.http.HttpResponseCallback)` borrows the request; connect it with `HttpServerBuilder.asyncHandler(runtime.http()::dispatch)`. Business objects never implement this transport callback. Builder `httpHandlers(Iterable<?>)` copies/replaces registrations, default empty; `httpContextFactory(HttpContextFactory)` rejects null. A factory is required for endpoints without a Key rule or with a custom context type incompatible with DefaultHttpContext; other endpoints can use the default context. An empty registry returns 404 while open without a factory.

`@HttpHandler` is an inherited runtime type annotation with `int domain() default 0`. `@HttpMethod` is a runtime method annotation with required `String value()` (raw path), `String method() default "GET"`, and `int domain() default 0`. Their routeKey/routeKeyMethod defaults and override rules are in §13.4. Nonzero method Domain overrides class Domain; the effective Domain must be registered. Each supplied target needs @HttpHandler. Methods are public instance non-varargs methods returning void or a reference type for a business result. Primitive return declarations and types implementing Netty HttpObject (including FullHttpResponse) fail build; boxed numbers, records, POJOs, maps, lists, and strings are ordinary result objects. Parameters remain zero to two: at most one exact FullHttpRequest and one compatible Context/HttpContext or custom HttpContext subtype, either order. InvocationContext parameters are rejected because HttpContext is not an InvocationContext. No DTO request binding or future unwrapping is performed. Invalid signatures, incompatible default contexts, missing required factories, duplicate endpoints, or unknown Domains fail build before executor ownership transfers. Handler/Event/Cron retain their void contracts.

Paths start with `/`, have no query/fragment/space/control characters, and match raw case-sensitive text. The verb is an uppercase native token; CONNECT is rejected. Query is excluded without decoding for matching: `/player?id=42` matches `/player`; `/player/` and `/%70layer` do not. No automatic HEAD→GET, OPTIONS, classpath scanning, or streaming method API.

`HttpContext extends Context` adds `FullHttpRequest request()` and **`HttpResultCallback responseCallback()`** to routeDomain()/routeKey(). It defines no businessIdType(), businessId(), or metadata(). Extensible `DefaultHttpContext extends DefaultContext` has only `(int domain, long key, FullHttpRequest, HttpResultCallback)` and requires nonnull request/callback. Application session/authentication fields may live in a custom HttpContext subtype; Runtime does not infer or copy them to EventContext/RouteCallContext. With an ordinary HTTP source, those target contexts use identity 0/0 and empty Metadata; the callback restores the same original HTTP instance. This follows the existing base Context rules rather than introducing HTTP-specific identity propagation.

The functional `HttpContextFactory.create(int domain, long routeKey, FullHttpRequest request, HttpResultCallback callback)` runs on ingress before Route admission, without binding a new Context. Preserve selected Domain/Key, the exact request/result callback, and a compatible context instance. If supplied routeKey is zero (no annotation rule), choose a nonzero Key. The callback is Runtime's completion gate. Do not release the borrowed request; additional retained references are application-owned. The factory can call `callback.onResponse(401, errorObject)` and return null, skipping business execution. No implicit identity trust, header authentication, RouteKeyRegistry lookup, or submitting-context inheritance is provided.

### 13.2 Object completion, encoding, errors, and ownership

`HttpResultCallback` exposes `boolean onResponse(Object result)` (default status 200), `boolean onResponse(int statusCode, Object result)`, and `boolean onFail(Throwable cause)`. Status must be 200..599; invalid status throws IllegalArgumentException and null failure cause throws NullPointerException before claiming completion. A null result is valid and defaults to JSON `null`. Void return does not automatically respond; the method explicitly completes now or later. Object return automatically completes, including null. No Runtime deadline or implicit business retry is added.

Builder `httpResultCodec(HttpResultCodec)` selects a nonnull codec. Default `HttpResultCodec.json()` creates an independent Jackson ObjectWriter using ObjectMapper defaults: UTF-8 JSON and `application/json; charset=UTF-8`, with no automatic module discovery. Supported beans/records/collections follow Jackson configuration, not arbitrary-object serialization guarantees. Unsupported properties, getter exceptions, or encoding failures complete as server failures. A custom codec can supply application serializer/modules; its functional `byte[] encode(Object result) throws Exception` returns an independently owned nonnull byte array, and `String contentType()` defaults to JSON. Build captures and validates nonblank Content-Type using native header validation before starting resources. The codec instance is retained, not deep-copied; encode must be thread-safe across Routes and callback threads, and its returned array must not be mutated after transfer. There is no ObjectMapper/Netty/HTTP-version type in the codec signature.

An atomic gate claims completion before encoding. Only the winning result is encoded, synchronously in its calling thread; losing results are ignored without encoding or resource transfer. The caller retains ordinary object ownership and must keep it stable during encode; use immutable DTOs/snapshots rather than mutable Route state in external callbacks. The encoder holds no object for deferred serialization after return. Automatic result encoding stays inside the method's Route task, without another queue/executor. The adapter creates an owned transport response from encoded bytes and configured media type; actual framing/version/HEAD/204/304 normalization and delivery belong to Network. Numeric status and object result contain no protocol version. Runtime currently serves only HTTP/1.1; additional transport profiles are unimplemented.

Encoding exceptions or null encoded byte arrays report RUNTIME_EXECUTION_ERROR and invoke the Network failure callback once, producing 500/close in its current profile. An asynchronous encoding failure reports the original HTTP context even after the method returned; a factory completion uses selected Domain/Key 0 diagnostics until a compatible context is established. Returning a transport object dynamically through Object or passing a Netty HttpObject/ReferenceCounted value also fails completion; unsupported reference-counted arguments are not consumed and remain caller-owned. Business callbacks do not accept or consume FullHttpResponse/ByteBuf. Completion boolean reflects first transport completion acceptance, not serialization success, peer receipt, or persistence. A disconnected pending winner may still encode before transport rejects/discards the resulting bytes; duplicate results do not encode.

| Boundary | HTTP outcome | Runtime/business behavior |
| --- | --- | --- |
| Closed Runtime | 503 before lookup | Method not executed |
| Non-origin target or fragment | 400 | Method not executed |
| Missing path/wrong verb | 404 / 405 with sorted Allow | Factory/method not executed |
| Invalid extraction/factory input or zero Key | 400 | Method not executed; invalid extraction skips factory |
| Factory completes explicitly | Encoded object response or failure | Method not executed |
| Incompatible factory result or unexpected extraction/factory exception | onFail; Network 500/close | RUNTIME_EXECUTION_ERROR; selected Domain/Key 0 |
| Overload/executor closure or close racing submission | 503 | Method not executed; retained request released |
| Method throws | onFail unless already completed | RUNTIME_EXECUTION_ERROR; no retry |
| Winning result encode fails | Network failure callback | RUNTIME_EXECUTION_ERROR; no retry/rollback |
| Object return is null | 200, JSON null by default | Normal completion |
| Void return | Pending until explicit completion/closure | No automatic result |

Null dispatcher arguments throw before request retain or completion. RuntimeErrorHandler exceptions follow isolated logging. Business failure results can return application DTOs/status without framework errors. Network transport normalization remains separate from the codec. Error responses expose no exception details.

Runtime retains the request before Route submission and releases after business invocation and automatic result encoding, or immediately on rejection. The same Route executor/context path as Handler/Event/call supplies same Domain/Key serialization and nested inlining. A later call callback restores the original HttpContext but does not reacquire a request reference. Copy request data before return or own/release an independent retained reference across every async/admission-failure path. Disconnect does not cancel admitted business work. Runtime.close rejects new entry, lets executors drain admitted work, and does not close HttpServer or await deferred completions. A callback admission failure can leave a response unfinished; arrange in-flight completion before service shutdown.

### 13.3 Example, compatibility, and validation

[RuntimeHttpExample](../../game-example/src/main/java/cn/managame/example/runtime/RuntimeHttpExample.java) returns an application EchoResult record for POST `/echo` and submits PlayerResult through a cross-Route callback for GET `/lookup`. POST body field playerId is the class Key default; GET query field lookupId overrides it. The example uses the default HttpContext without a factory and reads routeKey(); these Keys are routing input and do not establish authenticated identity. Business methods construct no FullHttpResponse or HTTP version. The [execution test](../../game-example/src/test/java/cn/managame/example/runtime/RuntimeHttpExampleTest.java) verifies UTF-8 DTO results over real HttpServer.

Sources: [public HTTP package](../../game-runtime/src/main/java/cn/managame/runtime/http), [RuntimeCompiler](../../game-runtime/src/main/java/cn/managame/runtime/internal/RuntimeCompiler.java), [RuntimeHttp](../../game-runtime/src/main/java/cn/managame/runtime/internal/RuntimeHttp.java). Tests: [RuntimeHttpTest](../../game-runtime/src/test/java/cn/managame/runtime/http/RuntimeHttpTest.java), [HttpRouteKeyTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpRouteKeyTest.java), [HttpResultTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpResultTest.java). They cover registration (including rejection of InvocationContext parameters), factories, HTTP-originated default Event/call identity, exact custom HTTP context restoration, shared Route ordering, query/body rules, ownership, deferred completion, DTO/null/custom-codec encoding, completion races, and encoding failure. Run root `mvn clean verify` for dependencies and `mvn -pl game-runtime -am test` for focused validation. Existing RPC tests/example referencing removed APIs currently block root verification; HTTP examples are separately compiled/run. Production capacity, every disconnection race, and additional HTTP versions remain unverified/unimplemented respectively.

Runtime depends on game-core, game-network, and Jackson Databind, with no new artifact/RPC dependency. Custom GameRuntime implementations implement http(). Existing no-HTTP builders remain usable. HttpContextFactory now accepts selected long routeKey as its second argument and HttpResultCallback as its fourth; earlier three-argument/network-callback factories must migrate. DefaultHttpContext identity/Metadata constructor arguments and HTTP businessIdType()/businessId()/metadata() access are removed; use Route accessors or explicitly defined application context fields. HTTP methods taking InvocationContext must switch to Context/HttpContext or a custom HttpContext subtype. Ordinary InvocationContext propagation remains unchanged. Replace FullHttpResponse-returning methods with DTO/void and callback responses with business objects. This explicit contract change keeps response values independent of HTTP versions rather than retaining an incompatible native response API.

### 13.4 RouteKey field and method rules

Both @HttpHandler and @HttpMethod add `String routeKey() default ""` and `String routeKeyMethod() default ""`. Nonempty method-level configuration replaces the entire class rule, including when switching from field to method or vice versa; both empty inherits. Field and method cannot both be nonempty at either level; whitespace-only values fail build. No implicit field or Java getter-name inference is performed. If neither level has a rule, the context factory supplies the Key; otherwise extraction runs first and the optional factory must preserve it. Without a factory, DefaultHttpContext supplies Route, request, and result callback without identity/Metadata. Custom HttpContext subtype requirements still require a compatible factory.

For `routeKey = "playerId"`, GET uses Netty QueryStringDecoder UTF-8 query decoding and requires exactly one value for the exact case-sensitive name; duplicates after decoding are rejected. Values are optional minus plus ASCII digits, parseable as long and nonzero; plus signs, blanks, fractions, exponent notation, and overflow reject. Other methods use a top-level property of one strict UTF-8 JSON object in the readable ByteBuf region, never query fallback. Integer number tokens or decimal integer strings are accepted. Floating-point/exponent tokens, null/boolean/array/object fields, missing fields, duplicate JSON names anywhere, malformed JSON, extra trailing values, invalid UTF-8, overflow, and zero return 400. Property names are literal: dots are not nested path expressions. Content-Type does not select the source or parser; configured non-GET field rules expect JSON regardless of that header. Applications choose media policy in their pipeline.

The package-private HttpRouteKey uses Jackson Core's streaming JsonFactory with strict duplicate detection and charset detection disabled (UTF-8). It validates the full object before accepting the Key, uses a non-releasing ByteBufInputStream over duplicate() without modifying caller indices/refCnt, and does not deserialize a business DTO. Jackson 2.21.3 defaults limit depth to 1000, number length to 1000, string length to 20,000,000, and field-name length to 50,000; no extra total document/token limit is set. Constraint violations return 400; Network still bounds the complete request body and URI. Query decoding uses no additional parameter-count cutoff within Network's URI limit. No Runtime business thread or second queue is created for extraction.

`routeKeyMethod = "playerKey"` binds exactly a public instance `long playerKey(FullHttpRequest request)` or Long-returning equivalent on the registered Handler object. Compile its MethodHandle once during build; missing/private/static, wrong arguments/return, or varargs fail build. No zero-argument getter or arbitrary reflection expression is inferred. The method runs on ingress before factory/admission, borrows the request, must not access Route-owned state, and may serve concurrent requests; its object fields gain no serialization. IllegalArgumentException maps to 400; other exceptions or null Long are diagnosed as RUNTIME_EXECUTION_ERROR and call onFail. A zero return maps to 400. Methods needing request data later must acquire independent ownership.

For example, `@HttpHandler(domain=1, routeKey="playerId")` plus `@HttpMethod(value="/guild", method="POST", routeKey="guildId")` selects guildId from the body; the GET version selects guildId from query. A custom `routeKeyMethod="playerKey"` replaces the class field rule and calls the declared extractor. Failure never retries another selector or lets a factory change the selected Key. [HttpRouteKeyTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpRouteKeyTest.java) tests both override directions, source isolation, full JSON validation, malformed UTF-8, exact 64-bit bounds, preserved buffer indices/refCnt, factory Key preservation, and extraction/registration failures. The updated runnable [RuntimeHttpExample](../../game-example/src/main/java/cn/managame/example/runtime/RuntimeHttpExample.java) exercises body/query extraction over a real HttpServer.
