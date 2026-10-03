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

The internal ContextClosedEvent listener has Ordered.HIGHEST_PRECEDENCE and supportsAsyncExecution=false. With Spring's ordinary synchronous event multicaster, it handles only its owning Context, calls Runtime.shutdown, repeatedly awaits 30-second intervals without a total timeout, then Runtime.close. Interruptions do not abandon draining; interrupt status is restored afterward. Ordinary close listeners (such as demo TCP/HTTP shutdown) run next, followed by Bean destruction and Data final flush. Do not install earlier/equal-priority resource-closing listeners, override this event ordering with a custom asynchronous multicaster, or close the Context from a Runtime Route. These would violate S-CLOSE-01. Resource cleanup on failed refresh follows Spring destruction, where no business admission should yet be exposed. This module owns no transport, database pool or extra business threads.

## 4. Usage and validation

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
