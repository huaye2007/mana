# OGBS Runtime Java 25 Development Specification 1.0

**[English](OGBS-Runtime-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md)

Document type: **Java Development Specification**. Standard: [Runtime Specification](OGBS-Runtime-1.0.md), which defines the behavior; this document defines the Java API, defaults, exceptions, threads and validation. Shared types: [Core](OGBS-Core-1.0.md).

<a id="1-模块与主-api"></a>

## 1. Module and main API

Maven `cn.managame:game-runtime:1.0.0-SNAPSHOT`, Java 25. Depends on game-core, game-network (HTTP entry), Jackson Databind 2.21.3 (HTTP results and JSON Key extraction) ; timers use the Netty HashedWheelTimer that game-network brings; no RPC dependency.

```java
public interface GameRuntime extends AutoCloseable {
    void dispatch(HandlerContext context);
    void dispatch(Connection connection, long routeKey, int businessIdType, long businessId, Metadata metadata, Object message);
    default void dispatch(Connection connection, Object message);
    default void dispatch(Connection connection, Metadata metadata, Object message);
    void dispatch(Connection connection, int command, int requestId, Metadata metadata, Object message);
    void dispatchRpc(int sourceNodeId, int sourceSlotId, int command, int requestId, long routeKey,
                     int businessIdType, long businessId, Metadata metadata, Object message);
    <T> void call(int routeDomain, long routeKey, Supplier<T> action, RouteCallback<T> callback);
    <T> CapturedRouteCallback<T> callback(RouteCallback<T> callback);
    HttpDispatcher http();
    EventBus eventBus();
    RuntimeTimer timer();
    CronScheduler cron();
    ProtocolRegistry protocols();
    RouteKeyRegistry routeKeys();
    void shutdown();
    boolean awaitTermination(Duration timeout) throws InterruptedException;
    RuntimeStats stats();
    void close();
}
public interface RouteCallback<T> {
    void onSuccess(T result);
    void onFail(int errorCode);
    default void onFail(int errorCode, Throwable cause) { onFail(errorCode); }
}
public interface CapturedRouteCallback<T> extends RouteCallback<T> { boolean cancel(); }

public interface ClientResultCodec {
    Object encode(ClientHandlerContext context, Object result) throws Exception;
    Object encodeError(ClientHandlerContext context, int errorCode) throws Exception;
}
```

A built Runtime is usable at once; there is no start, dynamic registration or arbitrary Runnable dispatch. The usual business entry is `dispatch(connection, routeKey, businessIdType, businessId, metadata, message)`; Domain comes from the Handler annotation (§6.2), so a packet decoder never carries it. With a `handlerContextFactory`, `dispatch(connection, command, requestId, metadata, message)` selects Key and identity per Domain (§6.4) and carries the correlation a response needs (§6.5); its two shorter forms are defaults that pass correlation 0 and, with no factory, extract the Key from the message. HTTP connects through `asyncHandler(runtime.http()::dispatch)` (§13).

| Package | Contents |
| --- | --- |
| cn.managame.runtime | GameRuntime, GameRuntimeBuilder |
| .context | Context hierarchy, default implementations, Contexts |
| .route | RouteDomain, RouteKeyBinding/Extractor/Registry, RouteCallback |
| .executor | RouteExecutor SPI, bindings, official executors |
| .protocol | Protocol descriptors, registration, request/response pairs |
| .handler | Handler annotations, HandlerArgumentBinding, HandlerContextFactory, ClientResultCodec, HandlerObserver |
| .http | HTTP annotations, HttpDispatcher, HttpContext, HttpContextFactory, HttpResultCallback, codecs |
| .event / .timer / .time / .error / .diagnostic | Events, timers and Cron, GameTime, errors, RuntimeStats and DomainStats |
| .internal | Compilation, scheduling, context binding; not application API |

<a id="2-构建"></a>
<a id="21-构建阶段应完成什么"></a>
<a id="22-注册失败的定位"></a>

## 2. Build

```java
GameRuntimeBuilder.builder()
    .routeDomains(Iterable<RouteDomain>)
    .routeExecutors(Iterable<RouteExecutorBinding>)
    .protocols(ProtocolRegistry)
    .routeKeys(Iterable<RouteKeyBinding<?>>)
    .handlers(Iterable<?>)
    .handlerArguments(Iterable<? extends HandlerArgumentBinding<?>>)
    .handlerContextFactory(HandlerContextFactory)
    .clientResultCodec(ClientResultCodec)
    .handlerObserver(HandlerObserver)
    .httpHandlers(Iterable<?>)
    .httpContextFactory(HttpContextFactory)
    .httpResultCodec(HttpResultCodec)
    .httpRequestCodec(HttpRequestCodec)
    .eventHandlers(Iterable<?>)
    .cronHandlers(Iterable<?>)
    .errorHandler(RuntimeErrorHandler)
    .cronZone(ZoneId)
    .build();
```

List setters copy and **replace** earlier values (`handlers(A)` then `handlers(B)` registers only B). Defaults: empty lists, UTC Cron zone, System.Logger error handler. `RouteDomain.of(id, name)` needs id > 0; every Domain binds exactly one executor through `RouteExecutorBinding.of(executor, domains...)`.

build compiles everything before returning: Domain/executor bindings, protocols and response pairs, Key extractors, Handler/Event/Cron/HTTP methods (MethodHandles) and Cron expressions. There is no classpath scanning; callers pass instances. Event/Cron methods are public, non-static, non-varargs and return void; Handler and HTTP methods may also return a business object. Duplicates and unresolvable definitions throw IllegalArgumentException (null arguments NullPointerException):

| Check | Typical error |
| --- | --- |
| Domain/executor | Duplicate ID, unknown Domain, missing binding |
| Protocol | Duplicate message class or type+command; response pair to an unregistered or wrong-direction type |
| Handler | Unregistered message, two methods for one message, unknown Domain, context parameter outside the HandlerContext hierarchy |
| Handler response (§6.5) | Return type that is not the message's registered response, a message that registers none, a non-client context, or no `clientResultCodec` |
| Event | Not exactly one Event parameter, an unregistered declared Domain, or listeners of one event type declaring different Domains |
| Cron | Invalid expression or Route, duplicate declaring class + method |

A failed build does not close supplied executors; a successful Runtime owns them and closes each once. Handler instances are shared across Routes: their fields are not serialized, so keep mutable state per Route.

<a id="3-最小接入示例"></a>

## 3. Minimal example

```java
public record Ping(long playerId) {}

@Handler(domain = 1)
public final class PingHandler {
    @HandlerMethod public void onPing(HandlerContext context, Ping message) {
        System.out.println(context.routeKey() + ": " + message);
    }
}

try (GameRuntime runtime = GameRuntimeBuilder.builder()
        .routeDomains(List.of(RouteDomain.of(1, "player")))
        .routeExecutors(List.of(RouteExecutorBinding.of(RouteExecutors.virtualThreads(), 1)))
        .protocols(ProtocolRegistry.of(ProtocolDescriptor.notify(1001, Ping.class)))
        .handlers(List.of(new PingHandler()))
        .build()) {
    runtime.dispatch(connection, 42L, 0, 0L, Metadata.empty(), new Ping(42));
}
```

close does not wait for work; for a graceful stop use shutdown → awaitTermination → close (§14).

<a id="4-context-与作用域"></a>
<a id="41-绑定与恢复的实现约束"></a>

## 4. Context and scope

```java
interface Context { int routeDomain(); long routeKey(); }
interface InvocationContext extends Context { int businessIdType(); long businessId(); Metadata metadata(); }
interface HandlerContext extends InvocationContext { Object message(); }
interface ClientHandlerContext extends HandlerContext { Connection connection(); }
interface RpcHandlerContext extends HandlerContext { int sourceNodeId(); int sourceSlotId(); int command(); int requestId(); }
interface EventContext extends InvocationContext { Event event(); }
interface RouteCallContext extends InvocationContext {}
interface TimerContext extends Context {}
interface HttpContext extends Context { FullHttpRequest request(); HttpResultCallback responseCallback(); }  // §13
```

Each has an extensible Default implementation (DefaultHandlerContext, DefaultClientHandlerContext, DefaultRpcHandlerContext, DefaultEventContext, DefaultRouteCallContext, DefaultTimerContext, DefaultHttpContext); short constructors use identity 0/0 and empty Metadata. businessIdType is 0..255; message and Metadata are nonnull.

`Contexts.current()` returns the bound context (NoSuchElementException when none), `currentOrNull()` null, `current(Class)` a cast (ClassCastException when wrong). Binding uses Java 25 ScopedValue together with the owning Runtime; only Runtime execution binds, and nested execution restores the outer value. A submission runs inline when the current Runtime, Domain and Key all match; otherwise it goes through the bound executor. A callback restores the exact original context object; a target computation of `call` gets a DefaultRouteCallContext carrying only invocation identity and Metadata.

<a id="transport-handler-contexts"></a>

### 4.2 Client and RPC Handler contexts

ClientHandlerContext is client ingress over any game-network Connection (including WebSocket); it implies no authentication. It carries the inbound `command()` and `requestId()` so its Handler can answer (§6.5), with the same meaning as on the RPC side: requestId 0 = no response expected. Every Connection dispatch overload builds a DefaultClientHandlerContext, with a possibly null connection that Runtime neither inspects nor closes. RpcHandlerContext is inter-service RPC ingress (Call and Notify; requestId 0 = Notify) and exposes no connection. DefaultRpcHandlerContext requires nonzero sourceNodeId and command and sourceSlotId 0..254. The RPC adapter decodes the borrowed body before its callback returns and must pick a nonzero business Key; the context owns no RPC buffer.

A Handler method may take HandlerContext (transport-independent), ClientHandlerContext or RpcHandlerContext; an incompatible actual context is rejected with HANDLER_CONTEXT_MISMATCH (3003) before argument resolution and admission. Each message class still has one Handler, so a message used over several transports takes HandlerContext — and therefore cannot return a response, since returning one tightens the required context to ClientHandlerContext; it may still write on a connection it obtains some other way. Tests: [TransportContextTest](../../game-runtime/src/test/java/cn/managame/runtime/TransportContextTest.java).

<a id="5-protocol-与-routekey-注册"></a>

## 5. Protocol and RouteKey registration

```java
record ProtocolDescriptor(Type type, int command, Class<?> messageType, Class<?> responseType) {
    enum Type { REQUEST, RESPONSE, NOTIFY }
    static ProtocolDescriptor request(int command, Class<?> type);                        // no response
    static ProtocolDescriptor request(int command, Class<?> type, Class<?> responseType);
    static ProtocolDescriptor response(int command, Class<?> type);
    static ProtocolDescriptor notify(int command, Class<?> type);
}
final class ProtocolRegistry {
    static ProtocolRegistry of(ProtocolDescriptor... descriptors);   // also of(Collection), empty()
    ProtocolDescriptor get(ProtocolDescriptor.Type type, int command);
    ProtocolDescriptor get(Class<?> messageType);
    List<ProtocolDescriptor> descriptors();
}

RouteKeyBinding.of(type, extractor); RouteKeyBinding.ofField(type, "field"); RouteKeyBinding.ofMethod(type, "method");
long RouteKeyRegistry.getRouteKey(Object message);   // 0 when no binding
```

`ProtocolRegistry.of` validates when it is built: descriptors are unique by type+command and by exact message class, and a request's responseType must be registered as a RESPONSE (IllegalArgumentException otherwise). Each type has its own command space, so a response may use its request's command or a different one, e.g. `request(1003, LoginReq.class, LoginRes.class)` with `response(2003, LoginRes.class)`. command uses all 32 bits. Only a REQUEST may carry a responseType. Lookups return null on a miss. Pass the registry with `builder.protocols(registry)`; merge several with `ProtocolRegistry.of(...)` over their `descriptors()`.

<a id="6-handler"></a>

## 6. Handler

Handler objects carry `@Handler(domain = …)` (inherited). A nonzero `@HandlerMethod(domain = …)` overrides the class Domain. A method takes exactly one registered REQUEST/NOTIFY message, at most one context parameter (Context, InvocationContext, HandlerContext or a subtype) and any explicitly bound custom arguments (§6.3), in any order. It returns void, or the message's registered response type (§6.5). Messages match by exact class, one Handler each.

<a id="61-方法签名判定示例"></a>

| Form | Valid |
| --- | --- |
| `void handle(M m)`, `void handle(HandlerContext c, M m)`, `void handle(M m, CustomContext c)` | Yes |
| `void handle(Extra x, M m)` | Yes, with a binding for Extra |
| `void handle(Connection c, M m)` | No; read it from ClientHandlerContext |
| `R handle(ClientHandlerContext c, M m)` where M registers response R | Yes (§6.5) |
| `R handle(M m)` where R is not M's registered response, `R handle(RpcHandlerContext c, M m)` | No (§6.5) |
| `int handle(M m)`, `static void handle(M m)`, `void handle(M a, M b)`, `void handle(TimerContext c, M m)`, `void handle(M... m)` | No |

Precondition and admission failures throw RuntimeDispatchException with a Core code (no stack trace is captured, so an overload rejection costs one allocation; toString includes `FrameworkErrorCodes.describe`); once execution starts, exceptions go to RuntimeErrorHandler (also when inlined), never to the caller.

<a id="automatic-handler-dispatch"></a>

### 6.2 Supplied-Key dispatch and message extraction

`dispatch(connection, routeKey, businessIdType, businessId, metadata, message)` uses the Handler's annotation Domain and the supplied nonzero Key without reading the message, and builds a DefaultClientHandlerContext. businessIdType outside 0..255 throws IllegalArgumentException. There is no three- or four-argument abbreviation of it: `dispatch(connection, 99L, message)` and `dispatch(connection, 1, 10001L, message)` would be a Route Key and a business identity in the same shape, which no reader can tell apart, so the long form is the only one and identity 0/0 is written out. A caller that finds it long is usually better served by building the context, which names every value.

For messages whose route lives in the message, `dispatch(connection, message)` without a context factory uses the exact-class Key binding — from `routeKeys(...)` or from `@Handler`/`@HandlerMethod` `routeKey = "field"` / `routeKeyMethod = "method"`. A method-level rule replaces the class rule; giving both names, a blank name, or an annotation rule plus a builder binding for the same message fails build. Missing binding or Key 0 → INVALID_ROUTE_KEY (3005); no Handler → HANDLER_NOT_FOUND (3002); closed → RUNTIME_CLOSED (3001). Extractor exceptions propagate on the submitting thread before admission. These entries never inherit identity or Metadata from an outer context.

`ofField`/`ofMethod` compile once at build: fields may be private or inherited (nearest wins); methods are public, non-static and argument-free; values must be byte/short/int/long or their wrappers. A null boxed value throws NullPointerException; a checked exception from the getter becomes IllegalStateException. Tests: [HandlerDispatchTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerDispatchTest.java).

```java
runtime.dispatch(connection, 99L, ROLE_TYPE, roleId, Metadata.empty(), loginReq);   // caller chose both
// with @HandlerMethod(routeKey = "userId") and no context factory:
runtime.dispatch(connection, loginReq);
```

<a id="handler-arguments"></a>

### 6.3 Business identity and optional Handler arguments

Business identity is read from the context: `context.businessId()` / `businessIdType()`; no wrapper type is needed. Key and identity are independent; authentication belongs to the integration.

`HandlerArgumentBinding.of(Type.class, context -> value)` registers an extra parameter type (exact class; not primitive, not a Context, not a protocol). Duplicate bindings, unbound parameter types or a type repeated in one method fail build. Resolvers run once per dispatch, in declaration order, on the submitting thread before admission; they must be thread-safe, must not touch Route state, and must return a nonnull value of the type (NullPointerException / ClassCastException otherwise). Their exceptions propagate without submission. Accepted work keeps the resolved values. Bindings apply to HandlerMethod only. Tests: [HandlerArgumentTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerArgumentTest.java); demo: [RoleHandler](../../game-demo/src/main/java/cn/managame/demo/bus/role/RoleHandler.java).

<a id="handler-context-factory"></a>

### 6.4 Domain-aware ingress context policy

```java
@FunctionalInterface
interface HandlerContextFactory {
    ClientHandlerContext create(int domain, int command, int requestId, Connection connection,
                                Metadata metadata, Object message);
}
```

When configured, every `dispatch(connection, …, message)` client overload calls it once, on the submitting thread before admission, with the Handler's effective annotation Domain and the correlation the caller supplied; it does not run any Key extractor. It selects Key and identity per Domain — for example from a Connection attribute or a thread-safe identity map — and may return an application subtype. It must keep the Domain, the correlation and the exact message, connection and Metadata references; a changed Domain → ROUTE_DOMAIN_MISMATCH (3004), replaced objects, a changed command or request id, or an incompatible context → HANDLER_CONTEXT_MISMATCH (3003), Key 0 → INVALID_ROUTE_KEY (3005), null → NullPointerException. Throwing rejects the dispatch (no retry, no error handler). Explicit-Key, explicit-context and explicit-identity overloads bypass the factory and carry correlation 0, so a Handler that answers is reached through this factory or through an explicitly built context. Runtime neither closes nor copies the factory.

```java
builder.handlerContextFactory((domain, command, requestId, connection, metadata, message) -> {
    Identity id = Objects.requireNonNull(identities.get(connection), "Not authenticated");
    return switch (domain) {
        case ROLE_ID -> new DefaultClientHandlerContext(domain, id.roleRouteKey(), ROLE_TYPE, id.roleId(),
                metadata, message, connection, command, requestId);
        default -> throw new IllegalArgumentException("Unsupported domain: " + domain);
    };
});
runtime.dispatch(connection, packet.command(), packet.seq(), Metadata.empty(), decodedMessage);
```

The demo stores an authenticated [GameSession](../../game-demo/src/main/java/cn/managame/demo/network/GameSession.java) on the Connection and lets [GameDomain](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameDomain.java) choose per Domain. Tests: [HandlerContextFactoryTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerContextFactoryTest.java).

<a id="client-responses"></a>

### 6.5 Client responses

`ClientHandlerContext` adds `command()` and `requestId()` to the borrowed `connection()`. `DefaultClientHandlerContext` takes them in its widest constructor; its shorter constructors pass 0, which means "expects no response". Implementing `ClientHandlerContext` directly now requires both accessors. The context stays a description of the request: it carries no completion object, and no Handler parameter supplies one.

A Handler answers in one of two ways:

```java
// Return it: Runtime frames it through the codec and writes it on this request's connection.
@HandlerMethod public LoginRes login(ClientHandlerContext c, LoginReq r) { return new LoginRes(...); }

// Or send it yourself, here from a guild Route callback that has restored c.
@HandlerMethod public void join(ClientHandlerContext c, JoinReq r) {
    runtime.call(GUILD_ID, r.guildId(), () -> guild.join(...), new RouteCallback<GuildInfo>() {
        public void onSuccess(GuildInfo info) { c.connection().write(packet(c.command(), c.requestId(), info)); }
        public void onFail(int errorCode) { c.connection().write(error(c.command(), c.requestId(), errorCode)); }
    });
}
```

Build rules for the first form, all `IllegalArgumentException` from `build()`: the returned type must be exactly the message's registered `responseType`; the message must register one; the context must be assignable to `ClientHandlerContext` (a wider declared context is tightened to it, `RpcHandlerContext` is rejected); and `clientResultCodec` must be configured. A void Handler has no such requirements — it owns whatever it writes.

At dispatch the tightened context type is checked like any other, so a non-client context gives HANDLER_CONTEXT_MISMATCH (3003) before admission. Runtime then sends at most once per dispatch, on the request's Route, right after the method returns or throws: it calls `ClientResultCodec` and writes the framed object with `Connection.write`, and treats only `WriteStatus.ACCEPTED` as written; `INACTIVE`/`NOT_WRITABLE` are not reported. They do mean the message was never submitted and Runtime still owns it ([N-WRITE-03](OGBS-Network-1.0.md)), so it passes the frame to `ReferenceCountUtil.release` — a no-op for an ordinary object, and what keeps a ByteBuf-framing codec from leaking. `ACCEPTED` transfers that share to the transport and Runtime never touches the frame again; a `Connection.write` that throws leaves ownership indeterminate, so Runtime releases nothing and reports instead. A null return writes nothing and is not an error. Missing correlation, a null connection, a codec exception and a null codec result are RUNTIME_EXECUTION_ERROR (3010) on RuntimeErrorHandler; a failed business framing then attempts `encodeError` once, and a failing `encodeError` is only reported. A Handler exception is reported first and then answered with one `encodeError(3010)` framing (RT-DISPATCH-03), so no exception detail is encoded; a void Handler's exception is only reported, since Runtime does not know whether it already answered.

`builder.clientResultCodec(codec)` is required before any Handler returns a response and is otherwise unused. Runtime neither closes nor copies it. Tests: [ClientResponseTest](../../game-runtime/src/test/java/cn/managame/runtime/ClientResponseTest.java); demo codec: [GamePacketResultCodec](../../game-demo/src/main/java/cn/managame/demo/network/GamePacketResultCodec.java).

<a id="handler-observation"></a>

### 6.6 Handler observation

```java
public interface HandlerObserver {
    default void onStart(HandlerContext context) {}
    void onFinish(HandlerContext context, long elapsedNanos);
    void onError(HandlerContext context, long elapsedNanos, Throwable cause);
}
```

`builder.handlerObserver(observer)` registers one; none is registered by default and nothing is measured then. `context.message().getClass()` is the Handler's identity — one Handler per message type — so it is the natural key for per-Handler latency and counts, and its cardinality is the registered protocol table.

All three run on the request's Route inside its Route task, with the exact context the Handler received. `onStart` is followed by exactly one of `onFinish` or `onError`. `elapsedNanos` comes from `System.nanoTime()` around the method handle invocation alone: not admission, not argument resolution, and not the response that a returning Handler produces afterwards. A callback that throws is reported as RUNTIME_EXECUTION_ERROR (3010) and otherwise ignored — the Handler's own result and the error handler's view of it are unchanged (RT-DISPATCH-08). `onError` does not replace `RuntimeErrorHandler`; both receive the same Throwable.

Only `@HandlerMethod` dispatch is observed. Event listeners, timer and Cron actions, cross-Route computations and HTTP methods are not. Admission failures are not observed, and nested same-Route dispatch produces nested, overlapping observations. Runtime neither closes nor copies the observer; it must be thread-safe, since Routes run concurrently. Tests: [HandlerObserverTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerObserverTest.java).

<a id="7-routeexecutor"></a>
<a id="71-两种容量的计算示例"></a>
<a id="72-自定义-executor-的验收边界"></a>

## 7. RouteExecutor

```java
interface RouteExecutor extends AutoCloseable {
    RouteExecuteStatus tryExecute(int domain, long key, Runnable task);   // ACCEPTED, OVERLOADED, CLOSED
    default RouteExecutorStats stats() { return RouteExecutorStats.unreported(); }   // capacity, routeCapacity, activeRoutes
    default void close() {}
}
```

| Executor | Serialization | Capacity |
| --- | --- | --- |
| RouteExecutors.platformThreads(n) / StripedRouteExecutor(n, queue) | Route hashed to one of n single-thread stripes; Routes on a stripe wait for each other | Waiting tasks per stripe (default 65,536), excluding the running one |
| RouteExecutors.virtualThreads() / VirtualThreadRouteExecutor(capacity[, routeCapacity]) | One lock-free mailbox and one virtual-thread consumer per Route with work | Unfinished tasks across all Routes (default 65,536, running included) and tasks waiting per Route behind the running one (default min(capacity, 1024)) |

Both reject without blocking and keep running accepted tasks after close. The per-Route limit stops one flooding Route from taking the shared capacity; still rate-limit connections in the network pipeline. Same-Route inline execution bypasses capacity (recursion still consumes stack). A custom executor must guarantee per-Route mutual exclusion and order, never run rejected tasks, run accepted tasks exactly once, and handle close/submit races.

<a id="route-mailbox-lifecycle"></a>

### 7.3 Virtual-thread mailboxes

A mailbox is in the ConcurrentHashMap keyed by Domain/Key only while its Route has queued or running tasks. It is a lock-free queue plus a count of queued and running tasks: a submitter CAS-increments the count and then enqueues, and the submitter that raises the count from 0 to 1 starts the consumer; the consumer decrements after each task and, on reaching 0, marks the count retired, removes the mailbox and exits. A submitter that sees the retired mark replaces the mailbox. Since the count reaches 0 only after the last task finished, at most one consumer runs a Route's tasks, in submission order; neither enqueue nor dequeue takes a lock, and there is no idle cache — idle Routes cost no memory and Caffeine is no longer needed. One AtomicLong holds the closed bit and all reservations, so admission and close are atomic; after close admitted work drains. Tests: [VirtualThreadRouteExecutorTest](../../game-runtime/src/test/java/cn/managame/runtime/executor/VirtualThreadRouteExecutorTest.java), [RouteExecutorTest](../../game-runtime/src/test/java/cn/managame/runtime/executor/RouteExecutorTest.java).

<a id="8-跨-route-调用与回调"></a>

## 8. Cross-Route calls and callbacks

```java
runtime.call(2, guildId, () -> loadGuildSnapshot(guildId), new RouteCallback<GuildSnapshot>() {
    public void onSuccess(GuildSnapshot snapshot) { /* back on the source Route */ }
    public void onFail(int errorCode) { /* framework error */ }
});
```

call requires this Runtime's current context (otherwise IllegalStateException). The target runs in a DefaultRouteCallContext with the source's identity and Metadata. Target rejection calls onFail with the code; an action exception reports ROUTE_CALL_EXECUTION_ERROR (3008) and calls onFail(3008); a callback exception reports RUNTIME_EXECUTION_ERROR (3009); failure to return to the source reports ROUTE_CALLBACK_DISPATCH_FAILED (3010). Same-Route calls may complete before call returns. There is no Future, timeout or cancellation.

<a id="9-eventbus"></a>

## 9. EventBus

```java
interface Event { int routeDomain(); long routeKey(); }
interface EventBus { void publish(Event event); }
@EventMethod(order = 10) public void onChanged(MyEvent event) { … }
@EventMethod(domain = GUILD_ID) public void onChanged(GuildEvent event) { … }
```

Listeners registered with eventHandlers take one Event subtype, match by exact class and run in ascending order (ties unspecified) on the event's Route in a DefaultEventContext; a listener failure is reported (3009) and the others still run. Publishing from this Runtime inherits identity and Metadata; otherwise 0/0 and empty. Route and admission are validated even without listeners; an event without listeners then returns at once, with no context, no Route task and no RuntimeStats count.

All listeners of one publication share that single Route, so each may touch only the state it owns. `@EventMethod(domain = …)` declares which Domain that is and makes the rule checkable; it adds no routing and no fan-out. The declared Domain must be registered, or build fails. Listeners of one event type that declare different Domains fail at build (`conflicting domains`), since one event carries one Domain. Otherwise the agreed Domain is checked once per publication: an event of that type whose `routeDomain()` differs is rejected with ROUTE_DOMAIN_MISMATCH (3004) before admission, so no context, Route task or listener invocation occurs. A listener that declares nothing keeps today's behaviour and accepts whatever Domain publishes, but one declaring listener constrains the type for all of them. To touch another Domain's state, stay on the event's Route and use `runtime.call`; its callback restores the event context. Tests: [EventDomainTest](../../game-runtime/src/test/java/cn/managame/runtime/event/EventDomainTest.java).

<a id="static-event-publication"></a>

### 9.1 Static publication

`Events.publish(event)` publishes through the Runtime owning the current execution, otherwise through the process default set with `Events.bind(runtime)` at startup; neither → IllegalStateException. Binding a different Runtime while one is bound throws; rebinding the same one is a no-op. There is no unbind: the binding is a startup fact that lasts as long as the process, and `Runtime.close` leaves it in place, so publishing afterwards fails with RUNTIME_CLOSED (3001) instead of IllegalStateException. The binding is the application's to make at process startup: `@EnableGameRuntime` builds the Runtime bean but deliberately does not bind it, because a bean factory runs once per context while the binding lasts as long as the process, so binding there would make a second context in one JVM collide with the first. `Events.resetForTests()` exists for suites that build many Runtimes and is not a lifecycle operation. Tests: [EventsTest](../../game-runtime/src/test/java/cn/managame/runtime/event/EventsTest.java).

<a id="10-gametimetimer-与-cron"></a>

## 10. GameTime, Timer, and Cron

<a id="demo-task-integration"></a>

The [demo](../../game-demo/README.md#demo-runtime-services) collects @Cron beans through CronMethodFilter/CronBeans, schedules a TimerRef bean and serves HTTP through the same Runtime ([DemoServicesTest](../../game-demo/src/test/java/cn/managame/demo/DemoServicesTest.java)).

### 10.1 GameTime

`GameTime.currentTimeMillis()`, `GameTime.now(ZoneId)`, `setClock(Clock)`, `resetClock()`. One process-wide clock in a `static volatile Clock` (default `Clock.systemUTC()`), deliberately not per Runtime (RT-TIME-01). Changing it never reschedules timers or Cron; durations and timeouts stay monotonic. `setClock` has no scope: it applies to every thread and every Runtime in the JVM at once, so it belongs to startup or a test harness, never to a request. A test that replaces the clock must restore it in teardown and must carry `@ResourceLock("GameTime")` so it cannot run beside a test that reads it — see [CronSchedulerTest](../../game-runtime/src/test/java/cn/managame/runtime/timer/CronSchedulerTest.java) and [RuntimeTimerTest](../../game-runtime/src/test/java/cn/managame/runtime/timer/RuntimeTimerTest.java).

<a id="102-一次性-timer"></a>

### 10.2 One-shot Timer

`TimerRef RuntimeTimer.schedule(domain, key, Duration delay, Runnable task)` validates the Route at once (negative delay rejected) and, when due, submits the task to the Route in a fresh DefaultTimerContext. One timing-wheel thread per Runtime (Netty HashedWheelTimer, 10 ms tick, 1024 slots): O(1) schedule and cancel, suited to very many buff, cooldown and expiry timers; resolution is one tick — a timer fires no earlier than its delay and normally within one tick after it. The wheel thread only submits to the Route; business code never runs on it. `TimerRef.cancel()` succeeds only before the timer fires; it cannot retract submitted work.

`int cancelAll(domain, key)` cancels every timer still pending for that complete Route and returns how many it claimed, for cleanup paths that would otherwise track each TimerRef — a logout, a room closing. It accepts an unregistered Domain and a closed Runtime, because that is when cleanup runs, and it skips Cron timers deliberately: a Cron registers its next cycle only after the current one runs, so cancelling its pending timer would stop the schedule for good (RT-TIMER-04). Tracking costs one map entry per Route holding timers and one set entry per timer, both dropped as each timer fires or is cancelled, so quiet Routes hold nothing.

<a id="103-cron-表达式"></a>

### 10.3 Cron expressions

`@Cron(value = "0 */5 * * * ?", domain = 1, routeKey = 42)` on a public, non-static, parameterless void method of an object passed to cronHandlers. cronZone defaults to UTC.

| Item | Rule |
| --- | --- |
| Fields | second minute hour day-of-month month day-of-week (six) |
| Ranges | 0–59, 0–59, 0–23, 1–31, 1–12, 1–7 (1 = Sunday) |
| Syntax | numbers, `*`, lists, ranges, steps; `?` only in the day fields |
| Day fields | at most one of day-of-month and day-of-week may be restricted; the other must be `*` or `?` (as in Quartz) |
| Not supported | names, L, W, #, a year field |
| Horizon | the next occurrence must fall within eight years |

After each run the next occurrence is computed from the later of the current GameTime and the occurrence just planned, so an occurrence is never planned twice even if the timer fires slightly before the business clock reaches it. A rejected or failing run still schedules the next one; missed occurrences are not replayed.

<a id="104-cron-取消与重排"></a>
<a id="105-timer-与-cron-的内部协作"></a>

### 10.4 Cron cancellation and rescheduling

```java
boolean cancel(Class<?> type, String methodName);       // true if it was active
boolean reschedule(Class<?> type, String methodName);   // recompute from current GameTime; false if unknown
void rescheduleAll();
```

The key is the declaring class plus method name. cancel stops future runs; a run already started finishes. reschedule reactivates a cancelled entry. Generations make old timers and queued, unclaimed old runs no-ops. After close, cancel returns false and reschedule throws RuntimeDispatchException(RUNTIME_CLOSED). Rescheduling may revisit an instant; business logic stays idempotent. Tests: [CronSchedulerTest](../../game-runtime/src/test/java/cn/managame/runtime/timer/CronSchedulerTest.java), [CronScheduleTest](../../game-runtime/src/test/java/cn/managame/runtime/internal/CronScheduleTest.java).

<a id="11-错误与关闭"></a>
<a id="111-api-结果与异步错误的区别"></a>
<a id="112-后续维护检查点"></a>

## 11. Errors and closure

```java
interface RuntimeErrorHandler { void onError(RuntimeError error); }
record RuntimeError(int errorCode, Context context, int routeDomain, long routeKey, Throwable cause) {}
int RuntimeDispatchException.errorCode();
```

| Stage | Caller sees | Error handler sees |
| --- | --- | --- |
| dispatch/publish validation or rejection | RuntimeDispatchException | — |
| Handler or listener exception | nothing | RUNTIME_EXECUTION_ERROR |
| call without a current context | IllegalStateException | — |
| call target rejected / action throws / callback throws | onFail(code) / onFail(3008) / — | — / 3008 / 3009 |
| Timer rejected after expiry | nothing | the rejection code |

Error handlers run on the triggering thread; return quickly and do not throw (their exceptions are logged). close is idempotent: it stops timers and closes each executor once without waiting; new dispatch, publish and schedule calls are rejected; registries stay readable.

<a id="12-源码与测试"></a>
<a id="121-易错契约的测试定位"></a>

## 12. Source and tests

Sources: [GameRuntime](../../game-runtime/src/main/java/cn/managame/runtime/GameRuntime.java), [GameRuntimeBuilder](../../game-runtime/src/main/java/cn/managame/runtime/GameRuntimeBuilder.java), [DefaultGameRuntime](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java), [RuntimeCompiler](../../game-runtime/src/main/java/cn/managame/runtime/internal/RuntimeCompiler.java). Key tests in [RuntimeTest](../../game-runtime/src/test/java/cn/managame/runtime/RuntimeTest.java): same-Route inline events and isolation, no inlining across Runtimes, cross-Route call and source restoration, target versus return rejection, fresh Timer context, shared executor closed once. [ClientResponseTest](../../game-runtime/src/test/java/cn/managame/runtime/ClientResponseTest.java) covers §6.5: a returned response framed with its correlation, a void Handler answering through the connection itself while Runtime frames nothing, null silence, handler exceptions answered with a code only, missing correlation or connection, codec failure and its single fallback, refused writes, and the build rules. Run `mvn -pl game-runtime -am test`.

<a id="runtime-http-api"></a>

## 13. HTTP annotations and Route integration

Implements [RT-HTTP-01–08](OGBS-Runtime-1.0.md#runtime-http-profile). Network's [HttpResponseCallback](OGBS-Network-Java-25-Specification-1.0.md#http-async-response) is the transport boundary; business code completes with HttpResultCallback and ordinary objects.

### 13.1 Registration and context

`runtime.http()` returns the frozen HttpDispatcher; connect it with `HttpServerBuilder.asyncHandler(runtime.http()::dispatch)`. `@HttpHandler(domain, routeKey, routeKeyMethod)` marks the class; `@HttpMethod(value = "/path", method = HttpRequestMethod.POST, domain, routeKey, routeKeyMethod)` marks methods (method defaults to POST; GET must be explicit; HttpRequestMethod is GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, TRACE). A nonzero method Domain overrides the class Domain. Methods are public instance methods returning void or a business object (not a primitive, not a Netty HttpObject) with at most one input (FullHttpRequest, String or a DTO decoded by HttpRequestCodec) and at most one context (Context, HttpContext or an HttpContext subtype), in any order.

Paths match the raw, case-sensitive path without the query: `/player?id=42` matches `/player`; `/player/` does not. No automatic HEAD or OPTIONS. A wrong method on a known path returns 405 with a sorted Allow header.

`HttpContext` adds `request()` and `responseCallback()` to Route accessors and has no business identity or Metadata. `HttpContextFactory.create(domain, routeKey, request, callback)` runs before admission: it must keep Domain, a nonzero extracted Key, the exact request and callback; when no Key rule exists it chooses the Key; it may complete the callback (for example 401) and return null to skip the method. A factory is required for endpoints without a Key rule or with a custom context type.

### 13.2 Completion, encoding and ownership

`HttpResultCallback`: `onResponse(result)` (status 200), `onResponse(status, result)` (200..599), `onFail(cause)`. A returned object (including null → JSON `null`) completes automatically; a void method completes explicitly, now or later. Completion is claimed once; only the winning result is encoded, synchronously on the completing thread, by `HttpResultCodec` (default Jackson JSON, `application/json; charset=UTF-8`, with the same property rules as the default request decoding — fields of any visibility plus ordinary getters/setters — so one DTO reads and writes the same fields; request bodies are parsed straight from the ByteBuf without a String copy; a custom codec returns an owned byte[] and a content type, and must be thread-safe). Encoding failure or a transport object as result → RUNTIME_EXECUTION_ERROR and the Network failure path (500 and close).

| Case | HTTP | Method runs |
| --- | --- | --- |
| Runtime closed | 503 | no |
| Non-origin target or fragment | 400 | no |
| Unknown path / wrong method | 404 / 405 + Allow | no |
| Invalid Key or input, Key 0 | 400 | no |
| Factory completes | its response | no |
| Overload or executor closed | 503 | no |
| Method throws | 500 unless already completed | reported |
| Returns null | 200 JSON null | yes |

Runtime retains the request until the method returns and automatic encoding finishes (or releases it at once on rejection). Copy request data before returning if needed later. Disconnection does not cancel admitted work; Runtime.close neither closes HttpServer nor waits for deferred completions.

<a id="135-request-binding"></a>
<a id="135-请求绑定"></a>

### 13.3 Route Key rules and request binding

A class `routeKey`/`routeKeyMethod` applies to all its methods; a nonempty method-level rule replaces it entirely; both names or a blank name fail build. `routeKey = "playerId"`: GET reads exactly one query value (Netty QueryStringDecoder, UTF-8); other methods read a top-level property of one strict UTF-8 JSON object (duplicate names, trailing values, floats, null and overflow rejected), independent of Content-Type. Values are decimal 64-bit integers, nonzero. `routeKeyMethod = "playerKey"` binds a public `long`/`Long playerKey(FullHttpRequest)` on the handler; it runs before the factory and admission; IllegalArgumentException → 400, other failures → 500. Extraction uses Jackson streaming on a non-releasing duplicate of the body.

`HttpRequestCodec` (default `HttpRequestCodec.json()`) compiles one decoder per method from its declared generic type. String receives the raw UTF-8 body; other types are read by Jackson: GET builds an object from query parameters (repeated names become arrays), other methods parse the body with trailing tokens rejected and unknown properties ignored. A null result or an IllegalArgumentException → 400 without running the method. Type variables, wildcards and primitive parameters fail build.

Example: [RuntimeHttpExample](../../game-demo/src/main/java/cn/managame/demo/examples/runtime/RuntimeHttpExample.java) (POST `/echo` with a body Key, GET `/lookup` with a query Key and a cross-Route callback). Tests: [RuntimeHttpTest](../../game-runtime/src/test/java/cn/managame/runtime/http/RuntimeHttpTest.java), [HttpRouteKeyTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpRouteKeyTest.java), [HttpResultTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpResultTest.java). Only HTTP/1.1 semantics are served at this layer.

<a id="14-metadata-ingress-external-callbacks-drain-and-diagnostics"></a>

## 14. Metadata ingress, external callbacks, drain and diagnostics

`dispatch(connection, command, requestId, metadata, message)` passes the exact Metadata and the correlation to the context factory (or uses message Key extraction with identity 0/0 when none is configured, keeping the correlation on the built context); the six-argument Key overload preserves explicit Key, identity and Metadata. Other overloads use empty Metadata, and every overload without correlation parameters passes 0.

`dispatchRpc(...)` resolves the Handler's Domain and creates a DefaultRpcHandlerContext; the application has already decoded the body and chosen Key and identity. It sends nothing; the typed adapter lives in game-spring.

`runtime.callback(cb)` requires this Runtime's current Route. It reserves one outstanding continuation counted by drain and returns a thread-safe one-shot CapturedRouteCallback: the first onSuccess/onFail returns to the source Route (inline when already there); later calls are ignored; onFail needs a positive code, and `onFail(code, cause)` passes the cause to the business callback's method of the same name (which ignores it by default). Always complete or cancel it, including through an external timeout. When the external call failed synchronously and the caller already learned that from an exception, `cancel()` releases the reservation without running the business callback, so one failure is not reported twice; cancel returns false once completed or cancelled. When `call`'s action throws, the callback receives `onFail(3008, cause)`.

`shutdown()` stops external admission and timers (irreversible, idempotent); running work may still submit downstream work, but not deferred work — `RuntimeTimer.schedule` and `CronScheduler.reschedule` throw RUNTIME_CLOSED (3001) from then on, including from a draining task, because a drain has to be finite (RT-DRAIN-03). A timer pending at that moment never fires and its `TimerRef.cancel()` still returns true, since its trigger never became eligible. `awaitTermination(timeout)` requires shutdown and an external thread (IllegalStateException otherwise) and waits for admitted actions and registered callbacks; it returns false on timeout without closing anything. `close()` closes executors without waiting. Graceful order: shutdown → awaitTermination → close → close network, RPC and data. Tests: [RuntimeDrainTest](../../game-runtime/src/test/java/cn/managame/runtime/RuntimeDrainTest.java).

`stats()` returns RuntimeStats(accepting, inFlight, queued, running, completed, rejected, errors, executionNanos, continuations, oldestContinuationNanos, domains); counters are approximate and not a success record. `domains` is one `DomainStats(routeDomain, queued, running, completed, rejected, executor)` per registered Domain in ascending Domain order, built fresh per reading, and `executor` is the `RouteExecutorStats(capacity, routeCapacity, activeRoutes)` of the executor bound to that Domain — the denominator for its queue depth, where 0 means the executor reports no such bound (RT-DIAG-02). `RouteExecutor.stats()` defaults to reporting nothing, so a custom executor keeps compiling; one that throws is caught and reported as nothing rather than failing the reading. VirtualThreadRouteExecutor reports both bounds and its active mailbox count; StripedRouteExecutor reports workers times queue capacity and no per-Route bound, because Routes share stripes. `continuations` is the number of `callback(...)` reservations not yet completed or cancelled and `oldestContinuationNanos` the age of the oldest, both 0 when none is outstanding; `inFlight - continuations` is Route work alone, which is what separates a slow business method from an integration that abandoned a capture. The age comes from scanning the outstanding reservations, which are few unless one is leaking. They are striped LongAdders, so worker threads do not contend on one word; `GameRuntimeBuilder.executionTiming(false)` drops the two monotonic clock reads per task, leaving executionNanos at 0. Spring assembly is specified by [game-spring](OGBS-Spring-Java-25-Specification-1.0.md).
