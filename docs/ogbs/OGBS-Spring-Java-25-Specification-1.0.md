# OGBS Spring Java 25 Development Specification 1.0

**[English](OGBS-Spring-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md)

Companion: [container integration semantics](OGBS-Spring-1.0.md). Implemented optional artifact `cn.managame:game-spring:1.0.0-SNAPSHOT`, Java 25, depending on game-boot, game-runtime, game-data and spring-context 7.0.9, with optional game-rpc. No Spring Boot dependency. Framework cores have no reverse Spring dependency.

This module is the Spring adapter over [game-boot](OGBS-Boot-Java-25-Specification-1.0.md): everything that joins components is a game-boot type, and everything here is discovery, property binding, injection and Spring lifecycle. Package `cn.managame.spring.*` contains no join of its own. Applications implementing `GameRpcCodec` or `ReadinessCheck` import them from `cn.managame.boot.*`.

## 1. Runtime registration

`cn.managame.spring.runtime.EnableGameRuntime` imports internal configuration and scans Handler/EventHandler/HttpHandler markers plus classes declaring or inheriting Cron methods. `String[] basePackages() default {}` aliases ComponentScan's basePackages; empty uses the annotated configuration's package. Standard Spring profiles/names apply. No additional Component annotation is needed. Existing Bean products with identifiable declared types also participate. Unknown FactoryBean products are not created to infer their task type. Discovery freezes at build, deduplicates exact target identity, and rejects prototype owners and identifiable Spring AOP proxies with IllegalArgumentException. Runtime validates annotation signatures, including private invalid methods. Annotation interfaces remain in Runtime without Spring annotations.

`GameRuntimeConfigurer.configure(GameRuntimeBuilder)` is a functional Bean extension. Configurers run in Spring order after handler/task discovery and all ProtocolRegistry Beans are collected and merged into one registry. Builder collection methods replace lists; an explicit configurer can therefore override discovered registrations. Applications provide Domain/RouteExecutor bindings and ingress identity policy. Prefer an executor Bean with destroyMethod="close" so failed startup releases caller-owned resources. Successful build transfers executor lifecycle to Runtime; built-in close is idempotent. Configuration creates the singleton named gameRuntime. It deliberately does **not** bind it as the process default for static event publication: that binding lasts as long as the process (RT-EVENT-05) while a bean factory runs once per context, so binding here would make a second context in one JVM collide with the first. The application calls `Events.bind(runtime)` at process startup instead. Handler constructors must not require the Runtime currently being built; use deferred providers only after startup.

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

The internal ContextClosedEvent listener has Ordered.HIGHEST_PRECEDENCE and supportsAsyncExecution=false. With Spring's ordinary synchronous event multicaster, it handles only its owning Context and runs the first two phases of `ShutdownBudget`: it sets its own rotation flag, waits the lead while still admitting, calls Runtime.shutdown, awaits termination for at most the budget's derived drain, logs a WARNING naming the unfinished count and the reserve it is leaving if draining did not complete, then calls Runtime.close. An interrupt during the lead or the drain is remembered, ends the wait early, and is re-asserted after Runtime is closed. Ordinary close listeners (such as demo TCP shutdown) run next, followed by managed HTTP lifecycle stop, Bean destruction and Data final flush. Do not install earlier/equal-priority resource-closing listeners, override this event ordering with a custom asynchronous multicaster, or close the Context from a Runtime Route. These would violate S-CLOSE-01. Resource cleanup on failed refresh follows Spring destruction. This module owns its managed HTTP listener, but no database pool or extra business threads. A listener may become reachable during the final refresh lifecycle phase; if another lifecycle fails, Spring closes the listener and Runtime, but this failed-refresh cleanup does not guarantee graceful drain or response delivery.

### 3.0 Shutdown budget

The budget type and its rules are [Boot §3](OGBS-Boot-Java-25-Specification-1.0.md#3-shutdown-budget); this section is only where the numbers come from. `ShutdownConfiguration` reads three properties from Spring Environment and calls `ShutdownBudget.of`, so an over-committed split fails at refresh.

| Property | Default | Phase |
| --- | --- | --- |
| `game.shutdown.budget-millis` | 25 000 | the whole budget; keep the orchestrator's grace period above it |
| `game.shutdown.lead-millis` | 0 | readiness reports failure while admission stays open |
| `game.shutdown.flush-reserve-millis` | 10 000 | reserved for Data's final persistence pass |
| *derived* | 15 000 | `drain = budget − lead − flushReserve`, for admitted work |

There is no drain property: configuring the drain on its own is what let the total drift, and the former `game.runtime.drain-timeout-millis` is gone.

`ShutdownConfiguration` is imported by both the Runtime and the Data configuration, so the single `gameShutdownBudget` Bean exists whenever either integration does and both read the same instance. A property that fails validation fails refresh with `IllegalArgumentException` naming the record component, so match it back: `budget-millis` is the total, `lead-millis` the lead, `flush-reserve-millis` the reserve. `DataConfiguration` applies `builder.shutdownTimeout(budget.flushReserve())` **before** the application's `GameDataConfigurer`s, so the reserve is a framework default an application that knows its storage better can still override; without the Runtime integration the budget Bean may be absent, and Data keeps its own default.

The rotation flag lives on the drain listener itself rather than in a Bean of its own, and the Runtime configuration contributes it as `gameRotationReadiness`, an ordinary `ReadinessCheck` Bean at `Ordered.HIGHEST_PRECEDENCE`. There is no shared state type between the two configurations: §5 collects contributed conditions like any other, and the ordering keeps `shutdown.rotation` ahead of the conditions derived from component snapshots.

Validation: [ShutdownBudgetTest](../../game-boot/src/test/java/cn/managame/boot/lifecycle/ShutdownBudgetTest.java) covers the derivation, the defaults and the splits that must fail; [MetricsAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/metrics/MetricsAssemblyTest.java) asserts the behaviour the budget exists for — readiness reporting 503 with `shutdown.rotation` named while `stats().accepting()` is still true, and admission closing only after the lead elapses.

### 3.1 Managed HTTP

`EnableGameRuntime` also imports internal HTTP assembly. The singleton `gameHttpServer` is an ordinary Network HttpServer Bean with destroyMethod="close". An internal SmartLifecycle starts it at phase Integer.MAX_VALUE after singleton initialization and Runtime build, and closes it on lifecycle stop. Runtime's synchronous drain listener runs before this stop during Context.close. Do not call start/close on the Bean manually. HTTP is one-shot like the underlying Network server: a stopped listener cannot be reopened by Context.start. Context.stop alone is not the Runtime graceful-shutdown operation; use Context.close. Invalid configuration remains a startup failure even when listener activation is disabled.

The default activation check uses discovered HttpHandler Beans, not annotation names inferred from unrelated classes. A configurer supplying HTTP owners outside Bean discovery must explicitly set enabled=true. With enabled=false, the Bean is constructed but unbound and creates no default network groups. Lifecycle uses the gameHttpServer qualifier; an unrelated Primary HttpServer Bean does not replace the managed listener. HTTP/1.1, origin-form targets, JSON/object results and the existing Runtime admission/Route/context contracts remain unchanged. All business methods run on the configured Runtime RouteExecutor; no Spring business executor is created.

Properties are read from Spring Environment; applications load their own property source without requiring Spring Boot:

| Property | Default | Boundary |
| --- | --- | --- |
| game.http.enabled | true when a HttpHandler Bean is discovered; otherwise false | Explicit true/false overrides discovery |
| game.http.port | 8080 | 0..65535; 0 selects an ephemeral port, accessible through HttpServer.localAddress() |
| game.http.bind-address | 127.0.0.1 | Nonblank host/address; unresolved/busy addresses fail binding |
| game.http.context-path | empty (root) | The value passed to `HttpContextPath`; its rules are [Boot §5](OGBS-Boot-Java-25-Specification-1.0.md#5-business-path-prefix), and an invalid value fails refresh |
| game.http.max-content-length | 1048576 | Positive byte count; oversize body follows Network 413 handling |
| game.http.max-initial-line-length | 4096 | Positive byte count |
| game.http.max-header-size | 8192 | Positive byte count |
| game.http.read-timeout-millis | 30000 | Nonnegative; 0 disables inbound inactivity timeout, not a business deadline |
| game.http.backlog | Network/Netty default | If set, positive SO_BACKLOG |
| game.http.keep-alive | Network/Netty default | If set, Boolean SO_KEEPALIVE (TCP socket setting, not HTTP Keep-Alive policy) |
| game.http.tcp-no-delay | Network/Netty default | If set, Boolean TCP_NODELAY |

`GameHttpConfigurer.configure(HttpServerBuilder)` Beans run in Spring order after property binding, before build; they may override network parameters, add native TLS/CORS/authentication pipeline handlers or supply caller-owned groups. The adapter installs Runtime dispatch last, so a configurer's handler/asyncHandler does not replace it. Extension handlers can still explicitly consume requests under Network's native pipeline contract. Configurers must not synchronously depend on the HTTP Bean being built. Borrowed groups remain caller-owned and must outlive listener closure. Without overrides, Network owns NIO boss/worker groups. TLS certificate configuration and arbitrary native options use this extension, not an automatically inferred HTTP security policy.

The dispatch chain this configuration installs, outermost first: the observability endpoints of §5 when that bean exists, then `HttpContextPath` with the configured prefix, then `runtime.http()`. HTTP context factories/codecs observe the relative URI; native pipeline extensions before dispatch see the original URI. There is no hot property reload.

Example with ordinary `@HttpHandler(domain=3, routeKey="routeKey")` and `@HttpMethod("/echo")`:

```properties
game.http.port=8080
game.http.context-path=/game
```

POST `/game/echo` with `{"routeKey":7,"text":"hello"}` selects Domain 3 / Key 7. Port/path do not supply a Domain, routing Key or authentication identity. Users need no HttpServer builder, asyncHandler adapter or close listener. [HttpAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/runtime/HttpAssemblyTest.java) validates automatic scanned-Bean startup, DTO/String/GET dispatch, prefix boundaries, size rejection, disabling/no-owner defaults, failed binding cleanup and shutdown admission/drain/port release. [DemoServicesTest](../../game-demo/src/test/java/cn/managame/demo/DemoServicesTest.java) exercises the same automatic listener with real Timer/Cron business execution. TLS/native security extensions, custom multicaster ordering and production load remain unverified by these tests.

<a id="managed-rpc"></a>

### 3.2 Managed RPC

The adapter, its codec contract and its composition hooks are [Boot §2](OGBS-Boot-Java-25-Specification-1.0.md#2-rpc-adapter). This section is the Spring wiring around it, and nothing else: an application that does not use Spring constructs `GameRpc` directly and gets the same behaviour.

`cn.managame.spring.rpc.EnableGameRpc` explicitly imports RPC assembly. It requires a GameRuntime Bean (normally EnableGameRuntime) and a thread-safe `cn.managame.boot.rpc.GameRpcCodec` Bean, and reads these properties:

| Property | Default | Boundary |
| --- | --- | --- |
| game.rpc.node-id | required | Nonzero uint32 bit pattern in an int |
| game.rpc.port | required | 0..65535 |
| game.rpc.bind-address | 127.0.0.1 | Nonblank |
| game.rpc.call-timeout-millis | Node default 5000 | Overrides the Node default when set |

Invalid properties fail startup. Add game-rpc explicitly: game-spring declares it optional, so HTTP/Data-only applications do not inherit it. No Boot, discovery, peer list inference or hot reload is introduced.

| Bean | Role |
| --- | --- |
| `gameRpc` | the `GameRpc` adapter, destroyMethod="close"; constructed from the properties above and the ordered `GameRpcConfigurer` Beans |
| `gameRpcNode` | `rpc.node()`, destroyMethod="" so the adapter stays the owner; exposes topology management and localAddress |
| `gameRpcLifecycle` | SmartLifecycle at Integer.MAX_VALUE; starts the Node after singleton initialization, and on stop calls `rpc.close()` |

`GameRpcConfigurer` Beans are collected with `ObjectProvider.orderedStream()`, so Spring order is the list order, and the adapter calls every hook — the last configurer is outermost. Ordering is the only thing this configuration decides about them. Context.close first drains Runtime through S-CLOSE-01, then closes RPC at lifecycle stop and idempotent destruction. Context.stop alone is not graceful Runtime shutdown; a stopped Node cannot restart. Do not start or close `gameRpcNode` yourself, and do not send raw call/notify/reply through it. Handler construction must defer the GameRpc lookup (`ObjectProvider` or a later setter) to avoid a Runtime/Handler/GameRpc construction cycle.

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

After Context.refresh, call `context.getBean(RpcNode.class).addPeer(2, address, 1)` under application topology policy; with routing, connect the Node this way to every declared Router. [RpcCompositionTest](../../game-spring/src/test/java/cn/managame/spring/rpc/RpcCompositionTest.java) verifies that Spring order becomes hook order and that decoration reaches the Runtime handler; the hook contract itself, including failed-attach cleanup, is verified container-free in [BootRpcCompositionTest](../../game-boot/src/test/java/cn/managame/boot/rpc/BootRpcCompositionTest.java). [RpcAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/rpc/RpcAssemblyTest.java) exercises actual TCP Call/Notify through the assembled Beans, identity/origin, object replies, unknown-command errors, immediate failure and exact Route restoration, plus remote-error/malformed-response routing, synchronous rejection reservation cleanup, timeout continuation drain, managed port release and one-shot lifecycle. Deployment security, discovery and production capacity remain outside this verification.

## 4. Usage and validation

The demo directly depends on game-spring and game-rpc. game-spring supplies transitive Boot/Runtime/Data/Network/Core and Spring Context APIs; explicit game-rpc supports the consolidated standalone RPC sample. Fory, JDBC driver and pool remain application dependencies. Other HTTP/Data-only applications still do not inherit game-spring's optional RPC dependency. `mvn -pl game-demo -am clean verify` builds the complete dependency reactor and runs application/standalone execution tests without preinstalled framework artifacts.

Configuration excerpts:

```java
@Configuration
@EnableGameRuntime(basePackages = "my.game")
@EnableGameData(basePackages = "my.game")
class GameConfig {
    // Provide DataSource, ProtocolRegistry, RouteExecutor and GameRuntimeConfigurer Beans.
}
```

The complete integration is [GameRuntimeConfig](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java) and [GameDataConfig](../../game-demo/src/main/java/cn/managame/demo/common/data/GameDataConfig.java). [CronBeansTest](../../game-spring/src/test/java/cn/managame/spring/runtime/CronBeansTest.java) covers method-only, inherited/default-interface and factory discovery, deduplication, startup rejection and real Route execution. [RuntimeLifecycleTest](../../game-spring/src/test/java/cn/managame/spring/runtime/RuntimeLifecycleTest.java) proves accepted work finishes before ordinary close listeners and resource destruction, and a replacement Context can bind Events again. Demo Repository tests validate initialized injection and all three bases using JDBC stubs; live databases and custom multicaster/proxy configurations remain unverified. Run root `mvn clean verify` for reactor boundaries; it includes game-boot, RPC and this optional adapter.

<a id="5-observability"></a>

## 5. Observability

Implements [Spring §5](OGBS-Spring-1.0.md#5-observability-composition). Package `cn.managame.spring.metrics`, opt-in with `@EnableGameMetrics`. The published types — `ReadinessCheck`, `ObservabilityEndpoints`, `FrameworkMetrics` — and everything they do are [Boot §4](OGBS-Boot-Java-25-Specification-1.0.md#4-observability); an application contributing a check implements `cn.managame.boot.metrics.ReadinessCheck`.

| Bean | Role |
| --- | --- |
| `gameMetricsRegistry` | `MetricsRegistry` publishing one `FrameworkMetrics` source per present component under the fixed prefixes `runtime`, `data`, `rpc`, `network`, each resolved through `ObjectProvider` so a missing component publishes nothing |
| `gameObservabilityEndpoints` | `ObservabilityEndpoints` holding the registry, the readiness checks and the two paths; §3.1 applies `wrap` when this bean exists |
| `gameRotationReadiness` | the `shutdown.rotation` condition, from the drain listener's rotation flag, at `Ordered.HIGHEST_PRECEDENCE`; published by the Runtime configuration, not this one |

Readiness checks are assembled in this order: every contributed `ReadinessCheck` bean in Spring order — which puts the framework's own `gameRotationReadiness` first, since it declares `Ordered.HIGHEST_PRECEDENCE`, and the application's beans after it — then `runtime.accepting` and `data.accepting`, derived here from the components that are present. So a failing application condition is named before `runtime.accepting`, which is usually what a reader wants: the specific cause ahead of the consequence.

| Property | Default | Boundary |
| --- | --- | --- |
| `game.metrics.path` | `/metrics` | Absolute, at least two characters, free of `?` and `#`, different from the other; otherwise the bean fails with IllegalArgumentException at refresh |
| `game.metrics.readiness-path` | `/readiness` | Same rules |

Both are matched before `game.http.context-path` is applied, so they are reachable at the server root whatever the business prefix is, and the prefix neither exposes nor hides them. Serving them needs the Runtime HTTP listener enabled; the registry Bean is available either way. A `NetworkServer` the application created without exposing it as a bean is its own to publish, with `registry.publish(prefix, source)`.

Validation: [MetricsAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/metrics/MetricsAssemblyTest.java) covers which prefixes are published when only Runtime is assembled, both paths served outside the context path through a real listener, an application `ReadinessCheck` turning the instance not ready and being named, configurable paths, and a draining Runtime reporting 503 while `/metrics` still answers. The endpoint behaviour itself, including path validation, is verified container-free in [ObservabilityEndpointsTest](../../game-boot/src/test/java/cn/managame/boot/metrics/ObservabilityEndpointsTest.java).
