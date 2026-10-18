# OGBS Spring Java 25 Development Specification 1.0

**[English](OGBS-Spring-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md)

Companion: [container integration semantics](OGBS-Spring-1.0.md). Implemented optional artifact `cn.managame:game-spring:1.0.0-SNAPSHOT`, Java 25, depending on game-runtime, game-data and spring-context 7.0.9, with optional game-rpc. No Spring Boot dependency. Framework cores have no reverse Spring dependency.

## 1. Runtime registration

`cn.managame.spring.runtime.EnableGameRuntime` imports internal configuration and scans Handler/EventHandler/HttpHandler markers plus classes declaring or inheriting Cron methods. `String[] basePackages() default {}` aliases ComponentScan's basePackages; empty uses the annotated configuration's package. Standard Spring profiles/names apply. No additional Component annotation is needed. Existing Bean products with identifiable declared types also participate. Unknown FactoryBean products are not created to infer their task type. Discovery freezes at build, deduplicates exact target identity, and rejects prototype owners and identifiable Spring AOP proxies with IllegalArgumentException. Runtime validates annotation signatures, including private invalid methods. Annotation interfaces remain in Runtime without Spring annotations.

`GameRuntimeConfigurer.configure(GameRuntimeBuilder)` is a functional Bean extension. Configurers run in Spring order after handler/task discovery and all ProtocolRegistry Beans are collected and merged into one registry. Builder collection methods replace lists; an explicit configurer can therefore override discovered registrations. Applications provide Domain/RouteExecutor bindings and ingress identity policy. Prefer an executor Bean with destroyMethod="close" so failed startup releases caller-owned resources. Successful build transfers executor lifecycle to Runtime; built-in close is idempotent. Configuration creates the singleton named gameRuntime, binds Events for external static publication, and closes it on event-binding conflict. Handler constructors must not require the Runtime currently being built; use deferred providers only after startup.

## 2. Data registration

`cn.managame.spring.data.EnableGameData` scans Repository markers in basePackages with the same default-package behavior and imports internal configuration. State and logs are separate stores (D-LOG-04), so the integration resolves one source for each and never derives one from the other; no DataSource Bean is required at all when an EntityMapper Bean carries state. It discovers known singleton Beans directly extending SingleRepository, GroupRepository or LogRepository and registers their concrete types, state against the EntityMapper Bean when there is one. A static BeanFactoryPostProcessor replaces their instance suppliers with `gameData.repository(type)` and adds a gameData dependency, retaining names/qualifiers and field/setter injection. Data creates the no-argument instance once and initializes it before Spring injection callbacks. Constructor injection is not supported. Other Repository classes outside these three bases are ordinary Spring Beans. Prototype Data repositories reject before database access.

| Bean | Serves |
| --- | --- |
| One DataSource with neither qualifier | Both stores: one database, said by having one DataSource |
| `@GameStateSource` DataSource | `mysql(...)`, state only |
| `@GameLogSource` DataSource | `logMysql(...)`, logs only |
| An EntityMapper Bean, such as a MongoEntityMapper | The state backend of every discovered state Repository |

As soon as either qualifier is present, each store takes only the source named for it, so silence cannot put logs in the state database: a `@GameStateSource` with log Repositories and no `@GameLogSource` fails build with IllegalArgumentException naming `logMysql(DataSource)`, before any DataSource is opened. Several candidates for one store need `@Primary` or that store's qualifier, and one DataSource carrying both qualifiers is rejected. Missing backends are not reported during resolution, because a configurer may still supply one.

Validation: [StoreSourceTest](../../game-spring/src/test/java/cn/managame/spring/data/StoreSourceTest.java) covers a state EntityMapper Bean carrying state with no DataSource Bean present, and the refusal to offer a `@GameStateSource` to logs before any DataSource is opened; [DataRepositoryRegistrationTest](../../game-demo/src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java) covers one unqualified DataSource serving both stores.

`GameDataConfigurer.configure(GameDataBuilder)` Beans run in Spring order after source/Repository configuration and before build, for JSON codecs, write-back/error policy, or redirecting a discovered Repository to another backend — which holds because a registration that names a backend replaces the integration's own. Configurer dependencies must not require the still-building Data/Repository. The singleton gameData has destroyMethod="close"; it borrows every source and closes neither the application pool nor a Mapper's client.

## 3. Shutdown

The internal ContextClosedEvent listener has Ordered.HIGHEST_PRECEDENCE and supportsAsyncExecution=false. With Spring's ordinary synchronous event multicaster, it handles only its owning Context, calls Runtime.shutdown, awaits termination for at most `game.runtime.drain-timeout-millis` (default 60000, must be positive), logs a WARNING with the unfinished count if draining did not complete, then calls Runtime.close. An interrupt ends the wait early, closes Runtime and restores the interrupt status. Ordinary close listeners (such as demo TCP shutdown) run next, followed by managed HTTP lifecycle stop, Bean destruction and Data final flush. Do not install earlier/equal-priority resource-closing listeners, override this event ordering with a custom asynchronous multicaster, or close the Context from a Runtime Route. These would violate S-CLOSE-01. Resource cleanup on failed refresh follows Spring destruction. This module owns its managed HTTP listener, but no database pool or extra business threads. A listener may become reachable during the final refresh lifecycle phase; if another lifecycle fails, Spring closes the listener and Runtime, but this failed-refresh cleanup does not guarantee graceful drain or response delivery.

### 3.0 Shutdown budget

Implements S-CLOSE-02 and S-CLOSE-03. `cn.managame.spring.lifecycle.ShutdownBudget` is the one declared budget, created from three properties and split into the phases that run in sequence; the drain is derived, never configured.

| Property | Default | Phase |
| --- | --- | --- |
| `game.shutdown.budget-millis` | 25 000 | the whole budget; keep the orchestrator's grace period above it |
| `game.shutdown.lead-millis` | 0 | readiness reports failure while admission stays open |
| `game.shutdown.flush-reserve-millis` | 10 000 | reserved for Data's final persistence pass |
| *derived* | 15 000 | `drain = budget − lead − flushReserve`, for admitted work |

25 000 is chosen below the 30 seconds a Kubernetes grace period defaults to; `docker stop` allows 10, so a deployment using it sets the budget accordingly. `ShutdownBudget.of` rejects a non-positive budget or reserve, a negative lead, and any split that leaves the drain nothing, so an over-committed budget fails at refresh rather than truncating a shutdown much later. The former `game.runtime.drain-timeout-millis` is gone: configuring the drain on its own is what let the total drift.

`ShutdownConfiguration` is imported by both the Runtime and the Data configuration, so the budget and `ShutdownPhase` exist whenever either integration does and both read the same instance. `DataConfiguration` applies `builder.shutdownTimeout(budget.flushReserve())` **before** the application's `GameDataConfigurer`s, so the reserve is a framework default an application that knows its storage better can still override; without the Runtime integration the budget Bean may be absent, and Data keeps its own default.

`ShutdownPhase` is the one bit of state the drain shares with readiness: `leaveRotation()` sets it once, `leavingRotation()` is read from any thread including an HTTP thread answering a probe. The drain listener sets it first, logs the lead, sleeps it while still admitting, and only then calls `runtime.shutdown()`; a lead of zero skips the sleep. An interrupt during the lead or the drain is remembered and re-asserted after the Runtime is closed. §5 contributes the `shutdown.rotation` readiness condition from it, first in the list so an instance leaving rotation reports that rather than a consequence of it.

Validation: [ShutdownBudgetTest](../../game-spring/src/test/java/cn/managame/spring/lifecycle/ShutdownBudgetTest.java) covers the derivation, the defaults fitting under a 30 s grace period, the splits that must fail, and the phase being set once; [MetricsAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/metrics/MetricsAssemblyTest.java) asserts the behaviour the budget exists for — readiness reporting 503 with `shutdown.rotation` named while `stats().accepting()` is still true, and admission closing only after the lead elapses.

### 3.1 Managed HTTP

`EnableGameRuntime` also imports internal HTTP assembly. The singleton `gameHttpServer` is an ordinary Network HttpServer Bean with destroyMethod="close". An internal SmartLifecycle starts it at phase Integer.MAX_VALUE after singleton initialization and Runtime build, and closes it on lifecycle stop. Runtime's synchronous drain listener runs before this stop during Context.close. Do not call start/close on the Bean manually. HTTP is one-shot like the underlying Network server: a stopped listener cannot be reopened by Context.start. Context.stop alone is not the Runtime graceful-shutdown operation; use Context.close. Invalid configuration remains a startup failure even when listener activation is disabled.

The default activation check uses discovered HttpHandler Beans, not annotation names inferred from unrelated classes. A configurer supplying HTTP owners outside Bean discovery must explicitly set enabled=true. With enabled=false, the Bean is constructed but unbound and creates no default network groups. Lifecycle uses the gameHttpServer qualifier; an unrelated Primary HttpServer Bean does not replace the managed listener. HTTP/1.1, origin-form targets, JSON/object results and the existing Runtime admission/Route/context contracts remain unchanged. All business methods run on the configured Runtime RouteExecutor; no Spring business executor is created.

Properties are read from Spring Environment; applications load their own property source without requiring Spring Boot:

| Property | Default | Boundary |
| --- | --- | --- |
| game.http.enabled | true when a HttpHandler Bean is discovered; otherwise false | Explicit true/false overrides discovery |
| game.http.port | 8080 | 0..65535; 0 selects an ephemeral port, accessible through HttpServer.localAddress() |
| game.http.bind-address | 127.0.0.1 | Nonblank host/address; unresolved/busy addresses fail binding |
| game.http.context-path | empty (root) | `/` also means root; `/game/` normalizes to `/game`; raw absolute ASCII path segments using letters/digits/`.`/`_`/`~`/`-`; no empty, `.` or `..` segments, percent escapes, query or fragment |
| game.http.max-content-length | 1048576 | Positive byte count; oversize body follows Network 413 handling |
| game.http.max-initial-line-length | 4096 | Positive byte count |
| game.http.max-header-size | 8192 | Positive byte count |
| game.http.read-timeout-millis | 30000 | Nonnegative; 0 disables inbound inactivity timeout, not a business deadline |
| game.http.backlog | Network/Netty default | If set, positive SO_BACKLOG |
| game.http.keep-alive | Network/Netty default | If set, Boolean SO_KEEPALIVE (TCP socket setting, not HTTP Keep-Alive policy) |
| game.http.tcp-no-delay | Network/Netty default | If set, Boolean TCP_NODELAY |

`GameHttpConfigurer.configure(HttpServerBuilder)` Beans run in Spring order after property binding, before build; they may override network parameters, add native TLS/CORS/authentication pipeline handlers or supply caller-owned groups. The adapter installs Runtime dispatch last, so a configurer's handler/asyncHandler does not replace it. Extension handlers can still explicitly consume requests under Network's native pipeline contract. Configurers must not synchronously depend on the HTTP Bean being built. Borrowed groups remain caller-owned and must outlive listener closure. Without overrides, Network owns NIO boss/worker groups. TLS certificate configuration and arbitrary native options use this extension, not an automatically inferred HTTP security policy.

The internal prefix adapter matches a complete raw segment without decoding or redirects. `/game` and `/game/` map to endpoint `/`; `/game/echo?routeKey=7` maps to `/echo?routeKey=7`. Unprefixed `/echo`, `/games/echo` and `/game%2Fecho` return 404. Invalid origin-form/fragment targets return 400. For a nonroot prefix, dispatch uses a retained request duplicate with relative URI, releases its own reference after dispatch (including failure), and leaves Network's original request untouched. Runtime independently retains admitted requests through method return; deferred request access still requires its own ownership. HTTP context factories/codecs observe the relative URI; native pipeline extensions before dispatch see the original URI. Default root dispatch uses the original request directly. There is no hot property reload.

Example with ordinary `@HttpHandler(domain=3, routeKey="routeKey")` and `@HttpMethod("/echo")`:

```properties
game.http.port=8080
game.http.context-path=/game
```

POST `/game/echo` with `{"routeKey":7,"text":"hello"}` selects Domain 3 / Key 7. Port/path do not supply a Domain, routing Key or authentication identity. Users need no HttpServer builder, asyncHandler adapter or close listener. [HttpAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/runtime/HttpAssemblyTest.java) validates automatic scanned-Bean startup, DTO/String/GET dispatch, prefix boundaries, size rejection, disabling/no-owner defaults, failed binding cleanup, retained-reference ownership and shutdown admission/drain/port release. [DemoServicesTest](../../game-demo/src/test/java/cn/managame/demo/DemoServicesTest.java) exercises the same automatic listener with real Timer/Cron business execution. TLS/native security extensions, custom multicaster ordering and production load remain unverified by these tests.



<a id="managed-rpc"></a>

### 3.2 Managed RPC

`cn.managame.spring.rpc.EnableGameRpc` explicitly imports RPC assembly. It requires a GameRuntime Bean (normally EnableGameRuntime), a thread-safe GameRpcCodec Bean, and properties game.rpc.node-id (nonzero uint32 bits in int) and game.rpc.port (0..65535). game.rpc.bind-address defaults to 127.0.0.1; game.rpc.call-timeout-millis optionally overrides the Node default of 5000. Invalid properties fail startup. Add game-rpc explicitly: game-spring declares it optional, so HTTP/Data-only applications do not inherit it. No Boot, discovery, peer list inference or hot reload is introduced.

GameRpcCodec exposes `byte[] encode(Object)` and `<T> T decode(byte[], Class<T>)`, plus the default methods `ByteBuf encode(Object, ByteBufAllocator)` and `<T> T decode(ByteBuf, Class<T>)`; the adapter calls only the ByteBuf forms, whose defaults wrap/copy and delegate to the array forms. A codec may override the ByteBuf forms to encode straight into a (pooled) buffer and decode from the received frame without the intermediate copies; a body passed to the ByteBuf decode is borrowed and the result must not reference it. It must support concurrent transport/Route callers, return nonnull values of the requested exact registered type, and return arrays/objects independent of transport-buffer lifetime. Business serialization is application policy (for example Fory), without a framework serialization format. The adapter copies borrowed inbound bytes and decodes synchronously before Runtime admission; large/slow decoding can still delay the transport EventLoop. Never retain borrowed ByteBufs in a decoded object. Outbound arrays become adapter-owned until consumed; the codec must not modify/reuse them after return.

GameRpcConfigurer Beans run in Spring order. `configure(builder)` adjusts RpcNodeBuilder after properties; any handler set there is replaced. `decorate(handler)` then wraps the adapter's Runtime RpcHandler in the same order (the last configurer is outermost) and must return nonnull; this is how a GameRouter or ServiceRouting receives the managed handler as its direct handler. After build and before start, `attach(node)` runs in order (for example `routing.start(node)`); a failing attach detaches earlier configurers in reverse and closes the unstarted Node. At lifecycle stop, `detach(node)` runs in reverse order before the Node closes (for example `routing.close()`); detach failures are logged and closing continues. Routed requests reach RouterHandler, not runtime.dispatchRpc: decoding them, Runtime dispatch and exact routed replies (ServiceRouting.reply with the saved RoutedRequest) remain application code. `gameRpc` owns RpcNode lifecycle; `gameRpcNode` exposes topology management and localAddress, and an internal SmartLifecycle starts it at Integer.MAX_VALUE after singleton initialization. Context.close first drains Runtime through S-CLOSE-01, then closes RPC at lifecycle stop and idempotent destruction. Context.stop alone is not graceful Runtime shutdown; a stopped Node cannot restart. Do not start/close this managed Node or send raw call/notify/reply messages through it: its private callback correlation belongs to GameRpc. Use RpcNode separately for the raw API. Handler construction must defer GameRpc lookup (ObjectProvider or later setter use) to avoid a Runtime/Handler/GameRpc construction cycle.

Incoming Call and Notify command lookup uses ProtocolDescriptor.Type.REQUEST; Notify uses requestId zero, not a separate NOTIFY protocol registration. Unknown commands, codec exceptions and synchronous Runtime admission/signature/Key failures propagate to RpcHandler's HANDLER_ERROR policy for Call and logging-only policy for Notify. Valid decoded requests pass source Node/Slot, command/requestId, caller Key/business identity and exact received Metadata to runtime.dispatchRpc. Annotation Domain selection and ordinary HandlerMethod signatures remain unchanged. No physical Connection is exposed or fabricated.

`GameRpc.call(targetNodeId, routeKey, businessIdType, businessId, metadata, request, RouteCallback<T>)` requires this Runtime's current Route and a REQUEST-to-response binding. The caller's T must match that binding. It serializes the request, registers runtime.callback, and restores the exact source Context/Route for success and failure, including immediate missing-Peer rejection. Remote error codes and local RPC failures go to RouteCallback.onFail; a successful response that cannot be decoded becomes PROTOCOL_ERROR. When RpcNode throws synchronously after callback registration, the adapter releases the reservation with `CapturedRouteCallback.cancel()` and rethrows: the caller learns of that failure from the exception only, and the business callback does not receive a second report through onFail; serialization/binding failures before registration also only throw. No callback is left reserved after synchronous rejection. Runtime owns continuation execution; no Spring business executor is created. Response Metadata is not exposed by this object-only convenience callback; use a standalone raw RpcNode if the application needs a transport-level response envelope.

`GameRpc.notify(...)` uses the same explicit target/Key/identity/Metadata/request inputs and returns RpcSendStatus; it needs no current Route and cannot be replied to. `reply(response)` obtains Contexts.current(RpcHandlerContext.class); `reply(context, response)` permits a saved immutable envelope for delayed work without retaining a transport body. A reply must match the original request's registered response class, and requestId must be nonzero. `replyError(context, positiveCode)` sends an empty error response. Success/error replies use empty response Metadata. Both preserve source-Slot preference and replacement-connection fallback; ACCEPTED is transport acceptance, not remote completion. Business Handler return values do not trigger automatic RPC replies. Exceptions after asynchronous Handler admission remain the ordinary RuntimeErrorHandler contract: business code must issue an error reply explicitly if wanted; otherwise the caller can time out. Delayed application work outside Runtime is not automatically part of drain.

Example (the ProtocolRegistry declares `request(cmd, QueryReq.class, QueryRes.class)` and `response(cmd2, QueryRes.class)`):

```java
@Configuration
@EnableGameRuntime(basePackages = "my.game")
@EnableGameRpc
class RpcConfig {
    // Provide GameRpcCodec, ProtocolRegistry, RouteExecutor and GameRuntimeConfigurer.
}

@Handler(domain = ROLE)
class QueryHandler {
    private final ObjectProvider<GameRpc> rpc;
    QueryHandler(ObjectProvider<GameRpc> rpc) { this.rpc = rpc; }
    @HandlerMethod public void query(QueryReq request) {
        rpc.getObject().reply(new QueryRes(request.roleId()));
    }
}
```

```properties
game.rpc.node-id=1
game.rpc.port=9100
```

Composing a service-role Router on the managed Node (the application's RouterHandler decides how routed requests enter Runtime):

```java
@Bean GameRpcConfigurer routing(RouterHandler routed) {
    return new GameRpcConfigurer() {
        private ServiceRouting routing;
        public void configure(RpcNodeBuilder builder) {}
        public RpcHandler decorate(RpcHandler runtimeHandler) {
            return routing = new ServiceRouting(SERVICE_ID, NODE_EPOCH, routed, runtimeHandler);
        }
        public void attach(RpcNode node) { routing.start(node); ROUTER_IDS.forEach(routing::addRouter); }
        public void detach(RpcNode node) { routing.close(); }
    };
}
```

After Context.refresh, call `context.getBean(RpcNode.class).addPeer(2, address, 1)` under application topology policy; with routing, connect the Node this way to every declared Router. [RpcCompositionTest](../../game-spring/src/test/java/cn/managame/spring/rpc/RpcCompositionTest.java) verifies decorate/attach/detach order, delegation to the Runtime handler, and cleanup after a failed attach. [RpcAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/rpc/RpcAssemblyTest.java) exercises actual TCP Call/Notify, identity/origin, object replies, unknown-command errors, immediate failure and exact Route restoration, plus remote-error/malformed-response routing, synchronous rejection reservation cleanup, timeout continuation drain, managed port release and one-shot lifecycle. Deployment security, discovery and production capacity remain outside this verification.

## 4. Usage and validation

The demo directly depends on game-spring and game-rpc. game-spring supplies transitive Runtime/Data/Network/Core and Spring Context APIs; explicit game-rpc supports the consolidated standalone RPC sample. Fory, JDBC driver and pool remain application dependencies. Other HTTP/Data-only applications still do not inherit game-spring's optional RPC dependency. `mvn -pl game-demo -am clean verify` builds the complete dependency reactor and runs application/standalone execution tests without preinstalled framework artifacts.

Configuration excerpts:

```java
@Configuration
@EnableGameRuntime(basePackages = "my.game")
@EnableGameData(basePackages = "my.game")
class GameConfig {
    // Provide DataSource, ProtocolRegistry, RouteExecutor and GameRuntimeConfigurer Beans.
}
```

The complete integration is [GameRuntimeConfig](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java) and [GameDataConfig](../../game-demo/src/main/java/cn/managame/demo/common/data/GameDataConfig.java). [CronBeansTest](../../game-spring/src/test/java/cn/managame/spring/runtime/CronBeansTest.java) covers method-only, inherited/default-interface and factory discovery, deduplication, startup rejection and real Route execution. [RuntimeLifecycleTest](../../game-spring/src/test/java/cn/managame/spring/runtime/RuntimeLifecycleTest.java) proves accepted work finishes before ordinary close listeners and resource destruction, and a replacement Context can bind Events again. Demo Repository tests validate initialized injection and all three bases using JDBC stubs; live databases and custom multicaster/proxy configurations remain unverified. Run root `mvn clean verify` for reactor boundaries; root clean verify now includes RPC and its optional adapter.

<a id="5-observability"></a>

## 5. Observability

Implements [Spring §5](OGBS-Spring-1.0.md#5-observability-composition). Package `cn.managame.spring.metrics`, opt-in with `@EnableGameMetrics`.

| Type | Role |
| --- | --- |
| `@EnableGameMetrics` | imports the configuration; without it components still keep their counters and an application reads them through their own `stats()` |
| `ReadinessCheck` | public: `String name()`, `boolean ready()`. Application beans are collected in order and added to the framework's own |
| `ObservabilityEndpoints` | public `HttpDispatcher wrap(HttpDispatcher business)`; the Runtime HTTP configuration applies it when the bean exists |

Beans: `MetricsRegistry gameMetricsRegistry` publishes one source per present component under the fixed prefixes `runtime`, `data`, `rpc` and `network`, each resolved through `ObjectProvider` so a missing component publishes nothing. `ObservabilityEndpoints gameObservabilityEndpoints` holds the registry, the readiness checks and the two paths.

Properties: `game.metrics.path` (default `/metrics`) and `game.metrics.readiness-path` (default `/readiness`). Both must be absolute, at least two characters, free of `?` and `#`, and different from each other; otherwise the bean fails with `IllegalArgumentException` at refresh. They are matched before `game.http.context-path` is applied, so they are reachable at the server root whatever the business context path is, and the context path neither exposes nor hides them. Only `GET` is served; any other method falls through to business dispatch.

Readiness returns 200 with `ready\n`, or 503 with `not ready: ` and the comma-separated names of the failing checks. The framework contributes `runtime.accepting` and `data.accepting` when those beans exist, from `stats().accepting()`; a check that throws counts as not ready. Because `GameRuntime.shutdown()` closes admission before work stops, a draining instance reports 503 while still finishing accepted work, and readings keep answering throughout.

`/metrics` renders every reading as `name value` on its own line, in registry order, with `text/plain; charset=UTF-8`. This is deliberately not a named exposition format: an application that needs one implements `MetricSink` and maps the names, which is what the Core contract exists for.

Both paths are answered on the HTTP thread, in front of `HttpContextPath` and therefore outside every Route executor — an endpoint that queued behind the work it reports on would say nothing while the instance is saturated. A check must honour the same rule: answer from state it already has, without blocking or dispatching.

`FrameworkMetrics` holds the adapters from each component's snapshot record to a `MetricsSource`. They live here rather than in the components so that a component stays free of the metrics contract and game-network keeps depending on no other framework module. A `NetworkServer` the application created without exposing it as a bean is its own to publish, with `registry.publish(prefix, source)`.

Validation: [MetricsAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/metrics/MetricsAssemblyTest.java) covers which prefixes are published when only Runtime is assembled, both paths served outside the context path, an application `ReadinessCheck` turning the instance not ready and being named, configurable paths, a draining Runtime reporting 503 while `/metrics` still answers, and path validation.
