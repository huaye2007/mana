# game-spring

**[English](README.md)** | [简体中文](README.zh-CN.md)

Optional plain Spring integration for Runtime and MySQL Data; Java 25, Spring Context 7.0.9, without Spring Boot. Core modules stay independent of Spring.

Use `@EnableGameRuntime(basePackages="my.game")` to discover Handler/EventHandler/HttpHandler and Cron owners. Provide ProtocolProvider and GameRuntimeConfigurer Beans for protocols, Domains, executors and application identity policy. Use `@EnableGameData(basePackages="my.game")` with an application-owned DataSource to initialize `@Repository` Beans; optional GameDataConfigurer controls codecs/write-back. Repositories retain field/setter injection and require Data's no-argument/direct generic base constraints.

On Context closure, reject new Runtime work and wait for registered work/callbacks before closing Runtime and downstream resources. Management-context synchronous closure is required; an unfinished callback can keep closure waiting. See the [complete demo configuration](../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java).

Contracts: [container integration specification](../docs/ogbs/OGBS-Spring-1.0.md), [Java development specification](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.md), [OGBS index](../docs/ogbs/README.md). Public packages are `cn.managame.spring.runtime` and `.data`; discovery/configuration/lifecycle helpers stay internal to their package.

Build `mvn -pl game-spring -am clean verify`. Tests cover Cron discovery and graceful lifecycle; Repository integration tests live in demo. Real database and arbitrary custom Spring proxy/multicaster configurations remain unverified.
