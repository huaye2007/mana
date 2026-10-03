# game-runtime

[English](README.md) | **[简体中文](README.zh-CN.md)**

## 规范文档

| 标准规范（语言无关） | Java 开发规范 |
| --- | --- |
| [OGBS Runtime Specification](../docs/ogbs/OGBS-Runtime-1.0.zh-CN.md) | [OGBS Runtime Java Development Specification](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md) |

组件行为与 Java 实现分别维护在上述两份规范中，本文提供使用入口。

Java 25 业务运行时。Maven 坐标为 `cn.managame:game-runtime`，按包划分内部功能模块。

<a id="handler-entry"></a>

## Handler 入口

外部接入可配置 `builder.handlerContextFactory((domain, connection, metadata, message) -> ...)`，再调用 `runtime.dispatch(connection, message)`。Runtime 先从 @Handler/@HandlerMethod 解析 Domain；业务工厂从连接属性或外部 Map 获取已鉴权身份，按 Domain 选择 routeKey/businessIdType/businessId，不要求框架身份存储或固定角色规则。工厂在接纳前运行一次，必须保留 Domain/消息/连接/Metadata；已有显式参数绕过它。见 [策略契约与示例](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#handler-context-factory) 和 [两个 Domain 的 Map 测试](src/test/java/cn/managame/runtime/HandlerContextFactoryTest.java)。

业务调用使用 `runtime.dispatch(connection, routeKey, businessIdType, businessId, message)`：调用方传入 Key 和可信身份，Runtime 按消息精确类型查找 Handler 的 Domain，构造空 Metadata 的 DefaultClientHandlerContext，并进入既有 RouteExecutor，无需另外构造 Route/Context 或从消息提取 Key。匿名调用可省略身份（默认 0/0）；Metadata/自定义字段仍使用显式上下文入口，通过 `context.connection()` 获取借用连接。

默认 Handler 写法为 `handle(ClientHandlerContext context, MyRequest request)`，通过 context.businessId() 获取业务身份，需要时读取 context.businessIdType()。无需定义 RoleId/GuildId/RoomId 包装类型或注册参数转换。接入层负责鉴权和类别校验；Context 访问不会根据 Domain、Key 或协议字段推断身份。已注册应用参数仍可通过 HandlerArgumentBinding 作为可选扩展；解析器在提交线程、接纳之前各运行一次，必须线程安全，不访问 Route 所有的可变状态，解析失败在入队前拒绝。见 [完整参数契约](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#handler-arguments) 和 [测试](src/test/java/cn/managame/runtime/HandlerArgumentTest.java)。

少数按协议内容路由的场景，配置 `@HandlerMethod(routeKey="userId")` 或 `routeKeyMethod="getUserId"`，再调用 `runtime.dispatch(connection, businessIdType, businessId, message)`（只有未配置上下文工厂时，匿名提取入口才可省略身份）。类级 @Handler 提供默认规则，方法级非空规则覆盖；外部 Key 重载从不调用这些规则。成员访问只编译一次，缺少/非法规则和 Key 为 0 时拒绝。见 [Java 契约与边界](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#automatic-handler-dispatch)、[框架测试](src/test/java/cn/managame/runtime/HandlerDispatchTest.java) 和 [demo](../game-demo/README.zh-CN.md#handler-分发)。

客户端消息使用 ClientHandlerContext（借用 Connection），RPC 消息使用 RpcHandlerContext（来源节点/Slot、command 和请求 ID）。两者继承 HandlerContext，都直接暴露 businessId；所需上下文子类型不匹配时在接纳前拒绝。HTTP 保持独立 HttpContext，不增加统一发送 API 或自动 RPC 到 Runtime 的适配器。见 [上下文契约](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#transport-handler-contexts) 和 [传输测试](src/test/java/cn/managame/runtime/TransportContextTest.java)。

## 目录与职责

```text
cn.managame.runtime
├── GameRuntime / GameRuntimeBuilder  公开入口与配置
├── context                          Context 体系、默认实现、只读 Contexts
├── route                            Domain、Key 提取与注册、RouteCallback
├── executor                         RouteExecutor SPI、绑定、平台/虚拟线程实现
├── protocol                         协议描述、注册及 Req → Res 关联
├── handler                          Handler 注解
├── http                             HTTP 注解、HttpContext、Route 分发器
├── event                            本地 Event、EventBus 及注解
├── timer                            一次性 Timer、Cron 注解及管理接口
├── time                             可替换的业务墙钟 GameTime
├── error                            Runtime 错误、处理器与分发异常
└── internal                         注册编译、Context 绑定、分发和时间调度实现
```

API 子包承载对应职责的类型；internal 负责组装，不属于应用支持接口。GameRuntimeBuilder 收集配置后交给 RuntimeCompiler 校验和编译，不再混放整个运行期实现。Context 绑定与 Timer/Cron 管理也独立于业务分发。

本次是包结构调整，未增加 Maven 发布单元。原来的 `cn.managame.runtime.HandlerContext` 等导入需改为 `cn.managame.runtime.context.HandlerContext`；`import cn.managame.runtime.*` 不会导入子包。仓库示例和测试已同步迁移。

## GameTime 与调度

```java
import cn.managame.runtime.time.GameTime;
import java.time.*;

GameTime.setClock(Clock.offset(Clock.systemUTC(), Duration.ofDays(1)));
long now = GameTime.currentTimeMillis();
LocalDateTime local = GameTime.now(ZoneId.of("Asia/Shanghai"));
runtime.cron().rescheduleAll();
GameTime.resetClock();
```

GameTime 是进程级业务墙钟；setClock/resetClock 不自动改变任何现存调度。测试应在 finally 或 AfterEach 恢复时钟。RuntimeTimer 仍按真实经过的 delay 触发。绝对业务 deadline 由应用自行换算成 Duration，需要重算时由业务 cancel + schedule。

Cron 基于当前 GameTime 计算下一次时间，再使用 RuntimeTimer。按声明类与方法名管理：

```java
runtime.cron().cancel(SystemCron.class, "dailyReset");
runtime.cron().reschedule(SystemCron.class, "dailyReset");
runtime.cron().rescheduleAll();
```

SystemCron 是应用定义的 Cron 类。cancel 停止后续周期；reschedule 可以恢复取消的项，rescheduleAll 包含全部注册项。已经开始的方法可以完成，旧代任务不会覆盖新调度。Cron 每轮方法结束后计算下一轮，异常或接纳失败不重试当轮，但继续未来周期。

## 静态事件发布

业务可通过 `cn.managame.runtime.event.Events` 直接调用 `Events.publish(event)`。Runtime 执行上下文内使用当前所属 Runtime；外部线程需要启动阶段先调用 `Events.bind(runtime)`，demo 的 Spring 配置自动完成此步骤。无所属/默认实例时抛 IllegalStateException。默认绑定冲突时拒绝；重复绑定同一实例幂等。`Events.unbind(runtime)` 仅移除指定实例的默认绑定，不关闭它；内置 Runtime.close 会解除自身绑定。启动绑定需与关闭协调，且只绑定仍运行的实例。既有 `runtime.eventBus().publish(event)` 保留。事件继续使用配置的 RouteExecutor，顺序、上下文继承、接纳错误及关闭行为不变。详见 [选择及生命周期边界](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#static-event-publication)、[框架测试](src/test/java/cn/managame/runtime/event/EventsTest.java) 和 [demo](../game-demo/README.zh-CN.md#发布-runtime-事件)。

## 虚拟线程 Route 队列

ConcurrentHashMap 持有活跃队列，按完整 Domain/Key 串行业务，不使用执行器全局 synchronized 监视器。只有完全执行结束的空队列进入 Caffeine 空闲缓存，默认复用 60 秒，保留条数上限等于任务容量。可用 `new VirtualThreadRouteExecutor(65_536, Duration.ofMinutes(2))` 配置其他期限。容量压力可提前淘汰空闲队列，不淘汰活跃工作。过期采用 Caffeine 默认被动维护，由后续写入及部分读取触发。过期队列不可复用；没有后续缓存访问时，物理移除可延迟到后续维护或关闭。不配置过期调度器。Caffeine 由 game-core 提供。详见 [完整默认值与生命周期](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#route-mailbox-lifecycle) 和 [行为测试](src/test/java/cn/managame/runtime/executor/VirtualThreadRouteExecutorTest.java)。

## 文档与验证

- [OGBS Runtime 规范](../docs/ogbs/OGBS-Runtime-1.0.zh-CN.md)
- [Java 25 开发规范](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md)
- [GameTime](src/main/java/cn/managame/runtime/time/GameTime.java)
- [CronScheduler](src/main/java/cn/managame/runtime/timer/CronScheduler.java)
- RPC / Runtime 完整集成示例尚未实现，当前接入示例见 Java 开发规范。

根目录运行 `mvn -pl game-runtime -am test`；拆包后的完整接入验证运行 `mvn clean verify`。

<a id="runtime-http"></a>

## HTTP 业务入口

`cn.managame.runtime.http` 在 game-runtime artifact 中提供 `@HttpHandler`、`@HttpMethod`、`HttpRequestMethod`、`HttpContext`、`DefaultHttpContext`、`HttpContextFactory`、`HttpResultCallback`、`HttpResultCodec`、`HttpDispatcher`。HttpContext 继承基础 Context，包含 Route、请求及结果回调，不含业务身份/Metadata 字段。依赖 game-network，与 Handler/Event/call 共用 Domain/RouteKey 执行器。

通过 `httpHandlers(...)` 注册实例。HttpMethod.method 使用 HttpRequestMethod 枚举，默认 POST；GET 需显式 `method=HttpRequestMethod.GET`。在 @HttpHandler/@HttpMethod 设置 `routeKey="playerId"`，GET 从 query 取字段，其他方法从 JSON body 顶层字段提取；方法配置覆盖类规则。也可设置 routeKeyMethod 指向 Handler 提取方法。可选的四参数 `httpContextFactory(domain, key, request, callback)` 保留选定 Key，可增加应用自定义 HTTP Context 字段；无规则时必须提供工厂选 Key。通过 `HttpServer.builder().asyncHandler(runtime.http()::dispatch)` 接入。public 方法返回业务 DTO/对象或 void，延迟完成使用 `context.responseCallback().onResponse(dto)`。Runtime 默认编码 JSON，在内部创建传输响应，业务结果不携带 HTTP 版本；通过 `httpResultCodec(...)` 自定义结果编码。按原始方法/path 精确匹配，支持请求 DTO 绑定，不推导玩家 ID。请求只借用到方法返回；延迟完成响应不会延长请求生命周期。

普通 Spring 的 [game-demo 服务接入](../game-demo/README.zh-CN.md#demo-runtime-services) 组合 HTTP 对象返回、一次性 TimerRef Bean 与注解 cron，并验证真实执行和关闭。

运行 [RuntimeHttpExample](../game-example/src/main/java/cn/managame/example/runtime/RuntimeHttpExample.java) 查看注解 echo 和跨 Route 延迟响应。详见 [HTTP 语义](../docs/ogbs/OGBS-Runtime-1.0.zh-CN.md#runtime-http-profile) 与 [Java API、失败及所有权](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#runtime-http-api)。

Runtime 还支持客户端接入显式 Metadata 及按注解 Domain 分发 RPC。外部异步回调通过 runtime.callback(...) 回到源 Route。平滑停机使用 shutdown()、awaitTermination(Duration)、close()，stats() 提供近似统计。HTTP 支持 DTO/String 输入，可通过 Contexts.current(HttpContext.class) 获取上下文。见 [请求绑定与生命周期](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#135-请求绑定) 及可选 [game-spring](../game-spring/README.zh-CN.md)。
