# game-runtime

[English](README.md) | **[简体中文](README.zh-CN.md)**

## 规范文档

| 标准规范（语言无关） | Java 开发规范 |
| --- | --- |
| [OGBS Runtime Specification](../docs/ogbs/OGBS-Runtime-1.0.zh-CN.md) | [OGBS Runtime Java Development Specification](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md) |

组件行为与 Java 实现分别维护在上述两份规范中，本文提供使用入口。

Java 25 业务运行时。Maven 坐标为 `cn.managame:game-runtime`，按包划分内部功能模块。

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

通过 `httpHandlers(...)` 注册实例。HttpMethod.method 使用 HttpRequestMethod 枚举，默认 POST；GET 需显式 `method=HttpRequestMethod.GET`。在 @HttpHandler/@HttpMethod 设置 `routeKey="playerId"`，GET 从 query 取字段，其他方法从 JSON body 顶层字段提取；方法配置覆盖类规则。也可设置 routeKeyMethod 指向 Handler 提取方法。可选的四参数 `httpContextFactory(domain, key, request, callback)` 保留选定 Key，可增加应用自定义 HTTP Context 字段；无规则时必须提供工厂选 Key。通过 `HttpServer.builder().asyncHandler(runtime.http()::dispatch)` 接入。public 方法返回业务 DTO/对象或 void，延迟完成使用 `context.responseCallback().onResponse(dto)`。Runtime 默认编码 JSON，在内部创建传输响应，业务结果不携带 HTTP 版本；通过 `httpResultCodec(...)` 自定义结果编码。按原始方法/path 精确匹配，不自动绑定请求 DTO 或推导玩家 ID。请求只借用到方法返回；延迟完成响应不会延长请求生命周期。

运行 [RuntimeHttpExample](../game-example/src/main/java/cn/managame/example/runtime/RuntimeHttpExample.java) 查看注解 echo 和跨 Route 延迟响应。详见 [HTTP 语义](../docs/ogbs/OGBS-Runtime-1.0.zh-CN.md#runtime-http-profile) 与 [Java API、失败及所有权](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#runtime-http-api)。
