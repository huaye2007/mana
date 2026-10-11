# game-spring

[English](README.md) | **[简体中文](README.zh-CN.md)**

Runtime、MySQL Data 与 RPC 的可选普通 Spring 集成，Java 25、Spring Context 7.0.9，不使用 Spring Boot，核心模块保持不依赖 Spring。

使用 `@EnableGameRuntime(basePackages="my.game")` 发现 Handler/EventHandler/HttpHandler 和 Cron 对象，提供 ProtocolProvider 与 GameRuntimeConfigurer Bean 配置协议、Domain、执行器及业务身份策略。`@EnableGameData(basePackages="my.game")` 配合应用持有的 DataSource 初始化 `@Repository` Bean，可选 GameDataConfigurer 配置 codec/回写。Repository 保留字段/setter 注入，仍需满足 Data 的无参构造及直接泛型基类约束。

HTTP Handler Bean 同时启用托管 HTTP/1.1 监听器，通过普通 Spring 属性源加载以下配置即可，无需 builder、分发适配或关闭监听器：

```properties
game.http.port=8080
game.http.context-path=/game
```

声明 `@HttpMethod("/echo")` 的端点通过 `/game/echo` 访问。方法直接接收 DTO 或 String，需要时通过 Contexts 获取 HttpContext。Domain/Route Key 配置仍由应用决定。没有 HTTP Handler 默认不监听；`game.http.enabled=false` 禁用自动监听。地址/大小/超时/socket 参数使用 `game.http.*`，可选 GameHttpConfigurer 提供原生扩展。见 [完整属性表和生命周期契约](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#31-托管-http)。

Context 关闭时先拒绝新 Runtime 工作，等待已登记工作/回调，再关闭 Runtime、托管 HTTP 和下游资源。必须在管理上下文同步关闭，未完成回调可能使关闭继续等待。见 [完整 demo 配置](../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java)。

契约：[容器集成规范](../docs/ogbs/OGBS-Spring-1.0.zh-CN.md)、[Java 开发规范](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.zh-CN.md)、[OGBS 索引](../docs/ogbs/README.zh-CN.md)。公开包为 `cn.managame.spring.runtime` 和 `.data`，发现/配置/生命周期辅助类型保持包内封装。

构建使用 `mvn -pl game-spring -am clean verify`，测试覆盖 Cron 发现、自动 HTTP/前缀/限制/启动失败及平滑生命周期，Repository 集成测试位于 demo。真实数据库及任意自定义 Spring 代理/广播配置仍未验证。


添加 game-rpc 依赖后，可使用 `@EnableGameRpc`，提供线程安全的 GameRpcCodec（例如 Fory），配置 game.rpc.node-id、game.rpc.port。GameRpc 按协议注册表解码并进入 Runtime；业务用 `gameRpc.reply(response)` 回复，在 Route 内用 `gameRpc.call(..., RouteCallback)` 发起调用并回到原 Route。Node 生命周期由 Spring 管理，Peer 配置仍由应用通过 gameRpcNode Bean 决定。GameRpcConfigurer 还可以 `decorate` Runtime handler（例如用 GameRouter/ServiceRouting 包装），并通过 `attach`/`detach` 跟随 Node 生命周期启动和关闭组件。game-rpc 是 optional 依赖，HTTP/Data-only 应用不传递引入。完整使用、构造循环、异步错误与关闭边界见 [托管 RPC](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#managed-rpc)，真实 TCP 验证见 [RpcAssemblyTest](src/test/java/cn/managame/spring/rpc/RpcAssemblyTest.java)。公开集成包还包含 `cn.managame.spring.rpc`。
