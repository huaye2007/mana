# game-runtime

**[English](README.md)** | [简体中文](README.zh-CN.md)

<a id="规范文档"></a>

## Specifications

| Specification (language-independent) | Java Development Specification |
| --- | --- |
| [OGBS Runtime Specification](../docs/ogbs/OGBS-Runtime-1.0.md) | [OGBS Runtime Java Development Specification](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md) |

Component behavior and Java implementation are maintained separately in these specifications. This document is the usage entry point.

Java 25 business runtime. Maven coordinates: `cn.managame:game-runtime`; internal functionality is organized by package.

<a id="目录与职责"></a>

<a id="handler-entry"></a>

## Handler entry

For external ingress, configure `builder.handlerContextFactory((domain, command, requestId, connection, metadata, message) -> ...)` and call `runtime.dispatch(connection, command, requestId, metadata, message)`. Runtime resolves Domain from @Handler/@HandlerMethod first. The application factory obtains authenticated identity from connection attributes or an external Map and selects routeKey/businessIdType/businessId by that Domain; no framework identity store or fixed role rule is required. The factory runs once before admission and must preserve Domain/correlation/message/connection/Metadata. Existing explicit parameters bypass it. See the [policy contract and example](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#handler-context-factory) and [two-Domain Map tests](src/test/java/cn/managame/runtime/HandlerContextFactoryTest.java).

For business work, call `runtime.dispatch(connection, routeKey, businessIdType, businessId, message)`: the caller supplies Key and trusted identity; Runtime selects Domain from the exact message Handler, constructs DefaultClientHandlerContext with empty Metadata, and uses its existing RouteExecutor. No separate Route/Context construction or message extraction is required. Anonymous work can omit identity (defaults 0/0); explicit-context dispatch supports Metadata/custom fields. Read the borrowed connection through `context.connection()`.

The normal Handler signature is `handle(ClientHandlerContext context, MyRequest request)`: read business identity through context.businessId() and, when needed, context.businessIdType(). No RoleId/GuildId/RoomId wrapper or argument registration is required. Integration owns authentication and category validation; Context access does not infer identity from Domain, Key or protocol fields. Registered application arguments remain an optional HandlerArgumentBinding extension. Its resolvers run once on the submitting thread before admission, must be thread-safe, and cannot access Route-owned mutable state; failures reject before queueing. See the [complete argument contract](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#handler-arguments) and [tests](src/test/java/cn/managame/runtime/HandlerArgumentTest.java).

For exceptional protocol-based routing, configure `@HandlerMethod(routeKey="userId")` or `routeKeyMethod="getUserId"` and call `runtime.dispatch(connection, businessIdType, businessId, message)` (omit identity for anonymous extraction only without a context factory). Class-level @Handler rules are defaults, replaced by nonempty method rules. The supplied-Key overload never invokes these rules. Member access compiles once; missing/invalid rules and zero Keys reject. See [Java contract and boundaries](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#automatic-handler-dispatch), [framework tests](src/test/java/cn/managame/runtime/HandlerDispatchTest.java), and the [demo](../game-demo/README.md#handler-dispatch).

A Handler answers its client request in one of two ways: it returns the message's registered response, and Runtime frames it through the `clientResultCodec(...)` you configure and writes it on that request's connection; or it returns void and writes on `context.connection()` itself, using `context.command()` and `context.requestId()` to correlate — from a cross-Route callback, for example, which has restored the original context. There is no third mechanism and no framework completion object in a Handler signature: the context describes the request, nothing more. Runtime sends at most once per dispatch, right after the method returns or throws, so there is no completion race. A null return answers nothing, and an exception from a returning Handler is reported and answered with a framework error code only, never with the exception. Returning a response requires a registered response pair, a ClientHandlerContext and a codec, all checked at build. See the [response contract](../docs/ogbs/OGBS-Runtime-1.0.md#client-response-contract), the [Java API](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#client-responses) and [tests](src/test/java/cn/managame/runtime/ClientResponseTest.java).

Client messages use ClientHandlerContext (borrowed Connection, inbound command and request ID); RPC messages use RpcHandlerContext (source node/Slot, command and request ID). Both extend HandlerContext and expose businessId directly. A required context subtype mismatch rejects before admission. HTTP retains its separate HttpContext. No unified send API or automatic RPC-to-Runtime adapter is provided. See the [context contract](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#transport-handler-contexts) and [transport tests](src/test/java/cn/managame/runtime/TransportContextTest.java).

## Layout and responsibilities

```text
cn.managame.runtime
├── GameRuntime / GameRuntimeBuilder  Public entry points and configuration
├── context                          Context hierarchy, defaults, read-only Contexts
├── route                            Domain, key extraction/registration, RouteCallback
├── executor                         RouteExecutor SPI, bindings, platform/virtual threads
├── protocol                         Protocol descriptions, registration, Req → Res mapping
├── handler                          Handler annotations
├── http                             HTTP annotations, HTTP contexts, Route dispatcher
├── event                            Local Event, EventBus, annotations
├── timer                            One-shot Timer, Cron annotations, management interfaces
├── time                             Replaceable business wall clock: GameTime
├── error                            Runtime errors, handlers, dispatch exceptions
└── internal                         Registration compilation, context binding, dispatch, scheduling
```

API subpackages contain their respective public types. internal assembles the runtime and is not a supported application interface. GameRuntimeBuilder collects configuration, then delegates validation and compilation to RuntimeCompiler instead of containing the entire runtime implementation. Context binding and Timer/Cron management are separate from business dispatch.

This package reorganization added no Maven publication units. Imports such as `cn.managame.runtime.HandlerContext` must become `cn.managame.runtime.context.HandlerContext`; `import cn.managame.runtime.*` does not import subpackages. Repository examples and tests have been migrated.

<a id="gametime-与调度"></a>

## GameTime and scheduling

```java
import cn.managame.runtime.time.GameTime;
import java.time.*;

GameTime.setClock(Clock.offset(Clock.systemUTC(), Duration.ofDays(1)));
long now = GameTime.currentTimeMillis();
LocalDateTime local = GameTime.now(ZoneId.of("Asia/Shanghai"));
runtime.cron().rescheduleAll();
GameTime.resetClock();
```

GameTime is the process-wide business wall clock, deliberately shared by every Runtime in the JVM: business wall time belongs to the game world, not to an execution instance, and a clock on the Runtime would push a Runtime reference into every model that reads the time. The price is that setClock has no scope — it changes the time for every thread and every Runtime at once, so it belongs to startup or a test harness, never to a request; tests must restore it and guard it with @ResourceLock("GameTime"). setClock/resetClock do not automatically change existing schedules. RuntimeTimer still fires after real elapsed delay. Applications convert absolute business deadlines into Duration and use cancel + schedule when recalculation is needed. A Route whose business is ending drops its timers with `runtime.timer().cancelAll(domain, key)` instead of tracking each TimerRef; it returns how many were still pending, and leaves Cron schedules alone.

Cron computes its next time using current GameTime, then uses RuntimeTimer. Manage entries by declaring class and method name:

```java
runtime.cron().cancel(SystemCron.class, "dailyReset");
runtime.cron().reschedule(SystemCron.class, "dailyReset");
runtime.cron().rescheduleAll();
```

SystemCron is an application-defined class. cancel stops future cycles; reschedule can resume cancelled entries; rescheduleAll includes every registered entry. A method that has started may finish; old-generation tasks cannot overwrite new schedules. Cron computes its next cycle after the current method ends. Exceptions or admission failures do not retry that cycle, but future cycles continue.

<a id="文档与验证"></a>

## Event listener Domain

Every listener of one publication runs on the event's single Route, so a listener may only touch the state that Route owns. `@EventMethod(domain = GUILD_ID)` declares which Domain a listener owns state in, which turns that rule from a convention into a check: listeners of one event type that declare different Domains fail at build, and an event whose `routeDomain()` differs from the declared one is rejected with ROUTE_DOMAIN_MISMATCH before admission — no context, no Route task, no listener runs. Declaring nothing keeps the previous behaviour, but one declaring listener constrains that event type for all of them. To touch another Domain's state, stay on the event's Route and use `runtime.call`; its callback restores the event context. See the [clause](../docs/ogbs/OGBS-Runtime-1.0.md#event-listener-domain) and [tests](src/test/java/cn/managame/runtime/event/EventDomainTest.java).

## Observing Handlers

`builder.handlerObserver(...)` registers one observer that is notified on the request's Route before a Handler method and exactly once after it, as completion or failure, with the method's own monotonic duration. It is where per-Handler latency and counts come from: `RuntimeStats` has no per-Handler dimension and `RuntimeErrorHandler` sees only failures. It cannot intervene — it cannot skip the method, change its result, or turn its own exception into the method's, and a callback that throws is reported and ignored. It runs where the business runs, so it must be thread-safe and must not block. Only `@HandlerMethod` dispatch is observed; events, timers, Cron, cross-Route computations and HTTP methods are not. See the [clause](../docs/ogbs/OGBS-Runtime-1.0.md#handler-observation), the [Java API](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#handler-observation) and [tests](src/test/java/cn/managame/runtime/HandlerObserverTest.java).

## Static event publication

Business code can call `Events.publish(event)` using `cn.managame.runtime.event.Events`. Inside Runtime execution, it uses the current owning Runtime; outside, bootstrap must have called `Events.bind(runtime)`. The container does not do this for you: `@EnableGameRuntime` builds the Runtime but leaves binding to the application, since a bean factory runs per context while the binding lasts as long as the process. [GameDemo](../game-demo/src/main/java/cn/managame/demo/GameDemo.java) binds right after its context starts. No owner/default throws IllegalStateException. A conflicting default binding is rejected; binding the same instance is idempotent. There is no unbind: a process binds once at startup and the binding lasts as long as the process, so `Runtime.close` leaves it in place and publishing afterwards fails with RUNTIME_CLOSED rather than reporting a missing configuration. That is also what guarantees a publication already under way cannot be redirected to a replacement. Existing `runtime.eventBus().publish(event)` remains available. Events still use the configured RouteExecutor, with unchanged ordering, context inheritance, admission errors, and closure behavior. See [selection and lifecycle details](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#static-event-publication), [framework tests](src/test/java/cn/managame/runtime/event/EventsTest.java), and the [demo](../game-demo/README.md#publishing-a-runtime-event).

## Virtual-thread Route queues

Each Route with work has a lock-free mailbox (a concurrent queue plus a count of queued and running tasks) and one virtual-thread consumer; the mailbox is removed as soon as its last task finishes, so idle Routes cost nothing and there is no idle cache. Work is serialized by complete Domain/Key without an executor-wide monitor or per-task map locking. Use `new VirtualThreadRouteExecutor(capacity, routeCapacity)` to change the total and per-Route limits. See [the complete defaults and lifecycle](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#route-mailbox-lifecycle) and [behavior tests](src/test/java/cn/managame/runtime/executor/VirtualThreadRouteExecutorTest.java).

## Documentation and validation

- [OGBS Runtime Specification](../docs/ogbs/OGBS-Runtime-1.0.md)
- [Java 25 Development Specification](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md)
- [GameTime](src/main/java/cn/managame/runtime/time/GameTime.java)
- [CronScheduler](src/main/java/cn/managame/runtime/timer/CronScheduler.java)
- A complete RPC / Runtime integration example is not implemented; current integration examples are in the Java specification.

Run `mvn -pl game-runtime -am test` from the root. After package changes, run `mvn clean verify` for full integration validation.

<a id="runtime-http"></a>

## HTTP business entry

`cn.managame.runtime.http` supplies `@HttpHandler`, `@HttpMethod`, `HttpRequestMethod`, `HttpContext`, `DefaultHttpContext`, `HttpContextFactory`, `HttpResultCallback`, `HttpResultCodec`, and `HttpDispatcher` in the game-runtime artifact. HttpContext extends the base Context with Route, request, and result callback; it has no business identity/Metadata fields. It depends on game-network and uses the same Domain/RouteKey executors as Handler/Event/call.

Register instances with `httpHandlers(...)`. HttpMethod.method uses HttpRequestMethod and defaults to POST; select GET explicitly with `method=HttpRequestMethod.GET`. Set `routeKey="playerId"` on @HttpHandler/@HttpMethod to select a GET query field or a top-level JSON body field for other methods; method configuration overrides the class rule. Alternatively set routeKeyMethod to a Handler extraction method. An optional four-argument `httpContextFactory(domain, key, request, callback)` preserves the selected Key and may add application-specific HTTP context fields; without a rule, the factory is required to select Key. Connect `HttpServer.builder().asyncHandler(runtime.http()::dispatch)`. A public method returns a business DTO/object or void; deferred completion uses `context.responseCallback().onResponse(dto)`. Runtime encodes JSON by default and creates the transport response internally, without an HTTP version in the business result. `httpResultCodec(...)` customizes result encoding. Paths match raw method/path exactly; DTO/String request binding is supported; player-ID inference is not provided. The request is borrowed until method return; deferred response completion does not extend its lifetime.

The plain-Spring [game-demo service integration](../game-demo/README.md#demo-runtime-services) combines HTTP object results, a one-shot TimerRef Bean and annotated cron, with real execution and closure tests.

Run [RuntimeHttpExample](../game-demo/src/main/java/cn/managame/demo/examples/runtime/RuntimeHttpExample.java) for annotated echo and a deferred cross-Route response. See [HTTP semantics](../docs/ogbs/OGBS-Runtime-1.0.md#runtime-http-profile) and [Java API, failures, and ownership](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#runtime-http-api).

Runtime also accepts explicit Metadata on client ingress and annotation-Domain RPC convenience dispatch. External async callbacks can restore their source Route with runtime.callback(...). Graceful shutdown uses shutdown(), awaitTermination(Duration), then close(); stats() supplies approximate counters. HTTP can bind DTO/String inputs, with optional Contexts.current(HttpContext.class). See [request binding and lifecycle](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#135-request-binding) and optional [game-spring](../game-spring/README.md).
