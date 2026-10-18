# game-spring

**[English](README.md)** | [简体中文](README.zh-CN.md)

Optional plain Spring integration for Runtime, MySQL Data and RPC; Java 25, Spring Context 7.0.9, without Spring Boot. Core modules stay independent of Spring.

Use `@EnableGameRuntime(basePackages="my.game")` to discover Handler/EventHandler/HttpHandler and Cron owners. Provide ProtocolRegistry and GameRuntimeConfigurer Beans for protocols, Domains, executors and application identity policy. Use `@EnableGameData(basePackages="my.game")` with the state and log sources the application declares to initialize `@Repository` Beans; optional GameDataConfigurer controls codecs/write-back. Repositories retain field/setter injection and require Data's no-argument/direct generic base constraints.

HTTP Handler Beans also enable a managed HTTP/1.1 listener. Load these settings through an ordinary Spring property source; no builder, dispatch adapter or close listener is required:

```properties
game.http.port=8080
game.http.context-path=/game
```

An endpoint declared `@HttpMethod("/echo")` is available at `/game/echo`. Methods receive a DTO or String and may read HttpContext through Contexts. Domain/Route Key configuration remains application policy. No HTTP Handler means no listener by default; `game.http.enabled=false` disables automatic listening. Address/size/timeout/socket settings use `game.http.*`; optional GameHttpConfigurer supplies native extensions. See the [full property table and lifecycle contract](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.md#31-managed-http).

On Context closure, reject new Runtime work and wait for registered work/callbacks before closing Runtime, managed HTTP and downstream resources. Management-context synchronous closure is required; an unfinished callback can keep closure waiting. See the [complete demo configuration](../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java).

Contracts: [container integration specification](../docs/ogbs/OGBS-Spring-1.0.md), [Java development specification](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.md), [OGBS index](../docs/ogbs/README.md). Public packages are `cn.managame.spring.runtime` and `.data`; discovery/configuration/lifecycle helpers stay internal to their package.

Build `mvn -pl game-spring -am clean verify`. Tests cover Cron discovery, automatic HTTP/prefix/limits/startup failure and graceful lifecycle; Repository integration tests live in demo. Real database and arbitrary custom Spring proxy/multicaster configurations remain unverified.


With an explicit game-rpc dependency, add `@EnableGameRpc`, supply a thread-safe GameRpcCodec (for example Fory), and configure game.rpc.node-id/game.rpc.port. GameRpc decodes through the protocol registry and enters Runtime; use `gameRpc.reply(response)` and call from a Route with `gameRpc.call(..., RouteCallback)` to return completion to that Route. Spring manages Node lifecycle; the gameRpcNode Bean retains application peer policy. GameRpcConfigurer can also `decorate` the Runtime handler (for example with GameRouter/ServiceRouting) and `attach`/`detach` components around the Node lifecycle. game-rpc is optional and not inherited by HTTP/Data-only applications. Complete usage, construction-cycle, asynchronous-error and shutdown boundaries: [managed RPC](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.md#managed-rpc). Real TCP validation: [RpcAssemblyTest](src/test/java/cn/managame/spring/rpc/RpcAssemblyTest.java). Public integration packages also include `cn.managame.spring.rpc`.

`@EnableGameMetrics` publishes a reading source for every managed component that is present — under the fixed prefixes `runtime`, `data`, `rpc` and `network` — and serves two paths on the Runtime HTTP listener: `/metrics`, every reading as `name value` per line, and `/readiness`, 200 `ready` or 503 naming the conditions that failed. Both are absolute, so they ignore `game.http.context-path` and a probe does not need to know it, and both answer in front of business dispatch and outside every Route executor, so a saturated or draining instance still answers. The framework contributes the `runtime.accepting` and `data.accepting` conditions; an application adds its own as `ReadinessCheck` beans. Paths are configurable with `game.metrics.path` and `game.metrics.readiness-path`. See [Spring §5](../docs/ogbs/OGBS-Spring-1.0.md#5-observability-composition) and [Java §5](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.md#5-observability).

Shutdown is one budget rather than independent timeouts. `game.shutdown.budget-millis` (default 25 000, under the 30 seconds a Kubernetes grace period defaults to) is split into `game.shutdown.lead-millis` (default 0), the reserved `game.shutdown.flush-reserve-millis` (default 10 000) for Data's final write-back, and a derived drain for admitted work; a split that leaves the drain nothing fails at refresh. The lead is the phase readiness exists for: during it `/readiness` reports 503 with `shutdown.rotation` named while admission stays open, so a load balancer can stop sending before anything is refused, and a Timer or Cron due in that window still fires. Set it to at least one probe interval behind a load balancer, and leave it at 0 without one. Closing admission afterwards also stops the Timer and Cron wheels, deliberately: a fired Timer's submission is refused once admission is closed, so running them through the drain would discard rather than execute. See [Spring §2](../docs/ogbs/OGBS-Spring-1.0.md#2-lifecycle-and-failure) and [Java §3.0](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.md#30-shutdown-budget).
