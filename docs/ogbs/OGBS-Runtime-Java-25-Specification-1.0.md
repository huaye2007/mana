# OGBS Runtime Java 25 Development Specification 1.0

**[English](OGBS-Runtime-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md)

Document type: **Java Development Specification**. Standard: [Runtime Specification](OGBS-Runtime-1.0.md).

This document defines public Java APIs, defaults, exceptions, threads/resources, extensions, and validation. Implementations satisfy both specifications, including behavior, not signatures alone. Fix implementation deviations; design changes update both layers. Pending/unverified capabilities are not completed features.

Status: current repository implementation contract. Semantics: [OGBS Runtime](OGBS-Runtime-1.0.md). Shared types: [Core](OGBS-Core-1.0.md).

<a id="1-模块与主-api"></a>

## 1. Module and main API

Maven: `cn.managame:game-runtime:1.0.0-SNAPSHOT`. Entry package: `cn.managame.runtime`, with responsibility-based subpackages. Depends on `game-core`; requires Java 25.

Signature excerpts below; source defines the types.

```java
public interface GameRuntime extends AutoCloseable {
    void dispatch(HandlerContext context);
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

No separate start, dynamic registration, or public arbitrary Runnable dispatch. Successful build is usable; network callbacks need integration-created HandlerContext and dispatch.

Public packages:

| Package | Contents |
| --- | --- |
| cn.managame.runtime | GameRuntime, GameRuntimeBuilder |
| cn.managame.runtime.context | Context hierarchy, defaults, read-only Contexts |
| cn.managame.runtime.route | Domain, Key binding/extraction/registry, RouteCallback |
| cn.managame.runtime.executor | Executor SPI, bindings, official implementations |
| cn.managame.runtime.protocol | Descriptors, registration, request/response association |
| cn.managame.runtime.handler | Handler annotations |
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
    .eventHandlers(Iterable<?>)
    .cronHandlers(Iterable<?>)
    .errorHandler(RuntimeErrorHandler)
    .cronZone(ZoneId)
    .build();
```

List setters replace previous configuration and copy each Iterable, without accumulation. Defaults: empty lists, UTC Cron zone, System.Logger error handler.

`RouteDomain.of(int id, String name)` requires id>0 and nonnull name. `RouteExecutorBinding.of(executor, int... domains)` needs at least one Domain. Every registered Domain binds exactly one Executor; unknown Domains reject.

Build parses annotations and compiles MethodHandles, then freezes registries. No classpath scanning; callers provide instances. Annotated methods must be public instance void methods without varargs. Invalid configuration generally throws IllegalArgumentException; null may throw NullPointerException.

Failed build does not close supplied Executors; successful Runtime owns their closure. Independently managed Runtimes must not share a closable Executor without explicit external-ownership adaptation.

<a id="21-构建阶段应完成什么"></a>

### 2.1 Required build work

Do not defer parsing until the first message. Before returning, compile Domain/Executor bindings, protocols/response relations, Key extractors, Handler/Event/Cron methods, and expressions. Reject duplicate/unresolvable definitions at startup, preventing arrival order from choosing a Handler.

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
