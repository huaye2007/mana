# game-spring

**[English](README.md)** | [简体中文](README.zh-CN.md)

Optional plain Spring integration for Runtime, MySQL Data and RPC; Java 25, Spring Context 7.0.9, without Spring Boot. Core modules stay independent of Spring.

Use `@EnableGameRuntime(basePackages="my.game")` to discover Handler/EventHandler/HttpHandler and Cron owners. Provide ProtocolProvider and GameRuntimeConfigurer Beans for protocols, Domains, executors and application identity policy. Use `@EnableGameData(basePackages="my.game")` with an application-owned DataSource to initialize `@Repository` Beans; optional GameDataConfigurer controls codecs/write-back. Repositories retain field/setter injection and require Data's no-argument/direct generic base constraints.

HTTP Handler Beans also enable a managed HTTP/1.1 listener. Load these settings through an ordinary Spring property source; no builder, dispatch adapter or close listener is required:

```properties
game.http.port=8080
game.http.context-path=/game
```

An endpoint declared `@HttpMethod("/echo")` is available at `/game/echo`. Methods receive a DTO or String and may read HttpContext through Contexts. Domain/Route Key configuration remains application policy. No HTTP Handler means no listener by default; `game.http.enabled=false` disables automatic listening. Address/size/timeout/socket settings use `game.http.*`; optional GameHttpConfigurer supplies native extensions. See the [full property table and lifecycle contract](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.md#31-managed-http).

On Context closure, reject new Runtime work and wait for registered work/callbacks before closing Runtime, managed HTTP and downstream resources. Management-context synchronous closure is required; an unfinished callback can keep closure waiting. See the [complete demo configuration](../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java).

Contracts: [container integration specification](../docs/ogbs/OGBS-Spring-1.0.md), [Java development specification](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.md), [OGBS index](../docs/ogbs/README.md). Public packages are `cn.managame.spring.runtime` and `.data`; discovery/configuration/lifecycle helpers stay internal to their package.

Build `mvn -pl game-spring -am clean verify`. Tests cover Cron discovery, automatic HTTP/prefix/limits/startup failure and graceful lifecycle; Repository integration tests live in demo. Real database and arbitrary custom Spring proxy/multicaster configurations remain unverified.


With an explicit game-rpc dependency, add `@EnableGameRpc`, supply a thread-safe GameRpcCodec (for example Fory), and configure game.rpc.node-id/game.rpc.port. GameRpc decodes through the protocol registry and enters Runtime; use `gameRpc.reply(response)` and call from a Route with `gameRpc.call(..., RouteCallback)` to return completion to that Route. Spring manages Node lifecycle; the gameRpcNode Bean retains application peer policy. game-rpc is optional and not inherited by HTTP/Data-only applications. Complete usage, construction-cycle, asynchronous-error and shutdown boundaries: [managed RPC](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.md#managed-rpc). Real TCP validation: [RpcAssemblyTest](src/test/java/cn/managame/spring/rpc/RpcAssemblyTest.java). Public integration packages also include `cn.managame.spring.rpc`.
