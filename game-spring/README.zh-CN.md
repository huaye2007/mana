# game-spring

[English](README.md) | **[简体中文](README.zh-CN.md)**

Runtime、MySQL Data 与 RPC 的可选普通 Spring 集成，Java 25、Spring Context 7.0.9，不使用 Spring Boot，核心模块保持不依赖 Spring。

本模块是 [game-boot](../game-boot/README.zh-CN.md) 之上的 Spring 适配层：发现、属性绑定、注入与 Spring 生命周期。拼装本身——RPC 适配器、关闭预算、可观测性端点、路径前缀——都是 game-boot 类型，因此不使用容器的应用用同一份代码拼装出同一个服务器。

使用 `@EnableGameRuntime(basePackages="my.game")` 发现 Handler/EventHandler/HttpHandler 和 Cron 对象，提供 ProtocolRegistry 与 GameRuntimeConfigurer Bean 配置协议、Domain、执行器及业务身份策略。`@EnableGameData(basePackages="my.game")` 配合应用持有的状态与日志来源初始化 `@Repository` Bean，可选 GameDataConfigurer 配置 codec/回写。Repository 保留字段/setter 注入，仍需满足 Data 的无参构造及直接泛型基类约束。

HTTP Handler Bean 同时启用托管 HTTP/1.1 监听器，通过普通 Spring 属性源加载以下配置即可，无需 builder、分发适配或关闭监听器：

```properties
game.http.port=8080
game.http.context-path=/game
```

声明 `@HttpMethod("/echo")` 的端点通过 `/game/echo` 访问。方法直接接收 DTO 或 String，需要时通过 Contexts 获取 HttpContext。Domain/Route Key 配置仍由应用决定。没有 HTTP Handler 默认不监听；`game.http.enabled=false` 禁用自动监听。地址/大小/超时/socket 参数使用 `game.http.*`，可选 GameHttpConfigurer 提供原生扩展。见 [完整属性表和生命周期契约](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#31-托管-http)。

Context 关闭时先拒绝新 Runtime 工作，等待已登记工作/回调，再关闭 Runtime、托管 HTTP 和下游资源。必须在管理上下文同步关闭，未完成回调可能使关闭继续等待。见 [完整 demo 配置](../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java)。

契约：[容器集成规范](../docs/ogbs/OGBS-Spring-1.0.zh-CN.md)、[Java 开发规范](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.zh-CN.md)、本模块所接线类型的[服务器组合规范](../docs/ogbs/OGBS-Boot-1.0.zh-CN.md)、[OGBS 索引](../docs/ogbs/README.zh-CN.md)。公开包为 `cn.managame.spring.runtime` 和 `.data`，发现/配置/生命周期辅助类型保持包内封装。

构建使用 `mvn -pl game-spring -am clean verify`，测试覆盖 Cron 发现、自动 HTTP/前缀/限制/启动失败及平滑生命周期，Repository 集成测试位于 demo。真实数据库及任意自定义 Spring 代理/广播配置仍未验证。


添加 game-rpc 依赖后，可使用 `@EnableGameRpc`，提供线程安全的 `cn.managame.boot.rpc.GameRpcCodec` Bean（例如 Fory），配置 game.rpc.node-id、game.rpc.port。Spring 随后用这些属性与有序的 GameRpcConfigurer Bean 构建 game-boot 的 [GameRpc](../game-boot/README.zh-CN.md#rpc-到-runtime)，暴露 `gameRpcNode` 供应用决定 Peer 策略，并持有 start/stop 回调。适配器所做的一切——按协议注册表解码、`reply`、`call` 把完成回调带回发起的那条 Route、钩子顺序（含用于 GameRouter/ServiceRouting 的 `decorate`）、attach 失败后的清理——都属于 game-boot，没有 Spring 时完全相同。game-rpc 是 optional 依赖，HTTP/Data-only 应用不传递引入。Bean 名称、属性、构造循环与关闭边界见 [托管 RPC](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#managed-rpc)，经 Bean 的真实 TCP 验证见 [RpcAssemblyTest](src/test/java/cn/managame/spring/rpc/RpcAssemblyTest.java)。公开集成包还包含 `cn.managame.spring.rpc`。

`@EnableGameMetrics` 接线 game-boot 的可观测性类型，为每个存在的受管组件各发布一个读数源——挂在固定前缀 `runtime`、`data`、`rpc`、`network` 下——并在 Runtime HTTP 监听端提供两个路径：`/metrics` 把每条读数按 `name value` 各占一行输出，`/readiness` 返回 200 `ready` 或 503 并点名未成立的条件。两者都是绝对路径，因此忽略 `game.http.context-path`，探针也不需要知道它；两者都在业务分发之前、所有 Route 执行器之外作答，于是饱和中或摘流中的实例依然答得出。框架贡献 `runtime.accepting` 与 `data.accepting` 两条条件；应用以 `cn.managame.boot.metrics.ReadinessCheck` Bean 追加自己的。路径可用 `game.metrics.path` 与 `game.metrics.readiness-path` 配置。行为见 [Boot §4](../docs/ogbs/OGBS-Boot-Java-25-Specification-1.0.zh-CN.md#4-可观测性)，接线见 [Spring §5](../docs/ogbs/OGBS-Spring-1.0.zh-CN.md#5-可观测性组合) 与 [Java §5](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#5-可观测性)。

关停是一份预算，而不是几个互不相干的超时。`game.shutdown.budget-millis`（默认 25 000，在 Kubernetes 宽限期默认的 30 秒之下）切分为 `game.shutdown.lead-millis`（默认 0）、为 Data 最终写回保留的 `game.shutdown.flush-reserve-millis`（默认 10 000），以及推导出来给已接纳工作的摘流时间；让摘流分不到时间的切分会在 refresh 时失败。提前量正是就绪存在的理由：在这段时间里 `/readiness` 报 503 并点名 `shutdown.rotation`，而准入保持开放，于是负载均衡器可以在任何请求被拒之前停止发送，该窗口内到期的定时器或 Cron 仍会触发。位于负载均衡器之后时请把它设为至少一个探测间隔，没有探测时保持 0。此后关闭准入会同时停掉定时器与 Cron 轮，这是有意的：准入关闭之后已触发的定时器提交会被拒绝，因此让它们跑过摘流只会丢弃而不是执行。预算本身属于 game-boot，本模块只读取这三个属性并持有触发点。见 [Boot §3](../docs/ogbs/OGBS-Boot-Java-25-Specification-1.0.zh-CN.md#3-关闭预算)、[Spring §2](../docs/ogbs/OGBS-Spring-1.0.zh-CN.md#2-生命周期与失败) 与 [Java §3.0](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#30-关闭预算)。
