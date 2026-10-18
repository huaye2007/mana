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

外部接入可配置 `builder.handlerContextFactory((domain, command, requestId, connection, metadata, message) -> ...)`，再调用 `runtime.dispatch(connection, command, requestId, metadata, message)`。Runtime 先从 @Handler/@HandlerMethod 解析 Domain；业务工厂从连接属性或外部 Map 获取已鉴权身份，按 Domain 选择 routeKey/businessIdType/businessId，不要求框架身份存储或固定角色规则。工厂在接纳前运行一次，必须保留 Domain/相关性/消息/连接/Metadata；已有显式参数绕过它。见 [策略契约与示例](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#handler-context-factory) 和 [两个 Domain 的 Map 测试](src/test/java/cn/managame/runtime/HandlerContextFactoryTest.java)。

业务调用使用 `runtime.dispatch(connection, routeKey, businessIdType, businessId, message)`：调用方传入 Key 和可信身份，Runtime 按消息精确类型查找 Handler 的 Domain，构造空 Metadata 的 DefaultClientHandlerContext，并进入既有 RouteExecutor，无需另外构造 Route/Context 或从消息提取 Key。匿名调用可省略身份（默认 0/0）；Metadata/自定义字段仍使用显式上下文入口，通过 `context.connection()` 获取借用连接。

默认 Handler 写法为 `handle(ClientHandlerContext context, MyRequest request)`，通过 context.businessId() 获取业务身份，需要时读取 context.businessIdType()。无需定义 RoleId/GuildId/RoomId 包装类型或注册参数转换。接入层负责鉴权和类别校验；Context 访问不会根据 Domain、Key 或协议字段推断身份。已注册应用参数仍可通过 HandlerArgumentBinding 作为可选扩展；解析器在提交线程、接纳之前各运行一次，必须线程安全，不访问 Route 所有的可变状态，解析失败在入队前拒绝。见 [完整参数契约](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#handler-arguments) 和 [测试](src/test/java/cn/managame/runtime/HandlerArgumentTest.java)。

少数按协议内容路由的场景，配置 `@HandlerMethod(routeKey="userId")` 或 `routeKeyMethod="getUserId"`，再调用 `runtime.dispatch(connection, businessIdType, businessId, message)`（只有未配置上下文工厂时，匿名提取入口才可省略身份）。类级 @Handler 提供默认规则，方法级非空规则覆盖；外部 Key 重载从不调用这些规则。成员访问只编译一次，缺少/非法规则和 Key 为 0 时拒绝。见 [Java 契约与边界](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#automatic-handler-dispatch)、[框架测试](src/test/java/cn/managame/runtime/HandlerDispatchTest.java) 和 [demo](../game-demo/README.zh-CN.md#handler-分发)。

Handler 应答客户端请求只有两种方式：返回该消息已注册的响应，由 Runtime 通过你配置的 `clientResultCodec(...)` 封装并写到该请求的连接上；或者返回 void，自己写 `context.connection()`，用 `context.command()` 与 `context.requestId()` 关联——例如在跨 Route 回调中写出，此时原上下文已恢复。没有第三种机制，Handler 签名里也不会出现框架完成对象：上下文只描述这个请求，仅此而已。每次分发 Runtime 至多发送一次，就在方法返回或抛出之后，因此没有完成竞争。编解码器封装出的对象在传输层接受之前归 Runtime 所有，未被接受时由它释放，因此封装成池化缓冲区是安全的。返回 null 表示不应答；以返回值应答的 Handler 抛异常时被报告并只以框架错误码应答，绝不把异常本身发出去。以返回值应答需要已注册的请求/响应配对、ClientHandlerContext 和编解码器，三者都在构建期校验。见 [响应契约](../docs/ogbs/OGBS-Runtime-1.0.zh-CN.md#client-response-contract)、[Java API](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#client-responses) 和[测试](src/test/java/cn/managame/runtime/ClientResponseTest.java)。

客户端消息使用 ClientHandlerContext（借用 Connection、入站 command 和请求 ID），RPC 消息使用 RpcHandlerContext（来源节点/Slot、command 和请求 ID）。两者继承 HandlerContext，都直接暴露 businessId；所需上下文子类型不匹配时在接纳前拒绝。HTTP 保持独立 HttpContext，不增加统一发送 API 或自动 RPC 到 Runtime 的适配器。见 [上下文契约](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#transport-handler-contexts) 和 [传输测试](src/test/java/cn/managame/runtime/TransportContextTest.java)。

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

GameTime 是进程级业务墙钟，有意被 JVM 内所有 Runtime 共享：业务墙钟属于游戏世界而非某个执行实例，把时钟挂到 Runtime 上会把 Runtime 引用推进每一个读取时间的模型。代价是 setClock 没有作用域——它一次改变所有线程和所有 Runtime 的时间，因此属于启动期或测试夹具，绝不属于某次请求；测试必须恢复它并用 @ResourceLock("GameTime") 保护。setClock/resetClock 不自动改变任何现存调度。RuntimeTimer 仍按真实经过的 delay 触发。绝对业务 deadline 由应用自行换算成 Duration，需要重算时由业务 cancel + schedule。业务即将结束的 Route 用 `runtime.timer().cancelAll(domain, key)` 一次性释放其定时器，而不必逐个跟踪 TimerRef；它返回仍待触发的数量，且不影响 Cron 调度。

Cron 基于当前 GameTime 计算下一次时间，再使用 RuntimeTimer。按声明类与方法名管理：

```java
runtime.cron().cancel(SystemCron.class, "dailyReset");
runtime.cron().reschedule(SystemCron.class, "dailyReset");
runtime.cron().rescheduleAll();
```

SystemCron 是应用定义的 Cron 类。cancel 停止后续周期；reschedule 可以恢复取消的项，rescheduleAll 包含全部注册项。已经开始的方法可以完成，旧代任务不会覆盖新调度。Cron 每轮方法结束后计算下一轮，异常或接纳失败不重试当轮，但继续未来周期。

## 事件监听器的 Domain

一次发布的全部监听器都在事件那一个 Route 上运行，因此监听器只能触及该 Route 所有的状态。`@EventMethod(domain = GUILD_ID)` 声明监听器在哪个 Domain 拥有状态，把这条规则从约定变成校验：同一事件类型的监听器声明了不同 Domain 时构建失败；事件的 `routeDomain()` 与所声明的不一致时，在准入前以 ROUTE_DOMAIN_MISMATCH 拒绝——不创建上下文、不提交 Route 任务、没有监听器运行。不声明维持原有行为，但只要有一个监听器声明，就约束了该事件类型的全部监听器。要触及其他 Domain 的状态，应留在事件的 Route 上并使用 `runtime.call`，其回调会恢复事件上下文。见[条款](../docs/ogbs/OGBS-Runtime-1.0.zh-CN.md#event-listener-domain)与[测试](src/test/java/cn/managame/runtime/event/EventDomainTest.java)。

## 观察 Handler

`builder.handlerObserver(...)` 注册唯一一个观察者，它在该请求的 Route 上于 Handler 方法之前被通知，并在之后恰好一次，或完成或失败，并带上该方法自身的单调时长。按 Handler 的时长与次数就来自这里：`RuntimeStats` 没有按 Handler 的维度，`RuntimeErrorHandler` 只看得到失败。它不能干预——不能跳过方法、不能改变结果、不能把自身异常变成方法的异常，抛出异常的回调被报告后忽略。它运行在业务运行的地方，因此必须线程安全且不得阻塞。只有 `@HandlerMethod` 分发被观察；事件、定时器、Cron、跨 Route 计算与 HTTP 方法都不在内。见[条款](../docs/ogbs/OGBS-Runtime-1.0.zh-CN.md#handler-observation)、[Java API](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#handler-observation) 与[测试](src/test/java/cn/managame/runtime/HandlerObserverTest.java)。

## 静态事件发布

业务可通过 `cn.managame.runtime.event.Events` 直接调用 `Events.publish(event)`。Runtime 执行上下文内使用当前所属 Runtime；外部线程需要启动阶段先调用 `Events.bind(runtime)`。容器不替你做这件事：`@EnableGameRuntime` 构建 Runtime，但把绑定留给应用，因为 bean 工厂每个上下文运行一次，而绑定与进程等寿命。[GameDemo](../game-demo/src/main/java/cn/managame/demo/GameDemo.java) 在上下文启动后立即绑定。无所属/默认实例时抛 IllegalStateException。默认绑定冲突时拒绝；重复绑定同一实例幂等。没有 unbind：进程在启动时绑定一次，该绑定与进程等寿命，因此 `Runtime.close` 不会撤销它，关闭后再发布得到 RUNTIME_CLOSED，而不是报告缺少配置。这同时保证了已在途的发布不会被重定向到替代实例。既有 `runtime.eventBus().publish(event)` 保留。事件继续使用配置的 RouteExecutor，顺序、上下文继承、接纳错误及关闭行为不变。详见 [选择及生命周期边界](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#static-event-publication)、[框架测试](src/test/java/cn/managame/runtime/event/EventsTest.java) 和 [demo](../game-demo/README.zh-CN.md#发布-runtime-事件)。

## 虚拟线程 Route 队列

每个有工作的 Route 拥有一个无锁 Mailbox（并发队列加排队与运行中任务计数）和一个虚拟线程消费者；最后一个任务执行完即移除 Mailbox，空闲 Route 不占内存，也没有空闲缓存。按完整 Domain/Key 串行业务，不使用执行器全局监视器，也不在每个任务上锁 Map。可用 `new VirtualThreadRouteExecutor(capacity, routeCapacity)` 调整总上限与单 Route 上限。详见 [完整默认值与生命周期](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#route-mailbox-lifecycle) 和 [行为测试](src/test/java/cn/managame/runtime/executor/VirtualThreadRouteExecutorTest.java)。

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

运行 [RuntimeHttpExample](../game-demo/src/main/java/cn/managame/demo/examples/runtime/RuntimeHttpExample.java) 查看注解 echo 和跨 Route 延迟响应。详见 [HTTP 语义](../docs/ogbs/OGBS-Runtime-1.0.zh-CN.md#runtime-http-profile) 与 [Java API、失败及所有权](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#runtime-http-api)。

Runtime 还支持客户端接入显式 Metadata 及按注解 Domain 分发 RPC。外部异步回调通过 runtime.callback(...) 回到源 Route。平滑停机使用 shutdown()、awaitTermination(Duration)、close()，stats() 提供近似统计。HTTP 支持 DTO/String 输入，可通过 Contexts.current(HttpContext.class) 获取上下文。见 [请求绑定与生命周期](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#135-请求绑定) 及可选 [game-spring](../game-spring/README.zh-CN.md)。
