# OGBS Spring Java 25 开发规范 1.0

**[English](OGBS-Spring-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md)

对应 [容器集成语义](OGBS-Spring-1.0.zh-CN.md)。已实现可选 artifact `cn.managame:game-spring:1.0.0-SNAPSHOT`，使用 Java 25，依赖 game-runtime、game-data 及 spring-context 7.0.9，可选依赖 game-rpc，不依赖 Spring Boot，核心组件不反向依赖 Spring。

## 1. Runtime 注册

`cn.managame.spring.runtime.EnableGameRuntime` 导入内部配置，扫描 Handler/EventHandler/HttpHandler 标记及声明/继承 Cron 方法的类。`String[] basePackages() default {}` 对应 ComponentScan.basePackages，空值使用被注解配置类的包，采用标准 Spring profile/名称规则，不需额外 Component。声明类型可识别的既有 Bean 产物也参与；不为推断任务类型初始化未知 FactoryBean 产物。发现结果构建时冻结，按目标对象身份去重；prototype 对象和可识别 Spring AOP 代理以 IllegalArgumentException 拒绝。Runtime 校验注解签名，包括非法 private 方法。Runtime 注解不包含 Spring 注解。

函数式 Bean 扩展 `GameRuntimeConfigurer.configure(GameRuntimeBuilder)` 在收集 Handler/任务及全部 ProtocolProvider Bean 后按 Spring 顺序执行。Builder 集合方法替换列表，显式 configurer 可覆盖扫描结果。应用提供 Domain/RouteExecutor 绑定及接入身份策略。执行器宜为 destroyMethod="close" 的 Bean，确保启动失败后释放调用方资源；成功构建转移生命周期至 Runtime，内置 close 幂等。配置创建名为 gameRuntime 的单例，绑定 Events 供外部静态发布；绑定冲突时关闭新实例。Handler 构造器不能依赖正在构造的 Runtime，延迟 provider 只能启动完成后使用。

## 2. Data 注册

`cn.managame.spring.data.EnableGameData` 在 basePackages 扫描 Repository，采用相同的默认包规则并导入内部配置。应用需提供持有的 DataSource Bean。默认使用 GameDataBuilder.mysql(dataSource)，发现已知单例 Bean 中直接继承 SingleRepository、GroupRepository 或 LogRepository 的具体类型并注册。静态 BeanFactoryPostProcessor 把实例供应器替换成 `gameData.repository(type)` 并添加 gameData 依赖，保留名称/限定符及字段/setter 注入。Data 仅创建一次无参实例，初始化后才进入 Spring 注入回调；不支持构造器注入。未继承这三个基类的其他 Repository 是普通 Spring Bean。prototype Data Repository 在数据库访问前拒绝。

`GameDataConfigurer.configure(GameDataBuilder)` Bean 在数据源/Repository 配置后、build 前按 Spring 顺序执行，可配置 JSON codec、回写/错误策略或显式 mapper 覆盖。configurer 依赖不能要求正在构建的 Data/Repository。gameData 单例采用 destroyMethod="close"，借用 DataSource，不关闭应用连接池。

## 3. 停机

内部 ContextClosedEvent 监听器使用 Ordered.HIGHEST_PRECEDENCE、supportsAsyncExecution=false。在 Spring 普通同步事件广播下只处理自身 Context，执行 Runtime.shutdown，以每次 30 秒间隔重复等待、无总超时，然后 Runtime.close。中断不放弃排空，结束后恢复中断状态。普通关闭监听器（例如 demo TCP 关闭）随后执行，再停止托管 HTTP 生命周期、销毁 Bean 和 Data 最终回写。不得安装更早/同优先级的资源关闭监听器、以自定义异步广播改变顺序，或在 Runtime Route 关闭 Context，否则违反 S-CLOSE-01。refresh 失败按 Spring 销毁清理。本模块持有托管 HTTP 监听器，不持有数据库池或额外业务线程。监听器可能在 refresh 最后生命周期阶段对外可达；其他生命周期若随后失败，Spring 关闭监听器及 Runtime，但此失败清理不保证优雅排空或响应投递。

### 3.1 托管 HTTP

`EnableGameRuntime` 同时导入内部 HTTP 装配。单例 `gameHttpServer` 是普通 Network HttpServer Bean，采用 destroyMethod="close"。内部 SmartLifecycle 在单例初始化和 Runtime 构建完成后，以 Integer.MAX_VALUE 阶段启动监听，在生命周期 stop 时关闭。Context.close 时 Runtime 同步排空监听器先于此 stop 执行。不要手动调用 Bean 的 start/close。HTTP 与底层 Network 服务一样只启动一次，停止的监听器不能通过 Context.start 重新打开。Context.stop 本身不是 Runtime 优雅停机操作，请使用 Context.close。即使禁用监听，无效配置仍导致启动失败。

默认启用检查依据发现的 HttpHandler Bean，不根据无关类的注解名称推断。configurer 若提供 Bean 发现范围外的 HTTP 对象，需显式设置 enabled=true。enabled=false 时仍构造 Bean，但不绑定端口、不创建默认网络线程组。生命周期按 gameHttpServer 限定符选择监听器，无关的 Primary HttpServer Bean 不替代托管监听器。HTTP/1.1、origin-form 请求目标、JSON/对象结果及既有 Runtime 接纳/Route/Context 契约保持不变。所有业务方法使用配置的 Runtime RouteExecutor，不新增 Spring 业务执行器。

配置从 Spring Environment 读取，应用加载自己的属性源即可，无需 Spring Boot：

| 配置项 | 默认值 | 边界 |
| --- | --- | --- |
| game.http.enabled | 发现 HttpHandler Bean 时为 true，否则 false | 显式 true/false 覆盖发现结果 |
| game.http.port | 8080 | 0..65535；0 使用随机端口，通过 HttpServer.localAddress() 获取 |
| game.http.bind-address | 127.0.0.1 | 非空白主机/地址；无法解析或占用导致绑定失败 |
| game.http.context-path | 空（根路径） | `/` 也表示根路径；`/game/` 规范为 `/game`；原始绝对 ASCII 路径段使用字母/数字/`.`/`_`/`~`/`-`；不允许空段、`.`/`..` 段、百分号转义、query 或 fragment |
| game.http.max-content-length | 1048576 | 正字节数；body 超限采用 Network 的 413 处理 |
| game.http.max-initial-line-length | 4096 | 正字节数 |
| game.http.max-header-size | 8192 | 正字节数 |
| game.http.read-timeout-millis | 30000 | 非负，0 禁用入站无数据超时，不是业务截止时间 |
| game.http.backlog | Network/Netty 默认值 | 设置时为正数 SO_BACKLOG |
| game.http.keep-alive | Network/Netty 默认值 | 设置时为 Boolean SO_KEEPALIVE（TCP socket 设置，不是 HTTP Keep-Alive 策略） |
| game.http.tcp-no-delay | Network/Netty 默认值 | 设置时为 Boolean TCP_NODELAY |

`GameHttpConfigurer.configure(HttpServerBuilder)` Bean 在属性绑定之后、build 之前按 Spring 顺序执行，可覆盖网络参数、添加原生 TLS/CORS/鉴权 pipeline Handler 或传入调用方线程组。适配器最后安装 Runtime dispatch，因此 configurer 的 handler/asyncHandler 不替换它；扩展 Handler 仍可按 Network 原生 pipeline 契约显式消费请求。configurer 不能同步依赖正在构建的 HTTP Bean。借用线程组仍由调用方持有，必须存活到监听关闭之后。没有覆盖时由 Network 持有 NIO boss/worker 线程组。TLS 证书及任意原生选项使用此扩展，不自动推断 HTTP 安全策略。

内部前缀适配按完整原始路径段匹配，不解码或重定向。`/game` 和 `/game/` 映射到端点 `/`；`/game/echo?routeKey=7` 映射到 `/echo?routeKey=7`。无前缀 `/echo`、`/games/echo` 和 `/game%2Fecho` 返回 404，无效 origin-form/fragment 目标返回 400。非根前缀使用独立 retain 的请求 duplicate 分发相对 URI，在 dispatch 返回后（包括失败）释放自身引用，不修改 Network 原请求。Runtime 独立 retain 已接纳请求到方法返回，延迟访问仍需自己的所有权。HTTP Context 工厂/codec 观察相对 URI，dispatch 前的原生 pipeline 扩展观察原始 URI。默认根路径直接分发原请求。不支持热重载属性。

普通 `@HttpHandler(domain=3, routeKey="routeKey")` 和 `@HttpMethod("/echo")` 示例：

```properties
game.http.port=8080
game.http.context-path=/game
```

POST `/game/echo`，body 为 `{"routeKey":7,"text":"hello"}`，选择 Domain 3 / Key 7。端口/路径不提供 Domain、路由 Key 或鉴权身份。用户无需 HttpServer builder、asyncHandler 适配或关闭监听器。[HttpAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/runtime/HttpAssemblyTest.java) 验证自动扫描 Bean 启动、DTO/String/GET 分发、前缀边界、大小拒绝、禁用/无对象默认值、绑定失败清理、retain 引用所有权及停机接纳/排空/端口释放。[DemoServicesTest](../../game-demo/src/test/java/cn/managame/demo/DemoServicesTest.java) 通过相同自动监听器验证真实 Timer/Cron 业务执行。这些测试不验证 TLS/原生安全扩展、自定义广播顺序或生产负载。



<a id="managed-rpc"></a>

### 3.2 托管 RPC

`cn.managame.spring.rpc.EnableGameRpc` 显式导入 RPC 装配。需要 GameRuntime Bean（通常由 EnableGameRuntime 提供）、线程安全的 GameRpcCodec Bean，以及 game.rpc.node-id（非零 uint32 原始位，以 int 保存）和 game.rpc.port（0..65535）配置。game.rpc.bind-address 默认 127.0.0.1；game.rpc.call-timeout-millis 可覆盖 Node 默认 5000ms。非法配置导致启动失败。应用须显式添加 game-rpc：game-spring 将其声明为 optional，只有 HTTP/Data 的应用不会传递引入。没有 Boot、发现、自动推断 Peer 列表或热更新。

GameRpcCodec 提供 `byte[] encode(Object)` 和 `<T> T decode(byte[], Class<T>)`，须支持传输/Route 并发调用，返回非 null 且符合精确注册类型的对象，返回数组/对象独立于传输缓冲区生命周期。业务序列化由应用决定，例如 Fory，没有框架业务序列化格式。适配器复制借用入站字节，并在 Runtime 接纳前同步解码；大型/慢解码仍会延迟传输 EventLoop。解码对象不能保存借用 ByteBuf。出站数组返回后交给适配器，消费前 codec 不得修改/复用。

GameRpcConfigurer Bean 按 Spring 顺序在属性配置后调整 RpcNodeBuilder，最后由适配器安装 RpcHandler。`gameRpc` 持有 RpcNode 生命周期，`gameRpcNode` 暴露拓扑管理与 localAddress；内部 SmartLifecycle 在 singleton 初始化后以 Integer.MAX_VALUE phase 启动。Context.close 先按 S-CLOSE-01 排空 Runtime，再于生命周期停止阶段关闭 RPC，销毁幂等关闭。Context.stop 本身不是 Runtime 优雅停服，停止的 Node 不能重启。不要手动启停托管 Node，也不要通过它发送原始 call/notify/reply：内部回调关联由 GameRpc 管理，原始 API 使用独立 RpcNode。Handler 构造必须延迟获取 GameRpc（ObjectProvider 或后续 setter 使用），避免 Runtime/Handler/GameRpc 构造循环。

入站 Call 和 Notify 按 ProtocolType.REQUEST 查 command；Notify 的 requestId 为零，不使用独立 NOTIFY 协议注册。未知 command、codec 异常及 Runtime 同步接纳/签名/Key 失败传播到 RpcHandler：Call 尝试 HANDLER_ERROR，Notify 仅诊断。有效对象将来源 Node/Slot、command/requestId、调用方 Key/业务身份及收到的原 Metadata 传给 runtime.dispatchRpc。注解 Domain 和普通 HandlerMethod 签名保持一致，不暴露或伪造物理 Connection。

`GameRpc.call(targetNodeId, routeKey, businessIdType, businessId, metadata, request, RouteCallback<T>)` 要求当前处于该 Runtime 的 Route，且存在 REQUEST 到响应的注册绑定；调用方 T 须符合绑定。序列化请求后登记 runtime.callback，成功/失败均回到精确来源 Context/Route，包含立即无 Peer 拒绝。远端错误码及本地 RPC 失败进入 RouteCallback.onFail；成功响应无法解码则为 PROTOCOL_ERROR。登记回调后发生同步失败，以 UNAVAILABLE 完成预留再重新抛出；登记前的序列化/绑定失败仅抛异常，同步拒绝不遗留回调预留。续接由 Runtime 执行，不创建 Spring 业务执行器。此对象便利回调不暴露响应 Metadata；需要传输级响应 envelope 时使用独立原始 RpcNode。

`GameRpc.notify(...)` 接受相同目标/Key/身份/Metadata/请求，返回 RpcSendStatus，无须当前 Route，也不能回复。`reply(response)` 从 Contexts.current(RpcHandlerContext.class) 获取上下文；`reply(context, response)` 允许保存不可变 envelope 做延迟回复，不保留传输 body。回复类型必须符合原请求注册的响应类，requestId 必须非零。`replyError(context, positiveCode)` 发送空错误响应。成功/失败回复的 Metadata 均为空。保留来源 Slot 优先和替换连接 fallback；ACCEPTED 仅代表传输接纳，不代表远端完成。业务 Handler 返回值不自动回复 RPC。异步 Handler 已接纳后的异常仍由普通 RuntimeErrorHandler 处理；需要错误回复时由业务显式发送，否则调用方可能超时。Runtime 外延迟应用工作不自动计入 drain。

示例（ProtocolProvider 绑定 QueryReq 到 QueryRes）：

```java
@Configuration
@EnableGameRuntime(basePackages = "my.game")
@EnableGameRpc
class RpcConfig {
    // 提供 GameRpcCodec、ProtocolProvider、RouteExecutor 和 GameRuntimeConfigurer。
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

Context.refresh 后按应用拓扑策略调用 `context.getBean(RpcNode.class).addPeer(2, address, 1)`。[RpcAssemblyTest](../../game-spring/src/test/java/cn/managame/spring/rpc/RpcAssemblyTest.java) 验证真实 TCP Call/Notify、身份/来源、对象回复、未知协议错误、立即失败及精确 Route 恢复，以及远端错误/坏响应回调、同步拒绝预留回收、超时续接排空、托管端口释放和一次性生命周期。部署安全、发现与生产容量不在验证范围。

## 4. 使用与验证

demo 直接依赖 game-spring 和 game-rpc：game-spring 提供传递 Runtime/Data/Network/Core 与 Spring Context API，显式 game-rpc 用于归并后的独立 RPC 示例。Fory、JDBC 驱动、连接池仍为应用依赖。其他仅使用 HTTP/Data 的应用不因 game-spring 的 optional RPC 声明传递引入 game-rpc。`mvn -pl game-demo -am clean verify` 构建完整依赖 reactor 并执行应用和独立示例测试，无需预先安装框架 artifact。

配置片段：

```java
@Configuration
@EnableGameRuntime(basePackages = "my.game")
@EnableGameData(basePackages = "my.game")
class GameConfig {
    // 提供 DataSource、ProtocolProvider、RouteExecutor 和 GameRuntimeConfigurer Bean。
}
```

完整接入见 [GameRuntimeConfig](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java) 及 [GameDataConfig](../../game-demo/src/main/java/cn/managame/demo/common/data/GameDataConfig.java)。[CronBeansTest](../../game-spring/src/test/java/cn/managame/spring/runtime/CronBeansTest.java) 覆盖仅方法注解、继承/default 接口及工厂发现、去重、启动拒绝和真实 Route 执行。[RuntimeLifecycleTest](../../game-spring/src/test/java/cn/managame/spring/runtime/RuntimeLifecycleTest.java) 证明已接纳工作先于普通关闭监听器和资源销毁完成，新 Context 可重新绑定 Events。demo Repository 测试用 JDBC 桩验证初始化注入及三种基类，真实数据库和自定义广播/代理配置仍未验证。根 `mvn clean verify` 检查模块边界，RPC 及可选适配器现已纳入通过的根验证。
