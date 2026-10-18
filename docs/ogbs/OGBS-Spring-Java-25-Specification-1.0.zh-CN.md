# OGBS Spring Java 25 开发规范 1.0

**[English](OGBS-Spring-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md)

对应 [容器集成语义](OGBS-Spring-1.0.zh-CN.md)。已实现可选 artifact `cn.managame:game-spring:1.0.0-SNAPSHOT`，使用 Java 25，依赖 game-boot、game-runtime、game-data 及 spring-context 7.0.9，可选依赖 game-rpc，不依赖 Spring Boot，核心组件不反向依赖 Spring。

本模块是 [game-boot](OGBS-Boot-Java-25-Specification-1.0.zh-CN.md) 之上的 Spring 适配层：凡是拼装组件的东西都是 game-boot 类型，这里的一切都是发现、属性绑定、注入与 Spring 生命周期。`cn.managame.spring.*` 包不含任何属于自己的拼装。实现 `GameRpcCodec` 或 `ReadinessCheck` 的应用从 `cn.managame.boot.*` 导入它们。

## 1. Runtime 注册

`cn.managame.spring.runtime.EnableGameRuntime` 导入内部配置，扫描 Handler/EventHandler/HttpHandler 标记及声明/继承 Cron 方法的类。`String[] basePackages() default {}` 对应 ComponentScan.basePackages，空值使用被注解配置类的包，采用标准 Spring profile/名称规则，不需额外 Component。声明类型可识别的既有 Bean 产物也参与；不为推断任务类型初始化未知 FactoryBean 产物。发现结果构建时冻结，按目标对象身份去重；prototype 对象和可识别 Spring AOP 代理以 IllegalArgumentException 拒绝。Runtime 校验注解签名，包括非法 private 方法。Runtime 注解不包含 Spring 注解。

函数式 Bean 扩展 `GameRuntimeConfigurer.configure(GameRuntimeBuilder)` 在收集 Handler/任务及全部 ProtocolRegistry Bean（合并为一个注册表）后按 Spring 顺序执行。Builder 集合方法替换列表，显式 configurer 可覆盖扫描结果。应用提供 Domain/RouteExecutor 绑定及接入身份策略。执行器宜为 destroyMethod="close" 的 Bean，确保启动失败后释放调用方资源；成功构建转移生命周期至 Runtime，内置 close 幂等。配置创建名为 gameRuntime 的单例。它刻意**不**把该实例绑定为静态事件发布的进程默认值：这项绑定的有效期是整个进程（RT-EVENT-05），而 Bean 工厂每个上下文执行一次，在此绑定会使同一 JVM 中的第二个上下文与第一个冲突。改由应用在进程启动时调用 `Events.bind(runtime)`。Handler 构造器不能依赖正在构造的 Runtime，延迟 provider 只能启动完成后使用。

## 2. Data 注册

`cn.managame.spring.data.EnableGameData` 在 basePackages 扫描 Repository，采用相同的默认包规则并导入内部配置。状态与日志是相互独立的存储（D-LOG-04），集成为两者各解析一个来源，绝不由一方推导另一方；当由 EntityMapper Bean 承载状态时，完全不需要 DataSource Bean。随后发现已知单例 Bean 中直接继承 SingleRepository、GroupRepository 或 LogRepository 的具体类型并注册，存在 EntityMapper Bean 时状态绑定到它。静态 BeanFactoryPostProcessor 把实例供应器替换成 `gameData.repository(type)` 并添加 gameData 依赖，保留名称/限定符及字段/setter 注入。Data 仅创建一次无参实例，初始化后才进入 Spring 注入回调；不支持构造器注入。未继承这三个基类的其他 Repository 是普通 Spring Bean。prototype Data Repository 在数据库访问前拒绝。

| Bean | 服务的存储 |
| --- | --- |
| 未标注任一限定注解的唯一 DataSource | 两个存储：只有一个 DataSource 即表示只有一个数据库 |
| `@GameStateSource` DataSource | `mysql(...)`，仅状态 |
| `@GameLogSource` DataSource | `logMysql(...)`，仅日志 |
| EntityMapper Bean，例如 MongoEntityMapper | 所有已发现状态 Repository 的状态后端 |

只要出现任一限定注解，各存储就只接受为它指定的来源，沉默不会把日志写进状态库：只有 `@GameStateSource`、存在日志 Repository 而没有 `@GameLogSource` 时，build 抛 IllegalArgumentException 并指出 `logMysql(DataSource)`，此时尚未打开任何 DataSource。同一存储有多个候选时需用 `@Primary` 或该存储的限定注解；同时标注两个限定注解的 DataSource 被拒绝。解析阶段不报告缺失的后端，因为 configurer 仍可能提供。

验证：[StoreSourceTest](../../game-spring/src/test/java/cn/managame/spring/data/StoreSourceTest.java) 覆盖没有任何 DataSource Bean 时由 EntityMapper Bean 承载状态，以及在打开任何 DataSource 之前就拒绝把 `@GameStateSource` 提供给日志；[DataRepositoryRegistrationTest](../../game-demo/src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java) 覆盖未标注的唯一 DataSource 同时服务两个存储。

`GameDataConfigurer.configure(GameDataBuilder)` Bean 在来源/Repository 配置后、build 前按 Spring 顺序执行，可配置 JSON codec、回写/错误策略，或把已发现的 Repository 改接到别的后端——这一点成立是因为指定了后端的注册会替换集成自己的注册。configurer 依赖不能要求正在构建的 Data/Repository。gameData 单例采用 destroyMethod="close"，借用全部来源，既不关闭应用连接池，也不关闭 Mapper 的客户端。

## 3. 停机

内部 ContextClosedEvent 监听器使用 Ordered.HIGHEST_PRECEDENCE 与 supportsAsyncExecution=false。在 Spring 默认同步事件广播下，它只处理自己所属的 Context，并运行 `ShutdownBudget` 的前两个阶段：标记 `ShutdownPhase`，在仍然接纳的状态下等待前导时间，调用 Runtime.shutdown，按预算推导出的排空时间等待终止，若未排空完成则记录 WARNING 并指出未完成数量与留给刷写的保留时间，然后调用 Runtime.close。前导或排空期间的中断会被记住、提前结束等待，并在 Runtime 关闭后重新置位。随后依次是普通关闭监听器（如 demo 的 TCP 停机）、托管 HTTP 生命周期 stop、Bean 销毁与 Data 最终刷写。不要安装更早或同优先级的资源关闭监听器，不要用自定义异步广播覆盖此事件顺序，也不要在 Runtime Route 上关闭 Context，这些都会违反 S-CLOSE-01。refresh 失败的资源清理遵循 Spring 销毁流程。本模块持有自己的托管 HTTP 监听器，但不持有数据库连接池或额外业务线程。监听器可能在 refresh 的最后生命周期阶段变为可达；若另一个生命周期失败，Spring 会关闭监听器与 Runtime，但这种 refresh 失败清理不保证优雅排空或响应投递。

### 3.0 关闭预算

预算类型及其规则见 [Boot §3](OGBS-Boot-Java-25-Specification-1.0.zh-CN.md#3-关闭预算)；本节只说明数值从哪里来。`ShutdownConfiguration` 从 Spring Environment 读取三个属性并调用 `ShutdownBudget.of`，因此超额切分在 refresh 时就失败。

| 配置项 | 默认值 | 阶段 |
| --- | --- | --- |
| `game.shutdown.budget-millis` | 25 000 | 总预算；部署需让编排器宽限期高于它 |
| `game.shutdown.lead-millis` | 0 | 就绪报告失败而准入仍开放 |
| `game.shutdown.flush-reserve-millis` | 10 000 | 为 Data 最后一次持久化保留 |
| *推导* | 15 000 | `排空 = 总预算 − 前导 − 刷写保留`，供已准入工作使用 |

没有排空配置项：单独配置排空正是让总和漂移的原因，原先的 `game.runtime.drain-timeout-millis` 已移除。

`ShutdownConfiguration` 同时被 Runtime 与 Data 配置导入，因此只要任一集成存在，`ShutdownBudget` 与 `ShutdownPhase` 就存在，且两者读到同一个实例。`DataConfiguration` 在应用的 `GameDataConfigurer` **之前**应用 `builder.shutdownTimeout(budget.flushReserve())`，于是保留时间是框架默认值，更了解自己存储的应用仍可覆盖；没有 Runtime 集成时预算 Bean 可能不存在，Data 保持自己的默认值。§5 从 `ShutdownPhase` 贡献 `shutdown.rotation` 就绪条件并置于列表首位，使退出轮转的实例报告的是这件事本身而不是它的后果。

验证：[ShutdownBudgetTest](../../game-boot/src/test/java/cn/managame/boot/lifecycle/ShutdownBudgetTest.java) 覆盖推导、默认值与应当失败的切分；[MetricsAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/metrics/MetricsAssemblyTest.java) 验证预算存在的意义——`stats().accepting()` 仍为 true 时就绪已返回 503 并指名 `shutdown.rotation`，而准入只在前导结束后才关闭。

### 3.1 托管 HTTP

`EnableGameRuntime` 同时导入内部 HTTP 装配。单例 `gameHttpServer` 是普通 Network HttpServer Bean，采用 destroyMethod="close"。内部 SmartLifecycle 在单例初始化和 Runtime 构建完成后，以 Integer.MAX_VALUE 阶段启动监听，在生命周期 stop 时关闭。Context.close 时 Runtime 同步排空监听器先于此 stop 执行。不要手动调用 Bean 的 start/close。HTTP 与底层 Network 服务一样只启动一次，停止的监听器不能通过 Context.start 重新打开。Context.stop 本身不是 Runtime 优雅停机操作，请使用 Context.close。即使禁用监听，无效配置仍导致启动失败。

默认启用检查依据发现的 HttpHandler Bean，不根据无关类的注解名称推断。configurer 若提供 Bean 发现范围外的 HTTP 对象，需显式设置 enabled=true。enabled=false 时仍构造 Bean，但不绑定端口、不创建默认网络线程组。生命周期按 gameHttpServer 限定符选择监听器，无关的 Primary HttpServer Bean 不替代托管监听器。HTTP/1.1、origin-form 请求目标、JSON/对象结果及既有 Runtime 接纳/Route/Context 契约保持不变。所有业务方法使用配置的 Runtime RouteExecutor，不新增 Spring 业务执行器。

配置从 Spring Environment 读取，应用加载自己的属性源即可，无需 Spring Boot：

| 配置项 | 默认值 | 边界 |
| --- | --- | --- |
| game.http.enabled | 发现 HttpHandler Bean 时为 true，否则 false | 显式 true/false 覆盖发现结果 |
| game.http.port | 8080 | 0..65535；0 使用随机端口，通过 HttpServer.localAddress() 获取 |
| game.http.bind-address | 127.0.0.1 | 非空白主机/地址；无法解析或占用导致绑定失败 |
| game.http.context-path | 空（根路径） | 传给 `HttpContextPath` 的取值，其规则见 [Boot §5](OGBS-Boot-Java-25-Specification-1.0.zh-CN.md#5-业务路径前缀)，非法取值导致 refresh 失败 |
| game.http.max-content-length | 1048576 | 正字节数；body 超限采用 Network 的 413 处理 |
| game.http.max-initial-line-length | 4096 | 正字节数 |
| game.http.max-header-size | 8192 | 正字节数 |
| game.http.read-timeout-millis | 30000 | 非负，0 禁用入站无数据超时，不是业务截止时间 |
| game.http.backlog | Network/Netty 默认值 | 设置时为正数 SO_BACKLOG |
| game.http.keep-alive | Network/Netty 默认值 | 设置时为 Boolean SO_KEEPALIVE（TCP socket 设置，不是 HTTP Keep-Alive 策略） |
| game.http.tcp-no-delay | Network/Netty 默认值 | 设置时为 Boolean TCP_NODELAY |

`GameHttpConfigurer.configure(HttpServerBuilder)` Bean 在属性绑定之后、build 之前按 Spring 顺序执行，可覆盖网络参数、添加原生 TLS/CORS/鉴权 pipeline Handler 或传入调用方线程组。适配器最后安装 Runtime dispatch，因此 configurer 的 handler/asyncHandler 不替换它；扩展 Handler 仍可按 Network 原生 pipeline 契约显式消费请求。configurer 不能同步依赖正在构建的 HTTP Bean。借用线程组仍由调用方持有，必须存活到监听关闭之后。没有覆盖时由 Network 持有 NIO boss/worker 线程组。TLS 证书及任意原生选项使用此扩展，不自动推断 HTTP 安全策略。

本配置安装的分发链，由外到内：存在该 Bean 时是 §5 的可观测性端点，然后是带配置前缀的 `HttpContextPath`，然后是 `runtime.http()`。HTTP Context 工厂/codec 观察相对 URI，dispatch 前的原生 pipeline 扩展观察原始 URI。不支持热重载属性。

普通 `@HttpHandler(domain=3, routeKey="routeKey")` 和 `@HttpMethod("/echo")` 示例：

```properties
game.http.port=8080
game.http.context-path=/game
```

POST `/game/echo`，body 为 `{"routeKey":7,"text":"hello"}`，选择 Domain 3 / Key 7。端口/路径不提供 Domain、路由 Key 或鉴权身份。用户无需 HttpServer builder、asyncHandler 适配或关闭监听器。[HttpAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/runtime/HttpAssemblyTest.java) 验证自动扫描 Bean 启动、DTO/String/GET 分发、前缀边界、大小拒绝、禁用/无对象默认值、绑定失败清理及停机接纳/排空/端口释放。[DemoServicesTest](../../game-demo/src/test/java/cn/managame/demo/DemoServicesTest.java) 通过相同自动监听器验证真实 Timer/Cron 业务执行。这些测试不验证 TLS/原生安全扩展、自定义广播顺序或生产负载。

<a id="managed-rpc"></a>

### 3.2 托管 RPC

适配器本身、它的 codec 契约与组合钩子见 [Boot §2](OGBS-Boot-Java-25-Specification-1.0.zh-CN.md#2-rpc-adapter)。本节只是围绕它的 Spring 接线，别无其他：不使用 Spring 的应用直接构造 `GameRpc`，得到完全相同的行为。

`cn.managame.spring.rpc.EnableGameRpc` 显式导入 RPC 装配。它要求一个 GameRuntime Bean（通常来自 EnableGameRuntime）和一个线程安全的 `cn.managame.boot.rpc.GameRpcCodec` Bean，并读取以下属性：

| 配置项 | 默认值 | 边界 |
| --- | --- | --- |
| game.rpc.node-id | 必填 | int 中的非零 uint32 位模式 |
| game.rpc.port | 必填 | 0..65535 |
| game.rpc.bind-address | 127.0.0.1 | 非空白 |
| game.rpc.call-timeout-millis | Node 默认 5000 | 设置时覆盖 Node 默认值 |

属性非法时启动失败。需显式添加 game-rpc：game-spring 将其声明为可选依赖，因此仅使用 HTTP/Data 的应用不会继承它。不引入 Boot、服务发现、Peer 列表推断或热重载。

| Bean | 角色 |
| --- | --- |
| `gameRpc` | `GameRpc` 适配器，destroyMethod="close"；由上表属性与有序 `GameRpcConfigurer` Bean 构造 |
| `gameRpcNode` | `rpc.node()`，destroyMethod="" 使适配器保持所有者；暴露拓扑管理与 localAddress |
| `gameRpcLifecycle` | Integer.MAX_VALUE 阶段的 SmartLifecycle；单例初始化后启动 Node，stop 时调用 `rpc.close()` |

`GameRpcConfigurer` Bean 用 `ObjectProvider.orderedStream()` 收集，因此 Spring 顺序就是列表顺序，全部钩子由适配器调用——最后一个 configurer 位于最外层。顺序是本配置对它们唯一做出的决定。Context.close 先按 S-CLOSE-01 排空 Runtime，然后在生命周期 stop 与幂等销毁中关闭 RPC。Context.stop 本身不是优雅的 Runtime 停机；停止的 Node 不能重启。不要自行 start 或 close `gameRpcNode`，也不要通过它发送裸的 call/notify/reply。Handler 构造必须延迟获取 GameRpc（用 `ObjectProvider` 或之后的 setter），以避免 Runtime/Handler/GameRpc 构造环。

```java
@Configuration
@EnableGameRuntime(basePackages = "my.game")
@EnableGameRpc
class RpcConfig {
    // 提供 GameRpcCodec、ProtocolRegistry、RouteExecutor 与 GameRuntimeConfigurer。
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

在托管 Node 上组合服务角色 Router（被路由请求如何进入 Runtime 由应用的 RouterHandler 决定）：

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

Context.refresh 之后，按应用拓扑策略调用 `context.getBean(RpcNode.class).addPeer(2, address, 1)`；使用路由时，以同样方式把 Node 连接到每个已声明的 Router。[RpcCompositionTest](../../game-spring/src/test/java/cn/managame/spring/rpc/RpcCompositionTest.java) 验证 Spring 顺序成为钩子顺序，以及装饰链确实到达 Runtime handler；钩子契约本身（含 attach 失败后的清理）在 [BootRpcCompositionTest](../../game-boot/src/test/java/cn/managame/boot/rpc/BootRpcCompositionTest.java) 中以无容器方式验证。[RpcAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/rpc/RpcAssemblyTest.java) 通过装配好的 Bean 验证真实 TCP Call/Notify、身份/来源、对象回复、未知命令错误、立即失败与精确 Route 恢复，以及远端错误/畸形响应的路由、同步拒绝的预留清理、超时续接的排空、托管端口释放与一次性生命周期。部署安全、服务发现与生产容量仍不在此验证范围内。

## 4. 使用与验证

demo 直接依赖 game-spring 与 game-rpc。game-spring 传递提供 Boot/Runtime/Data/Network/Core 与 Spring Context API；显式的 game-rpc 支撑整合后的独立 RPC 示例。Fory、JDBC 驱动与连接池仍是应用依赖。其他仅使用 HTTP/Data 的应用仍不会继承 game-spring 的可选 RPC 依赖。`mvn -pl game-demo -am clean verify` 构建完整依赖反应堆，并在无需预装框架构件的情况下运行应用/独立执行测试。

配置片段：

```java
@Configuration
@EnableGameRuntime(basePackages = "my.game")
@EnableGameData(basePackages = "my.game")
class GameConfig {
    // 提供 DataSource、ProtocolRegistry、RouteExecutor 与 GameRuntimeConfigurer Bean。
}
```

完整接入见 [GameRuntimeConfig](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java) 及 [GameDataConfig](../../game-demo/src/main/java/cn/managame/demo/common/data/GameDataConfig.java)。[CronBeansTest](../../game-spring/src/test/java/cn/managame/spring/runtime/CronBeansTest.java) 覆盖仅方法注解、继承/default 接口及工厂发现、去重、启动拒绝和真实 Route 执行。[RuntimeLifecycleTest](../../game-spring/src/test/java/cn/managame/spring/runtime/RuntimeLifecycleTest.java) 证明已接纳工作先于普通关闭监听器和资源销毁完成，新 Context 可重新绑定 Events。demo Repository 测试用 JDBC 桩验证初始化注入及三种基类，真实数据库和自定义广播/代理配置仍未验证。根 `mvn clean verify` 检查模块边界，其中包含 game-boot、RPC 及本可选适配器。

<a id="5-observability"></a>
<a id="5-可观测性"></a>

## 5. 可观测性

实现 [Spring §5](OGBS-Spring-1.0.zh-CN.md#5-可观测性组合)。包 `cn.managame.spring.metrics`，通过 `@EnableGameMetrics` 选择性启用。所发布的类型——`ReadinessCheck`、`ObservabilityEndpoints`、`FrameworkMetrics`——及它们做的一切都属于 [Boot §4](OGBS-Boot-Java-25-Specification-1.0.zh-CN.md#4-可观测性)；贡献条件的应用实现 `cn.managame.boot.metrics.ReadinessCheck`。

| Bean | 角色 |
| --- | --- |
| `gameMetricsRegistry` | `MetricsRegistry`，为每个存在的组件在固定前缀 `runtime`、`data`、`rpc`、`network` 下发布一个 `FrameworkMetrics` 源，各自经 `ObjectProvider` 解析，缺失的组件什么也不发布 |
| `gameObservabilityEndpoints` | `ObservabilityEndpoints`，持有注册表、就绪条件与两个路径；该 Bean 存在时 §3.1 应用 `wrap` |

就绪条件按此顺序装配：来自 `ShutdownPhase` Bean 的 `shutdown.rotation`，然后是存在组件的 `runtime.accepting` 与 `data.accepting`，最后是应用自己按 Spring 顺序的 `ReadinessCheck` Bean。

| 配置项 | 默认值 | 边界 |
| --- | --- | --- |
| `game.metrics.path` | `/metrics` | 绝对路径、至少两个字符、不含 `?` 与 `#`、与另一个不同；否则该 Bean 在 refresh 时抛 IllegalArgumentException |
| `game.metrics.readiness-path` | `/readiness` | 同样规则 |

两者都在应用 `game.http.context-path` 之前匹配，因此无论业务前缀是什么都能在服务器根路径上够到，前缀既不暴露也不遮蔽它们。提供它们需要启用 Runtime HTTP 监听；注册表 Bean 无论如何都可用。应用自己创建而未暴露为 Bean 的 `NetworkServer` 由它自己用 `registry.publish(prefix, source)` 发布。

验证：[MetricsAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/metrics/MetricsAssemblyTest.java) 覆盖只装配 Runtime 时发布哪些前缀、经真实监听器在 context path 之外提供两个路径、应用的 `ReadinessCheck` 使实例变为不就绪并被指名、路径可配置，以及排空中的 Runtime 返回 503 而 `/metrics` 仍作答。端点行为本身（含路径校验）在 [ObservabilityEndpointsTest](../../game-boot/src/test/java/cn/managame/boot/metrics/ObservabilityEndpointsTest.java) 中以无容器方式验证。
