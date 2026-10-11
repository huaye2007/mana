# OGBS Runtime Java 25 Development Specification 1.0

**[English](OGBS-Runtime-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md)

Document type: **Java Development Specification**. Standard: [Runtime Specification](OGBS-Runtime-1.0.md).

This document defines public Java APIs, defaults, exceptions, threads/resources, extensions, and validation. Implementations satisfy both specifications, including behavior, not signatures alone. Fix implementation deviations; design changes update both layers. Pending/unverified capabilities are not completed features.

Status: current repository implementation contract. Semantics: [OGBS Runtime](OGBS-Runtime-1.0.md). Shared types: [Core](OGBS-Core-1.0.md).

<a id="1-模块与主-api"></a>

## 1. Module and main API

Maven: `cn.managame:game-runtime:1.0.0-SNAPSHOT`. Entry package: `cn.managame.runtime`, with responsibility-based subpackages. Depends on `game-core`, `game-network`, and Jackson Databind 2.21.3 (transitive Core/Annotations) for HTTP result encoding and JSON Key extraction; game-core also supplies shared Caffeine transitively for idle Mailbox reuse; requires Java 25. HTTP is a subpackage of this artifact, with native Netty HTTP types, not another Maven module. Runtime has no RPC dependency.

Signature excerpts below; source defines the types.

```java
public interface GameRuntime extends AutoCloseable {
    void dispatch(HandlerContext context);
    void dispatch(Connection connection, long routeKey, Object message);
    void dispatch(Connection connection, long routeKey, int businessIdType, long businessId, Object message);
    void dispatch(Connection connection, Object message);
    void dispatch(Connection connection, Metadata metadata, Object message);
    void dispatch(Connection connection, long key, int businessIdType, long businessId, Metadata metadata, Object message);
    void dispatchRpc(int sourceNodeId, int sourceSlotId, int command, int requestId, long key,
                     int businessIdType, long businessId, Metadata metadata, Object message);
    void dispatch(Connection connection, int businessIdType, long businessId, Object message);
    HttpDispatcher http();
    <T> void call(int routeDomain, long routeKey,
                  Supplier<T> action, RouteCallback<T> callback);
    EventBus eventBus();
    RuntimeTimer timer();
    CronScheduler cron();
    ProtocolRegistry protocols();
    RouteKeyRegistry routeKeys();
    <T> RouteCallback<T> callback(RouteCallback<T> callback);
    void shutdown();
    boolean awaitTermination(Duration timeout) throws InterruptedException;
    RuntimeStats stats();
    void close();
}

public interface RouteCallback<T> {
    void onSuccess(T result);
    void onFail(int errorCode);
}
```

No separate start, dynamic registration, or public arbitrary Runnable dispatch. Successful build is usable. Business messages normally use `dispatch(connection, routeKey, businessIdType, businessId, message)`; no application Route or Context construction is needed. Anonymous work can omit identity. Explicit HandlerContext dispatch remains available for custom context fields; Metadata also has direct overloads. Message-derived extraction has separate overloads; see [§6.2](#automatic-handler-dispatch). HTTP connects through `asyncHandler(runtime.http()::dispatch)`; see [§13](#runtime-http-api).

For Domain-aware external ingress, configure `handlerContextFactory(...)` and use `dispatch(connection, message)`; the factory receives the annotation-derived Domain and selects routing/identity from application-managed storage. Explicit parameters retain priority. See [§6.4](#handler-context-factory).

Public packages:

| Package | Contents |
| --- | --- |
| cn.managame.runtime | GameRuntime, GameRuntimeBuilder |
| cn.managame.runtime.context | Context hierarchy, defaults, read-only Contexts |
| cn.managame.runtime.route | Domain, Key binding/extraction/registry, RouteCallback |
| cn.managame.runtime.executor | Executor SPI, bindings, official implementations |
| cn.managame.runtime.protocol | Descriptors, registration, request/response association |
| cn.managame.runtime.handler | Handler annotations, HandlerArgumentBinding, HandlerContextFactory |
| cn.managame.runtime.http | HttpHandler, HttpMethod, HttpRequestMethod, HttpDispatcher, HttpContext, DefaultHttpContext, HttpContextFactory, HttpResultCallback, HttpResultCodec |
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
    .handlerArguments(Iterable<? extends HandlerArgumentBinding<?>>)
    .handlerContextFactory(HandlerContextFactory)
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
interface ClientHandlerContext extends HandlerContext { Connection connection(); }
interface RpcHandlerContext extends HandlerContext {
    int sourceNodeId();
    int sourceSlotId();
    int command();
    int requestId();
}
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
| DefaultClientHandlerContext | (int domain, long key, Object message, Connection connection) |
| DefaultHandlerContext | (int domain, long key, int businessIdType, long businessId, Metadata metadata, Object message) |
| DefaultClientHandlerContext | (int domain, long key, int businessIdType, long businessId, Metadata metadata, Object message, Connection connection) |
| DefaultRpcHandlerContext | (int domain, long key, int businessIdType, long businessId, Metadata metadata, Object message, int sourceNodeId, int sourceSlotId, int command, int requestId) |
| DefaultEventContext | (Event event, int businessIdType, long businessId, Metadata metadata) |
| DefaultTimerContext | (int domain, long key) |
| DefaultRouteCallContext | (int domain, long key, int businessIdType, long businessId, Metadata metadata) |

Short HandlerContext construction uses identity 0 and empty Metadata. businessIdType is 0–255; Metadata/message cannot be null. Defaults are extensible for connections/correlation fields, which do not automatically copy into EventContext/RouteCallContext.

Connection is `cn.managame.network.connection.Connection`. DefaultClientHandlerContext stores the borrowed reference in a final field and permits null. Business code reads `context.connection()` or `Contexts.current(ClientHandlerContext.class).connection()`; Connection is not a separate Handler method parameter. Runtime neither queries active state nor closes it; writes may reject after disconnection while queued. Common HandlerContext does not expose a connection; see §4.2 for specific fields and migration.

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

<a id="transport-handler-contexts"></a>

### 4.2 Client and RPC Handler contexts

ClientHandlerContext denotes player/client ingress rather than a specific transport protocol; it can carry any game-network Connection, including a WebSocket connection. Its name does not establish authentication. RpcHandlerContext denotes inter-service RPC ingress, including both Call and Notify.

HandlerContext/DefaultHandlerContext contain only the decoded message and inherited invocation fields. ClientHandlerContext/DefaultClientHandlerContext add the borrowed Connection. RpcHandlerContext/DefaultRpcHandlerContext add sourceNodeId, sourceSlotId, command and requestId; they do not expose Connection or depend on game-rpc. HTTP remains the separate HttpContext hierarchy without invocation identity/Metadata.

All Connection convenience dispatch overloads create DefaultClientHandlerContext, including with null Connection. HandlerContextFactory.create returns ClientHandlerContext; custom factory contexts extend DefaultClientHandlerContext or implement ClientHandlerContext. Ordinary Handler methods may receive HandlerContext/DefaultHandlerContext for transport-independent work, ClientHandlerContext for client-connection work, or RpcHandlerContext for RPC work, in either message/context order. At dispatch, incompatible required subtypes reject with HANDLER_CONTEXT_MISMATCH (3003) before argument resolution or admission. Context-free message methods remain supported.

DefaultRpcHandlerContext requires nonzero sourceNodeId and command, sourceSlotId in 0..254, businessIdType in 0..255, and nonnull Metadata/message. Invalid numeric values throw IllegalArgumentException; null values throw NullPointerException. Node IDs, commands and nonzero request IDs retain negative int representations of unsigned wire values; requestId=0 represents Notify. Domain and nonzero Key are validated during dispatch, against the exact Handler's annotation-derived Domain. Context construction does not cross-check command against protocol registration, validate the source Slot against current Peer topology, or authenticate the envelope; integration owns those checks.

The RPC adapter decodes the borrowed RpcRequest body before its callback returns, then submits the decoded object and copied envelope primitives. It must not defer access to a borrowed ByteBuf without arranging its lifetime separately. The context retains its decoded message without copying it and owns no RPC buffer; applications must provide a message safe for deferred Route execution. A zero RPC transport affinity does not permit a zero Runtime Key: integration selects a nonzero business Key. sourceSlotId is preserved only for reply selection. No automatic adapter, RPC response encoding, send/reply method, or extra executor is added.

```java
@Handler(domain = 1)
class PlayerHandlers {
    @HandlerMethod public void login(ClientHandlerContext context, LoginReq request) {
        Connection connection = context.connection();
        // Verify token here before manually binding application identity.
    }
    @HandlerMethod public void update(RpcHandlerContext context, UpdateReq request) {
        long roleId = context.businessId();
        int sourceNodeId = context.sourceNodeId();
        int sourceSlotId = context.sourceSlotId();
        int requestId = context.requestId();
        // Integration/business code uses the RPC layer to encode and reply when required.
    }
}
// External RPC integration; annotation-derived Domain and business Key selected beforehand:
runtime.dispatch(new DefaultRpcHandlerContext(domain, key, businessIdType, businessId,
        metadata, decodedMessage, sourceNodeId, sourceSlotId, command, requestId));
```

Migration: recompile handlers/factories after this source contract change. Replace connection-bearing DefaultHandlerContext construction with DefaultClientHandlerContext, and connection access through HandlerContext/DefaultHandlerContext with ClientHandlerContext (or Contexts.current(ClientHandlerContext.class)). Connection-free DefaultHandlerContext constructors remain available. Custom contexts that used to extend DefaultHandlerContext and expose a connection now extend DefaultClientHandlerContext or implement ClientHandlerContext. Generic business identity access remains unchanged. Typed handlers do not gain polymorphic message dispatch: each exact message class still has one Handler, so a message shared across transports uses a common HandlerContext signature and checks transport capabilities only if needed.

Sources: [context types](../../game-runtime/src/main/java/cn/managame/runtime/context), [dispatch implementation](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java). [TransportContextTest](../../game-runtime/src/test/java/cn/managame/runtime/TransportContextTest.java) verifies shared serial Route execution, pre-admission type rejection, accepted work after close, Notify/Call correlation, nested TCP/RPC restoration and invocation-only Event/call inheritance. [RPC reply ownership and Slots](OGBS-RPC-Java-25-Specification-1.0.md) remain unchanged; optional real RPC-to-Runtime networking is verified by RpcAssemblyTest in game-spring.

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
RouteKeyBinding<T> RouteKeyBinding.ofField(Class<T> messageType, String field);
RouteKeyBinding<T> RouteKeyBinding.ofMethod(Class<T> messageType, String method);
long RouteKeyRegistry.getRouteKey(Object message);
```

Nonnull-type lookup misses return null. Key registration is independent; exact-Class lookup without extractor returns 0. Extractor exceptions propagate. Zero cannot dispatch. Explicit-context and supplied-Key dispatch never replace their Key by extraction; only the message-derived overload requests extraction.

## 6. Handler

Objects in handlers require the inheritable type annotation `@Handler(domain = ...)`. Nonzero method-level `@HandlerMethod(domain = ...)` overrides the class Domain; zero uses it.

Both annotations additionally declare `String routeKey() default ""` and `String routeKeyMethod() default ""`. These optional rules refer to a field or public no-argument instance method on the registered message class. A nonempty method annotation rule replaces the entire class rule, including switching from field to method. With neither configured, no extractor is added; supplied-Key dispatch needs none. Defining both names or a whitespace-only name rejects at build, including an invalid class rule even if a method overrides it. An annotation rule plus an explicit builder binding for the same message is rejected as duplicate, without hidden precedence.

Methods require exactly one registered REQUEST/NOTIFY parameter, optionally one Context parameter, and zero or more explicitly registered custom argument types, in any order:

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
| public void handle(Extra value, M m) | Yes, with optional binding | Extra is an explicitly registered application argument |
| public void handle(Connection c, M m) | No automatic binding | Read the optional connection from ClientHandlerContext |
| public M handle(M m) | No | Must return void; no automatic response sending |
| public static void handle(M m) | No | Instance method required |
| public void handle(M a, M b) | No | Message not unique |
| public void handle(TimerContext c, M m) | No | Wrong context hierarchy |
| public void handle(M... messages) | No | No varargs |

RuntimeDispatchException means precondition/admission failure; Handler exceptions go to RuntimeErrorHandler. Even inline callers cannot obtain business results by catching original Handler exceptions.

<a id="automatic-handler-dispatch"></a>

### 6.2 Supplied-Key dispatch and exceptional message extraction

The usual business call is `runtime.dispatch(connection, routeKey, businessIdType, businessId, message)`. It resolves the exact Handler's annotation Domain, preserves the supplied nonzero Key without reading any protocol member or invoking a registered extractor, and constructs DefaultClientHandlerContext with that connection/message, the explicit identity, and empty Metadata. businessIdType must be 0..255 (otherwise IllegalArgumentException); businessId retains all long values, including zero and negative values. Identity interpretation/validation belongs to the application. Anonymous work may use `dispatch(connection, routeKey, message)` with identity 0/0. Connection may be null; read it from Context. Handlers needing a custom context subtype use explicit-context dispatch or the configured factory in §6.4; built-in contexts reject incompatibility with HANDLER_CONTEXT_MISMATCH (3003). Custom parameters use the bindings in §6.3; no implicit Connection injection is added.

For the minority of messages whose routing depends on protocol content, call `runtime.dispatch(connection, businessIdType, businessId, message)`, or omit identity for anonymous work only when no context factory is configured. It resolves Handler Domain and invokes the exact-class RouteKey binding, supplied explicitly or compiled from optional annotations. Missing binding or zero result yields INVALID_ROUTE_KEY (3005); no Handler yields HANDLER_NOT_FOUND (3002), and closed Runtime yields RUNTIME_CLOSED (3001). No fallback or retry substitutes a supplied/extracted Key. Extractor exceptions occur on the submitting thread before admission and propagate; Handler exceptions after execution begins still go to RuntimeErrorHandler. None of these convenience overloads inherits business identity/Metadata from an outer current context. They preserve existing executor capacity, same-Route inline restoration and shutdown rules. Custom GameRuntime implementations must implement the new overloads.

```java
@Handler(domain = 1)
class LoginHandler {
    @HandlerMethod
    public void login(ClientHandlerContext context, LoginReq request) {
        Connection connection = context.connection();
    }
}
// Normal path: caller already selected the Key; request.userId is not read.
runtime.dispatch(connection, 99L, loginReq);

// Exceptional path (no context factory): configure @HandlerMethod(routeKey = "userId")
// or @HandlerMethod(routeKeyMethod = "getUserId"), then:
runtime.dispatch(connection, loginReq);
```

`ofField`/`ofMethod` compile immediately and reject invalid configuration with IllegalArgumentException (null type/name uses NullPointerException). Fields may be private/inherited; the nearest declaration wins. Methods must be public, instance, non-varargs, and take no arguments; inherited methods are allowed. Values must be byte/short/int/long or Byte/Short/Integer/Long and widen exactly to long; strings, floats, static members and missing members reject. A null boxed value raises NullPointerException during extraction. Method RuntimeException/Error propagates unchanged; a checked exception becomes IllegalStateException with its cause. Zero is readable but cannot route a message. JPMS access must permit privateLookupIn; inaccessible members reject configuration. Internal MessageRouteKey compiles MethodHandles once, without per-message reflective lookup or a monitor.

Sources: [annotations](../../game-runtime/src/main/java/cn/managame/runtime/handler/HandlerMethod.java), [RouteKeyBinding](../../game-runtime/src/main/java/cn/managame/runtime/route/RouteKeyBinding.java), [MessageRouteKey](../../game-runtime/src/main/java/cn/managame/runtime/internal/MessageRouteKey.java), [DefaultGameRuntime](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java). [HandlerDispatchTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerDispatchTest.java) verifies supplied-Key precedence (including a null protocol member), annotation overrides, private/inherited fields, getter failures, invalid signatures/configuration, explicit-context preservation, closed admission, and same-Key serialization. [DemoHandlerTest](../../game-demo/src/test/java/cn/managame/demo/DemoHandlerTest.java) validates the marker-only Spring UserHandler with caller Key 99 while the request contains userId=10001.

<a id="handler-arguments"></a>

### 6.3 Context business identity and optional Handler arguments

The normal business API is `handle(HandlerContext context, MyMessage request)`, reading `context.businessId()` and, when needed, `context.businessIdType()`. No RoleId/GuildId/RoomId wrapper or custom argument registration is required. Authentication and category checks belong to business integration; Context access itself does not perform them. Key and business identity remain independent. Direct access reuses the identity pair already present in Context and avoids wrapper allocation and registration for each identity category. Optional custom argument bindings below are an extension for applications that need additional values or stronger type constraints, not a prerequisite for identity access.

`cn.managame.runtime.handler.HandlerArgumentBinding<T>` is a record of `Class<T> type` and `Function<HandlerContext, ? extends T> resolver`, constructed directly or with `of(type, resolver)`. Both components and `resolve(context)` input must be nonnull. Primitive and Context-derived types reject with IllegalArgumentException. Register via builder.handlerArguments; the copied list replaces previous registrations and defaults empty. Bindings use exact declared Class, without naming conventions, constructors inferred by reflection, superclass matching, or a framework RoleId type. Duplicate binding types, a type also registered as a protocol, a repeated custom type in one method, and unbound method argument types fail build with IllegalArgumentException. An unused valid binding is permitted.

Parameter classification is Context first, registered protocol second, explicit argument binding third. Exactly one inbound message and at most one Context are still required. Different custom types may project the same identity pair; a method may omit both custom parameters and Context. MethodHandle parameter adaptation compiles during build. No reflection search, extra thread, or synchronization monitor is introduced per dispatch; methods with no custom parameters reuse an empty argument array.

After Route/Handler/context validation, resolvers run once in custom-parameter declaration order on the submitting thread, before admission and before binding the target Context scope. The supplied resolver context is authoritative; Contexts.current() may be absent or refer to an outer context. Resolvers must be thread-safe and avoid target Route state; they should construct immutable values from context data. `resolve` rejects null results with NullPointerException and wrong runtime types with ClassCastException. Resolver RuntimeException/Error propagates unchanged without submission or RuntimeErrorHandler notification; previously resolved objects are not rolled back. This also applies to same-Route inline dispatch. Later overload/closed-executor rejection may occur after resolution. Captured values are borrowed, not copied/disposed by Runtime; accepted tasks can execute after close without resolving again. Handler execution failures retain the normal error-handler behavior.

```java
// Register this Handler and its request protocol; no argument binding is required.
@Handler(domain = 1)
class RoleHandler {
    @HandlerMethod public void handle(HandlerContext context, MyMessage request) {
        long roleId = context.businessId();
        // Read role-owned state only here, inside the selected Route.
    }
}
// Identity comes from authenticated integration, independently of Key and message fields.
runtime.dispatch(connection, 99L, 1, 10001L, request);
```

Identity is not automatically serialized or registered as a protocol. Login before authentication may remain `login(ClientHandlerContext, LoginReq)` without RoleId. Sources: [HandlerArgumentBinding](../../game-runtime/src/main/java/cn/managame/runtime/handler/HandlerArgumentBinding.java), [RuntimeCompiler](../../game-runtime/src/main/java/cn/managame/runtime/internal/RuntimeCompiler.java). [HandlerArgumentTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerArgumentTest.java) verifies ordering, one-time pre-admission resolution, independent identity/Key/Domain, explicit-context preservation, admitted work after closure, rejection and invalid bindings. The runnable Spring configuration uses [RoleHandler](../../game-demo/src/main/java/cn/managame/demo/bus/role/RoleHandler.java) with direct Context access and no argument bindings; [DemoIdentityTest](../../game-demo/src/test/java/cn/managame/demo/DemoIdentityTest.java) validates independent Key/identity, virtual-thread execution and missing-session policy rejection. Existing message/Context-only signatures remain valid; custom GameRuntime implementations need the identity overloads. Bindings apply only to HandlerMethod, not HTTP/Event/Cron.

The demo [GamePacketHandler](../../game-demo/src/main/java/cn/managame/demo/network/GamePacketHandler.java) deserializes the command-selected class and invokes connection/message dispatch. Its configured policy in §6.4 selects routing/identity by the annotation-derived Domain. LOGIN uses its configured request userId solely as a queue Key with identity 0/0; ROLE reads an immutable authenticated [GameSession](../../game-demo/src/main/java/cn/managame/demo/network/GameSession.java) once from Connection's AttributeKey. Role identity is explicitly installed by business authentication; no implicit identity inference is performed. Handler/domain policies are application configuration. Runtime need not retain the application packet because dispatch captures its decoded object, not its byte[] frame. [GamePacketNetworkTest](../../game-demo/src/test/java/cn/managame/demo/network/GamePacketNetworkTest.java) verifies LoginReq and PingMessage reach distinct HandlerMethods over TCP on virtual threads; [GamePacketDispatchTest](../../game-demo/src/test/java/cn/managame/demo/network/GamePacketDispatchTest.java) verifies pre-admission rejection and queued session capture. These are integration examples, without automatic response sending or implemented authentication.

Applications may organize registrations using their own enums, as in [GameDomain](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameDomain.java). Convert explicit positive IDs/names to RouteDomain, and reference a compile-time int constant in @Handler/@HandlerMethod.domain; Java annotations cannot accept arbitrary user enum values through the existing int element or invoke enum methods. Do not use ordinal() for persistent configuration IDs. ROLE is a demo business label, without framework-reserved identity or Key semantics. The TCP/Spring tests above execute this configuration.

<a id="handler-context-factory"></a>

### 6.4 Domain-aware ingress context policy

`cn.managame.runtime.handler.HandlerContextFactory` is a functional interface: `ClientHandlerContext create(int domain, Connection connection, Metadata metadata, Object message)`. `builder.handlerContextFactory(factory)` replaces the previous factory, rejects null, and defaults unconfigured. Successful build retains that factory instance; its fields are not copied or serialized. Runtime does not close the factory or its external identity store; their lifecycle belongs to the application. `dispatch(connection, message)` uses it when configured; otherwise it keeps the existing exact-class message-Key extraction and identity 0/0. The explicit-context, three/five-argument supplied-Key, and four-argument explicit-identity/extraction overloads all bypass the factory. Thus registering a factory cannot rewrite callers' explicit routing/identity. With a factory, this entry does not invoke any configured annotation/RouteKeyBinding extractor, even if it would fail. No merged selector or fallback exists.

Before invocation, check closed state, nonnull message and exact Handler existence. Pass the effective Domain selected by @HandlerMethod/@Handler, not caller input or protocol descriptors. Invoke once before the target Context scope is bound. The factory may obtain identity from a Connection AttributeKey or thread-safe external Map, then select Key/businessIdType/businessId by Domain, preserving the supplied Metadata. It may return a compatible application Context subtype. It must preserve Domain and the exact message/connection/Metadata references; null connection remains valid for non-network factories. It may reject missing authentication by throwing. Metadata can be explicitly provided, but no outer context is inherited automatically; Contexts.current() may still represent an outer task. Capture immutable values and avoid Route-confined state. Factory and argument resolvers share the submitting thread, with factory creation preceding validation and argument resolution. No extra executor or thread is added.

Null output raises NullPointerException. A changed Domain yields ROUTE_DOMAIN_MISMATCH (3004); replacement message/connection/Metadata or incompatible Handler context type yields HANDLER_CONTEXT_MISMATCH (3003). Key 0 yields INVALID_ROUTE_KEY (3005); missing Handler yields HANDLER_NOT_FOUND (3002), and closed Runtime yields RUNTIME_CLOSED (3001). Factory RuntimeException/Error propagates unchanged without RuntimeErrorHandler or admission, and argument resolvers are not invoked for rejected factory contexts. A factory that closes Runtime before returning is rejected by the following validation. Later executor overload/closure may still reject after factory/argument evaluation; created values are not disposed or rolled back. Accepted work retains the returned context instance and resolved arguments, never invoking the factory or identity lookup again. Custom mutable context fields remain application-owned. Handler execution exceptions continue the existing RuntimeErrorHandler contract. Same-Route factory dispatch runs inline and restores the outer instance.

```java
builder.handlerContextFactory((domain, connection, metadata, message) -> {
    // identities is an application-managed, thread-safe connection-to-authenticated-identity map.
    Identity identity = Objects.requireNonNull(identities.get(connection), "Not authenticated");
    return switch (domain) {
        case ROLE_ID -> new DefaultClientHandlerContext(domain, identity.roleRouteKey(), ROLE_TYPE,
                identity.roleId(), metadata, message, connection);
        case GUILD_ID -> new DefaultClientHandlerContext(domain, identity.guildRouteKey(), GUILD_TYPE,
                identity.guildId(), metadata, message, connection);
        default -> throw new IllegalArgumentException("Unsupported domain: " + domain);
    };
});
// The annotation determines Domain; policy supplies routing and identity.
runtime.dispatch(connection, decodedMessage);
```

The demo uses Connection's [GameSession](../../game-demo/src/main/java/cn/managame/demo/network/GameSession.java) attribute instead of a Map. [GameRuntimeConfig](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java) passes the annotation-derived Domain to [GameDomain.handlerContext](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameDomain.java); LOGIN (ID 2) takes request userId solely as its queue Key with identity 0/0, without reading or creating a session; Key 0 rejects before admission. ROLE (ID 1) rejects an absent session and preserves its business-supplied Key and GameDomain.ROLE_BUSINESS_ID_TYPE/role value, without setting Key equal to roleId. Connection establishment binds nothing. Business code manually binds through context.connection() inside login only after token verification succeeds; invalid tokens leave an unbound connection without a session. The session requires a nonzero Key and directly stores long roleId; session presence, not an ID value sentinel, records business binding. Applications own any ID range constraints. Production authentication remains unimplemented in UserHandler; the TCP probe verifies failure/no binding and successful binding inside login with a test-only token verifier. Network [GamePacketHandler](../../game-demo/src/main/java/cn/managame/demo/network/GamePacketHandler.java) only decodes and calls the two-argument entry. Authentication and identity-store cleanup remain application responsibilities. Existing no-factory builders retain their behavior; adopting a factory changes only this explicitly configured two-argument entry. [HandlerContextFactoryTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerContextFactoryTest.java) validates two Domains and an external Map, effective method Domain override, custom contexts, unchanged message-Key extractors, captured inputs after map removal, failure-before-admission, explicit overload precedence, inline restoration, and closure during factory creation. The demo TCP/dispatch tests exercise the attribute-backed policy.

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
| RouteExecutors.virtualThreads() | Serial Mailbox per active Route | Default 65,536 unfinished tasks total, at most 1,024 waiting per Route |
| new VirtualThreadRouteExecutor(capacity) | Virtual threads process active Mailboxes | All Routes combined, including running tasks; per-Route limit min(capacity, 1024) |
| new VirtualThreadRouteExecutor(capacity, routeCapacity, idleTimeout) | Same | routeCapacity=1..capacity waiting tasks per Route, excluding the running task |

Parameters must be positive. Different Routes on one platform shard wait for each other. Virtual-thread Mailboxes retain idle state for bounded reuse; active work cannot be evicted. Defaults and concurrency are defined in §7.3.

Both close without waiting and continue accepted tasks. Custom executors must honor admission/serialization; an ordinary multithreaded pool alone is insufficient.

<a id="71-两种容量的计算示例"></a>

### 7.1 Capacity examples

With 2 StripedRouteExecutor shards and queueCapacity=100, shard A containing 1 running +100 waiting tasks rejects the next A task even if B is idle. Do not borrow B and break A's queue boundary. Running work is excluded from this capacity.

VirtualThreadRouteExecutor(capacity=100) counts every unfinished task across Routes: 99 waiting +1 running is full. Virtual threads do not mean unlimited admission.

The per-Route limit stops one hot Route (for example a flooding client) from consuming the shared capacity: with capacity=100 and routeCapacity=10, a Route holding 10 waiting tasks gets OVERLOADED for its next task while other Routes are still admitted. The running task does not count toward the per-Route waiting limit. Connection-level rate limiting (dropping or disconnecting before decoding) belongs to the access layer and should still be configured in the network pipeline.

Same-Route inlining bypasses capacity checks and adds no queued share. Recursive same-Route Event/call still consumes stack; capacity does not prevent infinite recursion.

<a id="72-自定义-executor-的验收边界"></a>

### 7.2 Custom Executor acceptance criteria

Verify complete-Route mutual exclusion, enqueue order, rejected tasks never executing, admitted tasks executing once, and close/submit races. Executing after rejection misleads callers; accepting then silently dropping also violates the contract.

Runtime binds Context in task wrappers; Executor owns admission/scheduling only. Do not infer player IDs, protocols, or business exception meaning. Capacity/closure changes affect Java contracts; observable admission changes also update the standard.

<a id="route-mailbox-lifecycle"></a>

### 7.3 Virtual-thread mailbox retention and concurrency

`VirtualThreadRouteExecutor(int capacity)` and `RouteExecutors.virtualThreads()` retain idle mailboxes for 60 seconds. `VirtualThreadRouteExecutor(int capacity, Duration idleTimeout)` sets a different positive, nanosecond-representable timeout. Nonpositive capacity, zero/negative timeout, or timeout overflow throws IllegalArgumentException; null timeout throws NullPointerException. Idle cache maximumSize equals capacity (default 65,536 entries). Cached empty mailboxes consume no unfinished-task capacity. Caffeine eviction is maintained asynchronously, so this maximum is a configured target, not a synchronous hard memory bound; task capacity also does not bound all retained queue-array bytes.

A ConcurrentHashMap keyed by complete Domain/Key owns active Mailboxes. Short compute/computeIfPresent operations serialize queue mutation, consumer startup, and active/idle handoff for that Key; business actions run outside those operations. There is no executor-wide synchronized monitor. ConcurrentHashMap may synchronize colliding bins internally; this is not a lock-free guarantee. An ArrayDeque is safe here because every enqueue/poll is protected by the corresponding atomic map operation.

When the consumer finishes the last action and finds no queued work, it transfers the empty Mailbox into the Caffeine cache before removing the active mapping, within the same Key operation. The next activation atomically removes a cached Mailbox or creates a new one, then starts exactly one virtual-thread consumer. Task exceptions are logged, capacity is released in finally, and draining continues. A consumer-start failure removes the new task and rolls back its reservation before propagating the failure.

Caffeine comes transitively from game-core; dependency/version ownership is defined in the [Core Java specification](OGBS-Core-Java-25-Specification-1.0.md#1-模块与职责). The private cache uses maximumSize(capacity), expireAfterWrite(idleTimeout), and monotonic elapsed time, with Caffeine's default passive maintenance and no Scheduler configuration. Each return to idle resets the expiry interval. Activation cannot retrieve an expired Mailbox, even before physical reclamation. Subsequent cache writes and occasional reads trigger maintenance using Caffeine's default executor; Runtime adds no expiry timer or cleanup thread, and maintenance never executes business actions. Without further cache activity, expired entries may remain physically retained until later maintenance or closure; no release deadline is promised. For example, if a Mailbox expires after traffic stops, the next submission to that Route creates a new Mailbox instead of reusing the expired one. Cache loss through expiry/size eviction is only loss of reuse, never loss of an active task. GameTime changes do not affect idle time.

A single AtomicLong holds the closed bit and all task reservations, including submissions awaiting Key coordination or consumer startup, queued tasks, and running tasks. Reservation CAS and close are atomic: a reservation won before closure may finish admission and execute; later submissions return CLOSED. Close does not wait for business completion. It invalidates idle cache entries, prevents late idle insertion from surviving closure, and allows active admitted work to drain without caching its final empty Mailbox.

Confirmed tradeoff: retain a bounded idle cache to avoid queue recreation between short bursts while pinning active queues separately so cache policies cannot break RT-ROUTE-02/03. Additional independent idle-size tuning requires a concrete need; production throughput and GC capacity have not been benchmarked. Sources: [VirtualThreadRouteExecutor](../../game-runtime/src/main/java/cn/managame/runtime/executor/VirtualThreadRouteExecutor.java). Validation: [VirtualThreadRouteExecutorTest](../../game-runtime/src/test/java/cn/managame/runtime/executor/VirtualThreadRouteExecutorTest.java) covers timeout reset, expired Mailbox rejection on subsequent submission, long-running tasks, size eviction, exception recovery, concurrent expiry/arrival, atomic capacity, and close/drain; [RouteExecutorTest](../../game-runtime/src/test/java/cn/managame/runtime/executor/RouteExecutorTest.java) covers the shared executor contract.

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

<a id="static-event-publication"></a>

### 9.1 Static event publication

`cn.managame.runtime.event.Events` provides `static void publish(Event)`, `static void bind(GameRuntime)`, and `static boolean unbind(GameRuntime)`. Business code calls `Events.publish(event)` without locating a Runtime or EventBus. The existing instance entry `runtime.eventBus().publish(event)` remains available for explicit multi-instance integration.

Events first reads the owning Runtime from the internal ScopedValue binding, then falls back to an AtomicReference holding the process default when no owner exists. It selects once and delegates to that Runtime's EventBus. No owner/default throws IllegalStateException; null event or binding Runtime throws NullPointerException. Existing RuntimeDispatchException rejection codes propagate unchanged. A closed current owner reports RUNTIME_CLOSED even if another open default exists. A binding change after selection never causes another Runtime to receive a retry. Same-Route inline execution, EventContext identity/Metadata inheritance, Context restoration, ordered listeners, and failure isolation remain those of RT-EVENT-01–05.

Startup explicitly calls `Events.bind(runtime)` on a successfully built live Runtime before admitting external publishers; build does not automatically choose a process default. bind compares instance identity: repeated binding of the same object is a no-op; a conflicting object throws IllegalStateException without replacing the original. unbind returns true only when it removed that exact object's binding, and otherwise false. Binding borrows the Runtime and does not start/close resources. Built-in Runtime.close atomically marks closure and conditionally removes its own default binding before closing timers/executors. It cannot clear another Runtime's binding. Custom GameRuntime implementations must arrange `Events.unbind(this)` in their closure lifecycle.

Applications serialize bootstrap binding with shutdown and must not bind an already closed Runtime; bind itself does not inspect lifecycle or reopen it. Explicit unbind/closure must not be mistaken for a barrier awaiting publications that already selected an instance. As in ordinary instance publication, Runtime closure may reject an in-flight submission, while accepted work follows RT-CLOSE-02. Outside a Runtime context, publication fails with IllegalStateException after default removal; admitted work still executing inside the closed Runtime retains its owner and fails with RUNTIME_CLOSED.

```java
// Bootstrap only; Spring/application lifecycle owns Runtime closure.
Events.bind(runtime);
// Business publication, including from an external thread:
Events.publish(new ChangedEvent(1, 42));
// Explicit default removal without closing Runtime:
Events.unbind(runtime);
```

The [demo Runtime configuration](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java) binds the built instance and closes it if binding fails, then exposes it as a closeable Spring Bean. Business listeners need only @EventHandler because the application adds an annotation scan filter; this does not make the Runtime annotation depend on Spring. Source: [Events](../../game-runtime/src/main/java/cn/managame/runtime/event/Events.java), [RuntimeContexts](../../game-runtime/src/main/java/cn/managame/runtime/internal/RuntimeContexts.java), and [DefaultGameRuntime](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java). Validation: [EventsTest](../../game-runtime/src/test/java/cn/managame/runtime/event/EventsTest.java) covers missing/default/current selection, identity inheritance and inline Context restoration, conflicts, stale closure, closed-owner rejection, and a binding change during publication; [DemoEventTest](../../game-demo/src/test/java/cn/managame/demo/DemoEventTest.java) verifies marker-only Spring discovery, static publication, and unbinding after Context closure.

<a id="10-gametimetimer-与-cron"></a>

## 10. GameTime, Timer, and Cron

<a id="demo-task-integration"></a>

The plain-Spring [demo task integration](../../game-demo/README.md#demo-runtime-services) discovers classes with @Cron methods through CronMethodFilter and collects annotated singleton Bean instances through CronBeans, registers them with cronHandlers and explicitly selects UTC. Known @Bean product types and inherited public methods are included without a fixed task-type list. Scanning avoids unrelated eager initialization, deduplicates exact object references, and rejects prototype or identifiable Spring AOP proxy targets; unknown FactoryBean product types must be made identifiable. Discovery is application bootstrap behavior, frozen at Runtime build, and introduces no Spring dependency into Runtime. onCron uses `*/10 * * * * ?` on Domain 3/Key 1. A Spring TimerRef Bean schedules tasks::onTimer with a default 3000 ms delay and cancels on destruction. Marker-only HttpHandler scanning registers object-returning endpoints through httpHandlers; the main listener uses HttpServer.asyncHandler(runtime.http()::dispatch) with default port 8080. These are application defaults, not new Runtime defaults or public APIs. No Spring scheduler or additional business executor is introduced. [DemoServicesTest](../../game-demo/src/test/java/cn/managame/demo/DemoServicesTest.java) verifies real HTTP/1.1, timer and cron occurrence, route/thread context, cancellation and closure.

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

`@HttpHandler` is an inherited runtime type annotation with `int domain() default 0`. `@HttpMethod` is a runtime method annotation with required `String value()` (raw path), `HttpRequestMethod method() default HttpRequestMethod.POST`, and `int domain() default 0`. `HttpRequestMethod` is a public enum in cn.managame.runtime.http with GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, and TRACE. Use `@HttpMethod("/echo")` for default POST or `@HttpMethod(value="/lookup", method=HttpRequestMethod.GET)` for GET. String literals and custom tokens cannot be supplied to the annotation. RuntimeCompiler freezes enum name() as the exact method token; endpoint matching does not convert an incoming method to an enum, so unknown methods on registered paths still receive 405 with sorted Allow. Their routeKey/routeKeyMethod defaults and override rules are in §13.4. Nonzero method Domain overrides class Domain; the effective Domain must be registered. Each supplied target needs @HttpHandler. Methods are public instance non-varargs methods returning void or a reference type for a business result. Primitive return declarations and types implementing Netty HttpObject (including FullHttpResponse) fail build; boxed numbers, records, POJOs, maps, lists, and strings are ordinary result objects. Parameters remain zero to two: at most one input (FullHttpRequest, String, or a concrete reference type decoded by HttpRequestCodec) and one compatible Context/HttpContext or custom HttpContext subtype, either order. Context is optional; use Contexts.current(HttpContext.class) inside execution. InvocationContext parameters are rejected because HttpContext is not an InvocationContext. Request binding is defined in §13.5; no future unwrapping is performed. Invalid signatures, incompatible default contexts, missing required factories, duplicate endpoints, or unknown Domains fail build before executor ownership transfers. Handler/Event/Cron retain their void contracts.

Paths start with `/`, have no query/fragment/space/control characters, and match raw case-sensitive text. The verb comes from HttpRequestMethod; CONNECT is not an enum member and cannot be registered. Query is excluded without decoding for matching: `/player?id=42` matches `/player`; `/player/` and `/%70layer` do not. No automatic HEAD→GET, OPTIONS, classpath scanning, or streaming method API.

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

[RuntimeHttpExample](../../game-demo/src/main/java/cn/managame/demo/examples/runtime/RuntimeHttpExample.java) returns an application EchoResult record for POST `/echo` and submits PlayerResult through a cross-Route callback for GET `/lookup`. POST /echo omits method and exercises the POST default; GET /lookup explicitly selects HttpRequestMethod.GET. POST body field playerId is the class Key default; GET query field lookupId overrides it. The example uses the default HttpContext without a factory and reads routeKey(); these Keys are routing input and do not establish authenticated identity. Business methods construct no FullHttpResponse or HTTP version. The [execution test](../../game-demo/src/test/java/cn/managame/demo/examples/runtime/RuntimeHttpExampleTest.java) verifies UTF-8 DTO results over real HttpServer.

Sources: [public HTTP package](../../game-runtime/src/main/java/cn/managame/runtime/http), [RuntimeCompiler](../../game-runtime/src/main/java/cn/managame/runtime/internal/RuntimeCompiler.java), [RuntimeHttp](../../game-runtime/src/main/java/cn/managame/runtime/internal/RuntimeHttp.java). Tests: [RuntimeHttpTest](../../game-runtime/src/test/java/cn/managame/runtime/http/RuntimeHttpTest.java), [HttpRouteKeyTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpRouteKeyTest.java), [HttpResultTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpResultTest.java). They cover default POST versus explicit enum methods, exact method lookup and 405/Allow (including unknown tokens), registration (including rejection of InvocationContext parameters), factories, HTTP-originated default Event/call identity, exact custom HTTP context restoration, shared Route ordering, query/body rules, ownership, deferred completion, DTO/null/custom-codec encoding, completion races, and encoding failure. Run root `mvn clean verify` for dependencies and `mvn -pl game-runtime -am test` for focused validation. Root clean verify includes RPC tests and runnable HTTP/RPC examples. Production capacity, every disconnection race, and additional HTTP versions remain unverified/unimplemented respectively.

Runtime depends on game-core, game-network, and Jackson Databind, and receives shared Caffeine transitively from Core, with no new artifact/RPC dependency. Custom GameRuntime implementations implement http(). Existing no-HTTP builders remain usable. HttpMethod.method changes from String with GET default to HttpRequestMethod with POST default, requiring handler source migration and recompilation; existing compiled annotations are not a compatible binding. Replace `method="POST"` with `method=HttpRequestMethod.POST` or omit it, and use `method=HttpRequestMethod.GET` for every former implicit GET entry. Default POST uses JSON body field selection; it does not inherit GET query behavior or install GET aliases. HttpContextFactory now accepts selected long routeKey as its second argument and HttpResultCallback as its fourth; earlier three-argument/network-callback factories must migrate. DefaultHttpContext identity/Metadata constructor arguments and HTTP businessIdType()/businessId()/metadata() access are removed; use Route accessors or explicitly defined application context fields. HTTP methods taking InvocationContext must switch to Context/HttpContext or a custom HttpContext subtype. Ordinary InvocationContext propagation remains unchanged. Replace FullHttpResponse-returning methods with DTO/void and callback responses with business objects. This explicit contract change keeps response values independent of HTTP versions rather than retaining an incompatible native response API.

### 13.4 RouteKey field and method rules

Both @HttpHandler and @HttpMethod add `String routeKey() default ""` and `String routeKeyMethod() default ""`. Nonempty method-level configuration replaces the entire class rule, including when switching from field to method or vice versa; both empty inherits. Field and method cannot both be nonempty at either level; whitespace-only values fail build. No implicit field or Java getter-name inference is performed. If neither level has a rule, the context factory supplies the Key; otherwise extraction runs first and the optional factory must preserve it. Without a factory, DefaultHttpContext supplies Route, request, and result callback without identity/Metadata. Custom HttpContext subtype requirements still require a compatible factory.

For `routeKey = "playerId"`, GET uses Netty QueryStringDecoder UTF-8 query decoding and requires exactly one value for the exact case-sensitive name; duplicates after decoding are rejected. Values are optional minus plus ASCII digits, parseable as long and nonzero; plus signs, blanks, fractions, exponent notation, and overflow reject. Other methods use a top-level property of one strict UTF-8 JSON object in the readable ByteBuf region, never query fallback. Integer number tokens or decimal integer strings are accepted. Floating-point/exponent tokens, null/boolean/array/object fields, missing fields, duplicate JSON names anywhere, malformed JSON, extra trailing values, invalid UTF-8, overflow, and zero return 400. Property names are literal: dots are not nested path expressions. Content-Type does not select the source or parser; configured non-GET field rules expect JSON regardless of that header. Applications choose media policy in their pipeline.

The package-private HttpRouteKey uses Jackson Core's streaming JsonFactory with strict duplicate detection and charset detection disabled (UTF-8). It validates the full object before accepting the Key, uses a non-releasing ByteBufInputStream over duplicate() without modifying caller indices/refCnt, and does not deserialize a business DTO. Jackson 2.21.3 defaults limit depth to 1000, number length to 1000, string length to 20,000,000, and field-name length to 50,000; no extra total document/token limit is set. Constraint violations return 400; Network still bounds the complete request body and URI. Query decoding uses no additional parameter-count cutoff within Network's URI limit. No Runtime business thread or second queue is created for extraction.

`routeKeyMethod = "playerKey"` binds exactly a public instance `long playerKey(FullHttpRequest request)` or Long-returning equivalent on the registered Handler object. Compile its MethodHandle once during build; missing/private/static, wrong arguments/return, or varargs fail build. No zero-argument getter or arbitrary reflection expression is inferred. The method runs on ingress before factory/admission, borrows the request, must not access Route-owned state, and may serve concurrent requests; its object fields gain no serialization. IllegalArgumentException maps to 400; other exceptions or null Long are diagnosed as RUNTIME_EXECUTION_ERROR and call onFail. A zero return maps to 400. Methods needing request data later must acquire independent ownership.

For example, `@HttpHandler(domain=1, routeKey="playerId")` plus `@HttpMethod(value="/guild", routeKey="guildId")` uses default POST and selects guildId from the body; explicitly setting method=HttpRequestMethod.GET selects guildId from query. A custom `routeKeyMethod="playerKey"` replaces the class field rule and calls the declared extractor. Failure never retries another selector or lets a factory change the selected Key. [HttpRouteKeyTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpRouteKeyTest.java) tests both override directions, source isolation, full JSON validation, malformed UTF-8, exact 64-bit bounds, preserved buffer indices/refCnt, factory Key preservation, and extraction/registration failures. The updated runnable [RuntimeHttpExample](../../game-demo/src/main/java/cn/managame/demo/examples/runtime/RuntimeHttpExample.java) exercises body/query extraction over a real HttpServer.

### 13.5 Request binding

Builder `httpRequestCodec(HttpRequestCodec)` replaces the default codec and rejects null. The functional method `Function<FullHttpRequest,Object> decoder(Type type)` is called once per input method at build with its full declared generic Type. The resulting decoder must be thread-safe and runs on ingress after Key/context selection, before admission. Invalid configuration fails before executor ownership transfers. Decode IllegalArgumentException returns 400 without invoking the Handler; other failures follow the server-error path.

`HttpRequestCodec.json()` is the default. String receives raw readable UTF-8 body. Other reference types use a Jackson ObjectReader compiled per method. GET constructs an object from decoded query parameters: one value is a string, repeated values an array of strings; standard Jackson scalar coercion applies. Non-GET reads body JSON with trailing-token rejection. Unknown DTO fields are ignored, permitting a separate RouteKey property. Null decoded payload rejects. Records, POJOs, concrete parameterized maps/lists are supported; unresolved type variables/wildcards and primitive parameters reject build. FullHttpRequest keeps its existing borrowed path. Binding does not add Bean Validation, infer query names for String, enforce Content-Type, or authenticate RouteKey. Custom codecs own such policies. Body Key extraction and DTO binding are separate parses; no single-parse performance claim is made.

Use `public Result submit(RequestDto request)` or `public Result echo(String body)` and `Contexts.current(HttpContext.class)` when needed. Inputs decoded by the default codec do not retain native buffers. The request held by HttpContext is still borrowed through method return only. See [RuntimeHttpTest](../../game-runtime/src/test/java/cn/managame/runtime/http/RuntimeHttpTest.java) for generic types, query arrays, String, malformed inputs and no admission on failure.

## 14. Metadata ingress, external callbacks, drain, and diagnostics

`dispatch(connection, metadata, message)` passes the exact nonnull Metadata instance to the configured ingress factory; without a factory it uses message Key extraction with identity 0/0. The six-argument `dispatch(connection, key, businessIdType, businessId, metadata, message)` bypasses that factory and preserves explicit inputs. Other overloads use empty Metadata, never implicit outer values. HandlerContextFactory now has four parameters; migrate three-argument lambdas and preserve the input Metadata instance. Changed Metadata identity yields HANDLER_CONTEXT_MISMATCH (3003), just like replacement message/connection. Packet command/seq/code may use application-owned Core keys; Runtime reserves no new keys.

`dispatchRpc(sourceNodeId, sourceSlotId, command, requestId, key, businessIdType, businessId, metadata, message)` resolves the exact Handler's effective annotation Domain and creates DefaultRpcHandlerContext. It preserves the ordinary validation and argument-resolution path and does not introduce a game-rpc dependency. The application decodes/copies borrowed RPC body before dispatch, verifies command/type and identity, and chooses Key/identity. This entry does not send a response, choose a Peer, or alter game-rpc transport behavior; optional typed adaptation is implemented by game-spring without a reverse core dependency. [TransportContextTest](../../game-runtime/src/test/java/cn/managame/runtime/TransportContextTest.java) validates the convenience envelope.

`runtime.callback(RouteCallback<T>)` requires this Runtime's current Route, otherwise IllegalStateException. It reserves one outstanding continuation, captures the exact source Context, and returns a thread-safe one-shot adapter. First onSuccess/onFail posts to the source Route (or inlines when already there); duplicates are ignored. onFail requires a positive code; invalid codes throw IllegalArgumentException without consuming the reservation. Callback exceptions report RUNTIME_EXECUTION_ERROR; failed return admission reports ROUTE_CALLBACK_DISPATCH_FAILED. The reservation is released after completion submission, while submitted callback work stays counted. Always complete the adapter on synchronous external-call failure and ensure an external timeout path. There is no separate cancel handle. Borrowed fields in the restored context can already be invalid; copy required data before the initial Handler returns.

`shutdown()` stops external admission and timers without closing executors. It is irreversible and idempotent. Already executing owned contexts may submit downstream work. `awaitTermination(Duration)` requires prior shutdown and an external management thread; otherwise IllegalStateException. Null timeout throws NullPointerException, negative timeout IllegalArgumentException, interruption InterruptedException, timeout returns false without closing anything. Zero is a nonblocking poll. Completion waits for admitted actions and registered callbacks; deferred HTTP completion alone is not counted. `close()` still performs immediate executor closure and does not wait. Graceful order is shutdown → await success → close → close network/RPC/data. [RuntimeDrainTest](../../game-runtime/src/test/java/cn/managame/runtime/RuntimeDrainTest.java) validates the CAS admission/drain boundary and callback reservations.

`stats()` returns `cn.managame.runtime.diagnostic.RuntimeStats`: accepting, inFlight (queued/running actions plus registered callbacks), queued, running, completed actions (including failed/inline actions), rejected shutdown/executor admissions, errors reported, and executionNanos. Rejected does not count every pre-admission validation or decoding error. Duration includes nested overlapping intervals and is not CPU time. Atomic snapshots/LongAdder counters are approximate, can change between fields, and do not establish business success. APIs extend GameRuntime; custom implementations must implement the new methods. Optional Spring assembly and lifecycle are specified by [game-spring](OGBS-Spring-Java-25-Specification-1.0.md); Runtime itself keeps no Spring dependency.
