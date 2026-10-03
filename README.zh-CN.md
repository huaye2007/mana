# mana3 — OGBS Java 25

[English](README.md) | **[简体中文](README.zh-CN.md)**

**OGBS = Open Game Backend Specification**（开放游戏后端规范）。

mana3 是 OGBS 的 Java 参考实现，提供游戏服务器的共享基础类型、网络通信、RPC、业务运行时和数据访问组件。Java 25，无 preview 特性；Maven 多模块，公共包名为 `cn.managame.*`。

| 模块 | 职责 |
| --- | --- |
| game-core | 共享 Metadata、类型化 MetadataKey、统一框架错误码 |
| game-network | TCP / 二进制 WebSocket Connection API、原生 TLS / WSS，以及独立 HTTP/1.1 HttpServer |
| game-rpc | RpcNode、主动/被动 Peer、固定 Slot、握手、心跳、重连、call / notify / reply，以及 Netty Wire 编解码 |
| game-runtime | Route 执行、Context、Handler、HTTP 注解/分发、Event、GameTime、可取消 Timer / Cron、跨 Route call |
| game-data | Single/Group 缓存、异步写回、MySQL/JDBC、MongoDB 与 MySQL 追加日志 |
| [game-example](game-example/README.zh-CN.md) | Network、Runtime HTTP、RPC 可运行示例与示例执行测试 |
| [game-demo](game-demo/README.zh-CN.md) | Spring 应用，装配 MySQL Data Repository、GamePacket TCP 分发、Runtime HTTP、定时与 cron |

依赖方向：

```text
game-core ──────→ game-data
    ├──────────→ game-runtime ←──── game-network
    └──────────→ game-rpc     ←──── game-network
game-network / game-runtime / game-rpc ───→ game-example
```

game-rpc 在同一 Maven 模块中包含 RPC 核心与 cn.managame.rpc.netty 适配包，依赖 game-network；RPC 不依赖 Runtime 或协议注册表。game-core 为 Runtime/Data 缓存统一发布 Caffeine 依赖，归属见 [Core Java 规范](docs/ogbs/OGBS-Core-Java-25-Specification-1.0.zh-CN.md#1-模块与职责)。Runtime 为 HTTP 注解与 Route 接入依赖 game-core 和 game-network，不依赖 RPC、Spring 或业务序列化。

Network 和 RPC 均以一个 Maven artifact 发布，内部按职责划分子包。Network 入口见 [game-network](game-network/README.zh-CN.md)；RPC 入口见 [game-rpc](game-rpc/README.zh-CN.md)。

## OGBS 规范文档

每个框架组件必须配套标准规范和 Java 开发规范；当前五个组件的成对文档统一入口见 [OGBS 1.0 文档索引](docs/ogbs/README.zh-CN.md)。

| 组件 | 标准规范（语言无关） | Java 开发规范 |
| --- | --- | --- |
| game-core | [OGBS Core Specification](docs/ogbs/OGBS-Core-1.0.zh-CN.md) | [Core Java 开发规范](docs/ogbs/OGBS-Core-Java-25-Specification-1.0.zh-CN.md) |
| game-network | [OGBS Network Specification](docs/ogbs/OGBS-Network-1.0.zh-CN.md) | [Network Java 开发规范](docs/ogbs/OGBS-Network-Java-25-Specification-1.0.zh-CN.md) |
| game-rpc | [OGBS RPC Specification](docs/ogbs/OGBS-RPC-1.0.zh-CN.md) | [RPC Java 开发规范](docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md) |
| game-runtime | [OGBS Runtime Specification](docs/ogbs/OGBS-Runtime-1.0.zh-CN.md) | [Runtime Java 开发规范](docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md) |
| game-data | [OGBS Data Specification](docs/ogbs/OGBS-Data-1.0.zh-CN.md) | [Data Java 开发规范](docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md) |

game-data 已提供 Single/Group 缓存与异步写回、MySQL/JDBC、MongoDB 适配及 MySQL 追加日志，依赖 game-core；game-example 在实现可运行 Data 示例时再添加 game-data 依赖。详见 [模块入口](game-data/README.zh-CN.md)、[Data 语义规范](docs/ogbs/OGBS-Data-1.0.zh-CN.md) 和 [Data Java 开发规范](docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md)。实机数据库验证状态见模块文档。

共享 Metadata 与错误码见 [OGBS Core](docs/ogbs/OGBS-Core-1.0.zh-CN.md)，字节布局见 [RPC Wire Profile](docs/rpc-wire.zh-CN.md)。

## 构建与运行

安装 JDK 25，确保 mvn -version 使用该 JDK：

```shell
mvn clean verify
mvn -pl game-network -am test
```

当前根构建包含 game-core、game-network、game-rpc、game-runtime、game-data、game-example、game-demo。[game-demo 应用](game-demo/README.zh-CN.md) 通过普通 Spring Context 初始化应用持有的 MySQL DataSource/Data Repository，并提供 TCP 分发和 [HTTP/定时/cron 示例](game-demo/README.zh-CN.md#demo-runtime-services)，可用 `mvn -pl game-demo -am clean verify` 构建。独立可运行示例及其执行测试统一放在 game-example 的 `cn.managame.example.<component>` 包中，框架 artifact 不包含示例类。使用 mvn -pl game-example -am test 验证示例。RPC→Runtime 集成和 DataMemoryDemo 尚未实现。

在 IDE 运行 [NetworkEchoExample](game-example/src/main/java/cn/managame/example/network/NetworkEchoExample.java) 可得到 hello game-network。示例使用本机随机端口、长度 framing 和字符串编解码，结束后释放网络资源。

[HttpServerExample](game-example/src/main/java/cn/managame/example/network/HttpServerExample.java) 演示独立 HTTP/1.1 服务端、应用 health/echo handler 和 JDK 示例调用方。[HttpAsyncServerExample](game-example/src/main/java/cn/managame/example/network/HttpAsyncServerExample.java) 演示 asyncHandler 与应用执行器上的 HttpResponseCallback 完成。框架 API 位于 cn.managame.network.http，详见 [HTTP 契约](docs/ogbs/OGBS-Network-Java-25-Specification-1.0.zh-CN.md#native-http-server-api)。

Network 测试覆盖 TCP/TLS/WS/WSS、顺序、引用计数、异常、背压、握手失败及关闭/中断竞争。临时证书由当前 JDK keytool 创建。Windows Network 测试让 JDK Selector 唤醒管道回退到 TCP，并限定默认 Netty 线程数；生产框架不修改 JVM 属性。数据库实机验证状态见 Data 模块文档。
## Runtime 包结构与业务时间

[game-runtime 模块目录](game-runtime/README.zh-CN.md) 按 context、route、executor、protocol、handler、http、event、timer、time、error、internal 划分职责。根包只保留 GameRuntime / GameRuntimeBuilder；使用方需要按新子包更新 import。

```java
import cn.managame.runtime.time.GameTime;

GameTime.setClock(Clock.offset(Clock.systemUTC(), Duration.ofDays(1)));
runtime.cron().rescheduleAll(); // 显式重算所有 Cron，包含已取消项
runtime.cron().cancel(SystemCron.class, "dailyReset");
runtime.cron().reschedule(SystemCron.class, "dailyReset");
GameTime.resetClock();
```

Clock/Duration 来自 java.time，SystemCron 是应用类。GameTime 修改整个进程的业务墙钟，**不会自动重排**已创建的 Timer/Cron。动态业务 Timer 由业务 cancel 后按新时间重新 schedule。Cron 使用声明类 + 方法名作为 Key；详情见 [时间 API](docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#10-gametimetimer-与-cron)。

## Runtime 最小用法

```java
record Request(long id) {}
record Response(long value) {}

@Handler(domain = 1)
class Requests {
    @HandlerMethod
    public void handle(HandlerContext context, Request request) {
        // 已在 (domain=1, key=request.id) 的串行执行范围内。
    }
}

ProtocolProvider protocols = registrar -> {
    registrar.register(Protocols.request(1001, Request.class));
    registrar.register(Protocols.response(1001, Response.class));
    registrar.bindResponse(Request.class, Response.class);
};

try (GameRuntime runtime = GameRuntimeBuilder.builder()
        .routeDomains(List.of(RouteDomain.of(1, "application-defined")))
        .routeExecutors(List.of(
            RouteExecutorBinding.of(RouteExecutors.platformThreads(4), 1)))
        .protocols(List.of(protocols))
        .handlers(List.of(new Requests()))
        .build()) {
    runtime.dispatch(new DefaultHandlerContext(1, 10001, new Request(10001)));
}
```

接入层自己解码消息并构造 Context。Context 默认实现可继承；框架不自动读取 `getRoleId/getGuildId`。需要提取消息字段时使用显式的 `RouteKeyBinding.of(Request.class, Request::id)`。

## Network / RPC 接入

Network 的公开入口为 cn.managame.network.netty.NetworkServer 与 NetworkClient，业务通过 ConnectionHandler 接收生命周期、消息、事件与异常。普通发送失败交给 onException，由业务决定是否关闭；write 的三种结果只表达接纳状态。

game-network 是 Netty 的薄封装：用户添加原生 pipeline handler，通过 ConnectionHandler 接收消息。组件不保存连接或未完成尝试集合，连接索引、会话、重连及批量关闭属于上层。

完整用法与资源所有权见 [Network 模块](game-network/README.zh-CN.md) 和 [Network Java 开发规范](docs/ogbs/OGBS-Network-Java-25-Specification-1.0.zh-CN.md)。

RPC 已提供 RpcNode Builder、自管 TCP Server/Client、时间轮、多 Slot、主动/被动 Peer 与 call/notify/reply。运行 [RpcEchoExample](game-example/src/main/java/cn/managame/example/rpc/RpcEchoExample.java) 可看到 hello game-rpc。RpcHandler 统一处理消息、远端错误与应用解码；RPC→Runtime 自动接入尚未实现。
## 约定与当前边界

- 没有预置业务 RouteDomain。每个已注册 Domain 必须且只能绑定一个 RouteExecutor；允许多个 Domain 共享执行器。
- `build()` 完成校验并立即可用。注册表冻结；不提供运行期动态注册。
- `runtime.call()` 必须在该 Runtime 的 Context 内调用。成功和失败回调都回原 Route、恢复原 Context；返回 Route 已拒绝时只报告错误，不在别的 Route 上执行回调。
- 同 Route 串行不等于异步调用期间锁住实体。跨 Route 返回值应为不可变结果、快照或独立 DTO。
- Metadata 的 `byte[]` 按不可变约定共享。Wire 为 `uint16 key + uint16 length + bytes`，不含类型字段。读取时才调用 Key 的 codec。
- Java RPC body 使用 ByteBuf；入站回调内借用，异步使用必须 retain/copy。出站通过校验后接管一个引用，编码为连续帧并释放 body。
- Cron 支持六字段数字表达式（秒、分、时、日、月、周），支持 `* ? , - /`，周日为 1，默认 UTC。当前不支持 Quartz 的 `L/W/#`、名称与年份字段。
- RPC 每 Node 一个 HashedWheelTimer，负责调用超时、握手超时和重连延迟；心跳由连接上的 IdleStateHandler 负责。调用超时从网络 ACCEPTED 后开始。
- Network 回调与 RPC 完成回调应快速返回；耗时业务应投递到 Runtime。RPC Core 不自动解码响应，也不自动切换 Runtime Route。
- 当前提供 Core、Network、RPC、Runtime、Data 实现与测试；RPC 包含真实 TCP 与重连测试，自动 Runtime 接入仍待实现，尚未进行生产容量基准测试。Spring 自动装配、协议代码生成、服务发现、Router、业务 codec 均为外围集成。

详见 [架构与执行契约](docs/architecture.zh-CN.md) 和 [本仓库 RPC Wire Profile](docs/rpc-wire.zh-CN.md)。

<a id="runtime-http"></a>

## HTTP 业务入口

`cn.managame.runtime.http` 在 game-runtime artifact 中提供 `@HttpHandler`、`@HttpMethod`、`HttpRequestMethod`、`HttpContext`、`DefaultHttpContext`、`HttpContextFactory`、`HttpResultCallback`、`HttpResultCodec`、`HttpDispatcher`。HttpContext 继承基础 Context，包含 Route、请求及结果回调，不含业务身份/Metadata 字段。依赖 game-network，与 Handler/Event/call 共用 Domain/RouteKey 执行器。

通过 `httpHandlers(...)` 注册实例。HttpMethod.method 使用 HttpRequestMethod 枚举，默认 POST；GET 需显式 `method=HttpRequestMethod.GET`。在 @HttpHandler/@HttpMethod 设置 `routeKey="playerId"`，GET 从 query 取字段，其他方法从 JSON body 顶层字段提取；方法配置覆盖类规则。也可设置 routeKeyMethod 指向 Handler 提取方法。可选的四参数 `httpContextFactory(domain, key, request, callback)` 保留选定 Key，可增加应用自定义 HTTP Context 字段；无规则时必须提供工厂选 Key。通过 `HttpServer.builder().asyncHandler(runtime.http()::dispatch)` 接入。public 方法返回业务 DTO/对象或 void，延迟完成使用 `context.responseCallback().onResponse(dto)`。Runtime 默认编码 JSON，在内部创建传输响应，业务结果不携带 HTTP 版本；通过 `httpResultCodec(...)` 自定义结果编码。按原始方法/path 精确匹配，不自动绑定请求 DTO 或推导玩家 ID。请求只借用到方法返回；延迟完成响应不会延长请求生命周期。

运行 [RuntimeHttpExample](game-example/src/main/java/cn/managame/example/runtime/RuntimeHttpExample.java) 查看注解 echo 和跨 Route 延迟响应。详见 [HTTP 语义](docs/ogbs/OGBS-Runtime-1.0.zh-CN.md#runtime-http-profile) 与 [Java API、失败及所有权](docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#runtime-http-api)。
