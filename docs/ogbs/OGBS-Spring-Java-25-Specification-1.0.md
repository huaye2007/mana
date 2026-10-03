# OGBS Spring Java 25 Development Specification 1.0

**[English](OGBS-Spring-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md)

Companion: [container integration semantics](OGBS-Spring-1.0.md). Implemented optional artifact `cn.managame:game-spring:1.0.0-SNAPSHOT`, Java 25, depending on game-runtime, game-data and spring-context 7.0.9. No Spring Boot dependency. Framework cores have no reverse Spring dependency.

## 1. Runtime registration

`cn.managame.spring.runtime.EnableGameRuntime` imports internal configuration and scans Handler/EventHandler/HttpHandler markers plus classes declaring or inheriting Cron methods. `String[] basePackages() default {}` aliases ComponentScan's basePackages; empty uses the annotated configuration's package. Standard Spring profiles/names apply. No additional Component annotation is needed. Existing Bean products with identifiable declared types also participate. Unknown FactoryBean products are not created to infer their task type. Discovery freezes at build, deduplicates exact target identity, and rejects prototype owners and identifiable Spring AOP proxies with IllegalArgumentException. Runtime validates annotation signatures, including private invalid methods. Annotation interfaces remain in Runtime without Spring annotations.

`GameRuntimeConfigurer.configure(GameRuntimeBuilder)` is a functional Bean extension. Configurers run in Spring order after handler/task discovery and all ProtocolProvider Beans are collected. Builder collection methods replace lists; an explicit configurer can therefore override discovered registrations. Applications provide Domain/RouteExecutor bindings and ingress identity policy. Prefer an executor Bean with destroyMethod="close" so failed startup releases caller-owned resources. Successful build transfers executor lifecycle to Runtime; built-in close is idempotent. Configuration creates the singleton named gameRuntime, binds Events for external static publication, and closes it on event-binding conflict. Handler constructors must not require the Runtime currently being built; use deferred providers only after startup.

## 2. Data registration

`cn.managame.spring.data.EnableGameData` scans Repository markers in basePackages with the same default-package behavior and imports internal configuration. An application-owned DataSource Bean is required. Data configuration defaults to GameDataBuilder.mysql(dataSource), discovers known singleton Beans directly extending SingleRepository, GroupRepository or LogRepository and registers their concrete types. A static BeanFactoryPostProcessor replaces their instance suppliers with `gameData.repository(type)` and adds a gameData dependency, retaining names/qualifiers and field/setter injection. Data creates the no-argument instance once and initializes it before Spring injection callbacks. Constructor injection is not supported. Other Repository classes outside these three bases are ordinary Spring Beans. Prototype Data repositories reject before database access.

`GameDataConfigurer.configure(GameDataBuilder)` Beans run in Spring order after DataSource/Repository configuration and before build, for JSON codecs, write-back/error policy or explicit mapper overrides. Configurer dependencies must not require the still-building Data/Repository. The singleton gameData has destroyMethod="close"; it borrows DataSource and does not close the application pool.

## 3. Shutdown

The internal ContextClosedEvent listener has Ordered.HIGHEST_PRECEDENCE and supportsAsyncExecution=false. With Spring's ordinary synchronous event multicaster, it handles only its owning Context, calls Runtime.shutdown, repeatedly awaits 30-second intervals without a total timeout, then Runtime.close. Interruptions do not abandon draining; interrupt status is restored afterward. Ordinary close listeners (such as demo TCP shutdown) run next, followed by managed HTTP lifecycle stop, Bean destruction and Data final flush. Do not install earlier/equal-priority resource-closing listeners, override this event ordering with a custom asynchronous multicaster, or close the Context from a Runtime Route. These would violate S-CLOSE-01. Resource cleanup on failed refresh follows Spring destruction. This module owns its managed HTTP listener, but no database pool or extra business threads. A listener may become reachable during the final refresh lifecycle phase; if another lifecycle fails, Spring closes the listener and Runtime, but this failed-refresh cleanup does not guarantee graceful drain or response delivery.

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

## 4. Usage and validation

The demo declares game-spring as its only direct framework dependency, using its transitive Runtime/Data/Network/Core and Spring Context APIs. Fory, the JDBC driver and connection pool remain application dependencies. RPC is outside this dependency graph; applications that actually use RPC add game-rpc explicitly. `mvn -pl game-demo -am clean verify` builds the complete demo dependency reactor without requiring preinstalled framework artifacts or compiling unrelated RPC tests.

Configuration excerpts:

```java
@Configuration
@EnableGameRuntime(basePackages = "my.game")
@EnableGameData(basePackages = "my.game")
class GameConfig {
    // Provide DataSource, ProtocolProvider, RouteExecutor and GameRuntimeConfigurer Beans.
}
```

The complete integration is [GameRuntimeConfig](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java) and [GameDataConfig](../../game-demo/src/main/java/cn/managame/demo/common/data/GameDataConfig.java). [CronBeansTest](../../game-spring/src/test/java/cn/managame/spring/runtime/CronBeansTest.java) covers method-only, inherited/default-interface and factory discovery, deduplication, startup rejection and real Route execution. [RuntimeLifecycleTest](../../game-spring/src/test/java/cn/managame/spring/runtime/RuntimeLifecycleTest.java) proves accepted work finishes before ordinary close listeners and resource destruction, and a replacement Context can bind Events again. Demo Repository tests validate initialized injection and all three bases using JDBC stubs; live databases and custom multicaster/proxy configurations remain unverified. Run root `mvn clean verify` for reactor boundaries; existing RPC test API inconsistencies remain a separately recorded blocker.
