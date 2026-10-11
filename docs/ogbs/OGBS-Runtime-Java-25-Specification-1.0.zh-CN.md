# OGBS Runtime Java 25 Development Specification 1.0

[English](OGBS-Runtime-Java-25-Specification-1.0.md) | **[简体中文](OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md)**

文档类型：**Java 开发规范**。对应标准：[OGBS Runtime Specification](OGBS-Runtime-1.0.zh-CN.md)。

本文规定 Java 实现的公开 API、默认配置、异常形式、线程与资源机制、扩展接入以及验证要求。Java 实现必须同时满足本文和对应标准规范；不能只满足方法签名而忽略行为契约。代码与规范冲突时应修正实现，设计变更则同步修订两层规范。下文明确标注的待实现、未验证能力不代表已完成。

状态：当前仓库实现约定。语义规范见 [OGBS Runtime](OGBS-Runtime-1.0.zh-CN.md)，共享类型见 [OGBS Core](OGBS-Core-1.0.zh-CN.md)。

## 1. 模块与主 API

Maven 坐标为 `cn.managame:game-runtime:1.0.0-SNAPSHOT`，入口包名 `cn.managame.runtime`，按职责分为子包，依赖 `game-core`、`game-network` 和用于 HTTP 结果编码与 JSON Key 提取的 Jackson Databind 2.21.3（传递依赖 Core/Annotations），并从 game-core 传递引入共享 Caffeine 以复用空闲 Mailbox，要求 Java 25。HTTP 是该 artifact 内的子包，直接使用 Netty HTTP 类型，不新增 Maven 模块。Runtime 不依赖 RPC。

以下代码块列出接口签名；类型以源码为准。

```java
public interface GameRuntime extends AutoCloseable {
    void dispatch(HandlerContext context);
    void dispatch(Connection connection, long routeKey, Object message);
    void dispatch(Connection connection, long routeKey, int businessIdType, long businessId, Object message);
    void dispatch(Connection connection, Object message);
    void dispatch(Connection connection, Metadata metadata, Object message);
    void dispatch(Connection connection, long key, int businessIdType, long businessId, Metadata metadata, Object message);
    void dispatchRpc(int sourceNodeId, int sourceSlotId, int command, int requestId, long key,
                     int businessIdType, long businessId, Metadata metadata, Object message);
    void dispatch(Connection connection, int businessIdType, long businessId, Object message);
    HttpDispatcher http();
    <T> void call(int routeDomain, long routeKey,
                  Supplier<T> action, RouteCallback<T> callback);
    EventBus eventBus();
    RuntimeTimer timer();
    CronScheduler cron();
    ProtocolRegistry protocols();
    RouteKeyRegistry routeKeys();
    <T> RouteCallback<T> callback(RouteCallback<T> callback);
    void shutdown();
    boolean awaitTermination(Duration timeout) throws InterruptedException;
    RuntimeStats stats();
    void close();
}

public interface RouteCallback<T> {
    void onSuccess(T result);
    void onFail(int errorCode);
}
```

没有独立 start 阶段、动态注册 API 或公开的任意 Runnable 分发入口。build 成功后可使用。业务消息通常使用 `dispatch(connection, routeKey, businessIdType, businessId, message)`，应用无需构造 Route 或 Context；匿名调用可省略身份。自定义上下文字段仍可使用显式 HandlerContext 分发，Metadata 也有直接传入的重载。消息提取有独立重载，见 [§6.2](#automatic-handler-dispatch)。HTTP 通过 `asyncHandler(runtime.http()::dispatch)` 接入，见 [§13](#runtime-http-api)。

按 Domain 决定外部接入参数时，配置 `handlerContextFactory(...)` 并使用 `dispatch(connection, message)`，工厂接收注解解析的 Domain，从应用管理的身份存储选择路由/身份；显式参数保留优先级。见 [§6.4](#handler-context-factory)。

公开 API 的包划分：

| 包 | 内容 |
| --- | --- |
| cn.managame.runtime | GameRuntime、GameRuntimeBuilder |
| cn.managame.runtime.context | Context 体系、默认实现、只读 Contexts |
| cn.managame.runtime.route | Domain、Key 绑定/提取/注册、RouteCallback |
| cn.managame.runtime.executor | Executor SPI、绑定与官方实现 |
| cn.managame.runtime.protocol | 协议描述、注册、请求响应关联 |
| cn.managame.runtime.handler | Handler 注解、HandlerArgumentBinding、HandlerContextFactory |
| cn.managame.runtime.http | HttpHandler、HttpMethod、HttpRequestMethod、HttpDispatcher、HttpContext、DefaultHttpContext、HttpContextFactory、HttpResultCallback、HttpResultCodec |
| cn.managame.runtime.event | Event、EventBus、事件注解 |
| cn.managame.runtime.timer | RuntimeTimer、TimerRef、Cron、CronScheduler |
| cn.managame.runtime.time | GameTime |
| cn.managame.runtime.error | RuntimeError、处理器与分发异常 |
| cn.managame.runtime.internal | 注册编译、调度与上下文绑定内部实现，应用不得依赖 |

原先 `cn.managame.runtime.*` 中的类型已迁移到对应子包。Java 通配 import 不包含子包，调用方需更新 import。Maven artifact 仍为一个 game-runtime，未拆成多个发布单元。详见 [模块目录](../../game-runtime/README.zh-CN.md)。

## 2. 构建

```java
GameRuntimeBuilder.builder()
    .routeDomains(Iterable<RouteDomain>)
    .routeExecutors(Iterable<RouteExecutorBinding>)
    .protocols(Iterable<? extends ProtocolProvider>)
    .routeKeys(Iterable<RouteKeyBinding<?>>)
    .handlers(Iterable<?>)
    .handlerArguments(Iterable<? extends HandlerArgumentBinding<?>>)
    .handlerContextFactory(HandlerContextFactory)
    .httpHandlers(Iterable<?>)
    .httpContextFactory(HttpContextFactory)
    .httpResultCodec(HttpResultCodec)
    .eventHandlers(Iterable<?>)
    .cronHandlers(Iterable<?>)
    .errorHandler(RuntimeErrorHandler)
    .cronZone(ZoneId)
    .build();
```

上述列表配置方法采用替换语义，每次调用复制本次 Iterable，不累加旧配置。列表默认空，Cron 默认 UTC，默认错误处理器写入 System.Logger。

`RouteDomain.of(int id, String name)` 要求 id > 0、name 非 null。`RouteExecutorBinding.of(executor, int... domains)` 必须绑定至少一个 Domain。构建要求每个已注册 Domain 恰好绑定一个 Executor，不允许绑定未知 Domain。

构建时解析注解并编译 MethodHandle；注册表在成功后固定。没有 classpath 扫描，实例由调用方显式提供。Handler/Event/Cron 方法必须 public、实例方法、返回 void、非 varargs；HTTP 方法还可返回普通业务对象，拒绝传输响应类型和基本类型返回声明。错误配置通常抛出 IllegalArgumentException，null 输入可能抛出 NullPointerException。

构建失败不自动关闭调用方提供的 Executor；成功后 Runtime 负责关闭。不要把生命周期独立的 Runtime 绑定到同一个可关闭 Executor，除非明确实现了外部所有权适配。

### 2.1 构建阶段应完成什么

构建不是把对象列表保存下来等第一次消息再解析。实现需要在返回可用实例前，编译 Domain/Executor 绑定、协议与响应关系、Key 提取器、Handler/Event/Cron 与 HTTP 方法和 Cron 表达式。重复或无法解析的定义在启动时拒绝，不能让同一部署因为消息到达顺序不同而选择不同 Handler。

```text
应用显式创建业务对象和 Executor
→ Builder 复制每次传入的列表
→ RuntimeCompiler 校验注册关系并编译方法
→ 建立固定注册表与执行入口
→ 装配 Timer/Cron
→ 返回可工作的 Runtime
```

列表快照只固定注册对象的集合，不深复制业务对象内部状态。Handler 实例仍是应用提供的同一个对象；它可以同时服务不同 Route，因此不能把“实例字段天然串行”当成保证。可变业务状态应按 Route 组织。

配置列表采用替换语义，例如先 handlers(A)，再 handlers(B)，最终只注册 B。需要同时注册多个模块的 Handler 时，应用先合并列表再设置。此行为与 Data 的追加注册、Network 的追加 pipeline 不同，不能由 Builder 名字推断。

### 2.2 注册失败的定位

| 检查类别 | 典型错误 | 修正位置 |
| --- | --- | --- |
| Domain/Executor | 重复 ID、未知 Domain、缺少绑定 | 应用启动装配 |
| Protocol | 相同类型或相同 type+command 重复 | ProtocolProvider |
| 请求响应 | 绑定未注册类型或类型方向错误 | bindResponse 注册 |
| Handler | 消息未注册、同消息多方法、Domain 非法 | 注解和 handlers 列表 |
| Context | 方法声明不能接收 HandlerContext 体系 | Handler 方法签名 |
| Event | 方法并非单个 Event 参数 | 监听器定义 |
| Cron | 表达式无效、目标 Route 无效、重复声明类+方法名 | Cron 注解及注册列表 |

构建失败前应用创建的 Executor 仍由应用清理。成功构建后，Runtime 会在 close 时关闭已绑定 Executor；不要让两个生命周期互不相关的 Runtime 不加适配地共享同一个 Executor。


## 3. 最小接入示例

```java
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeBuilder;
import cn.managame.runtime.context.DefaultHandlerContext;
import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.runtime.protocol.ProtocolProvider;
import cn.managame.runtime.protocol.Protocols;
import cn.managame.runtime.route.RouteDomain;
import cn.managame.runtime.route.RouteKeyBinding;
import java.util.List;

public class RuntimeExample {
    public record Ping(long playerId) {}

    @Handler(domain = 1)
    public static final class PingHandler {
        @HandlerMethod
        public void onPing(HandlerContext context, Ping message) {
            System.out.println(context.routeKey() + ": " + message);
        }
    }

    public static void main(String[] args) {
        ProtocolProvider protocols = registrar ->
            registrar.register(Protocols.notify(1001, Ping.class));

        try (GameRuntime runtime = GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "player")))
                .routeExecutors(List.of(RouteExecutorBinding.of(
                    RouteExecutors.platformThreads(2), 1)))
                .protocols(List.of(protocols))
                .routeKeys(List.of(RouteKeyBinding.of(Ping.class, Ping::playerId)))
                .handlers(List.of(new PingHandler()))
                .build()) {
            Ping message = new Ping(42);
            long key = runtime.routeKeys().getRouteKey(message);
            runtime.dispatch(new DefaultHandlerContext(1, key, message));
        }
    }
}
```

示例中的 Key 提取是接入层显式调用。close 不等待执行完成；官方平台线程执行器会继续处理已接受的任务。实际服务应自行安排停止入口与在途业务完成的顺序。

## 4. Context 与作用域

```java
interface Context {
    int routeDomain();
    long routeKey();
}
interface InvocationContext extends Context {
    int businessIdType();
    long businessId();
    Metadata metadata();
}
interface HandlerContext extends InvocationContext { Object message(); }
interface ClientHandlerContext extends HandlerContext { Connection connection(); }
interface RpcHandlerContext extends HandlerContext {
    int sourceNodeId();
    int sourceSlotId();
    int command();
    int requestId();
}
interface EventContext extends InvocationContext { Event event(); }
interface TimerContext extends Context {}
interface RouteCallContext extends InvocationContext {}
// cn.managame.runtime.http；完整契约见第 13 节
interface HttpContext extends Context {
    FullHttpRequest request();
    HttpResultCallback responseCallback();
}
```

Metadata 位于 `cn.managame.core`。默认实现的构造器：

| 类型 | 构造参数 |
| --- | --- |
| DefaultContext | (int domain, long key) |
| DefaultInvocationContext | (int domain, long key, int businessIdType, long businessId, Metadata metadata) |
| DefaultHandlerContext | (int domain, long key, Object message) |
| DefaultClientHandlerContext | (int domain, long key, Object message, Connection connection) |
| DefaultHandlerContext | (int domain, long key, int businessIdType, long businessId, Metadata metadata, Object message) |
| DefaultClientHandlerContext | (int domain, long key, int businessIdType, long businessId, Metadata metadata, Object message, Connection connection) |
| DefaultRpcHandlerContext | (int domain, long key, int businessIdType, long businessId, Metadata metadata, Object message, int sourceNodeId, int sourceSlotId, int command, int requestId) |
| DefaultEventContext | (Event event, int businessIdType, long businessId, Metadata metadata) |
| DefaultTimerContext | (int domain, long key) |
| DefaultRouteCallContext | (int domain, long key, int businessIdType, long businessId, Metadata metadata) |

短 HandlerContext 构造器使用身份 0 和空 Metadata。businessIdType 范围 0–255，Metadata 和 message 不得为 null。默认类可继承，允许接入层增加连接、请求关联等信息；这些额外信息不会自动复制进 EventContext 或 RouteCallContext。

Connection 是 `cn.managame.network.connection.Connection`。DefaultClientHandlerContext 通过 final 字段保存借用引用，允许 null。业务通过 `context.connection()` 或 `Contexts.current(ClientHandlerContext.class).connection()` 读取连接，不将 Connection 作为独立 Handler 方法参数。Runtime 不查询活跃状态，也不关闭连接；任务排队期间断连后，写入可能拒绝。公共 HandlerContext 不暴露连接，专用字段与迁移见 §4.2。

```java
Context Contexts.current();
Context Contexts.currentOrNull();
<T extends Context> T Contexts.current(Class<T> type);
```

实现使用 Java 25 ScopedValue。current 在未绑定时抛 NoSuchElementException，currentOrNull 返回 null，类型转换错误抛 ClassCastException。只有 Runtime 执行任务时绑定，无公共手动 bind API；嵌套任务结束后恢复外层值。

### 4.1 绑定与恢复的实现约束

内部 RuntimeContexts 同时绑定 Runtime 身份与 Context。执行包装的逻辑可概括为以下伪代码，省略错误处理，不是新的公开 API：

```text
执行任务:
    在当前 Runtime + 本次 Context 的 ScopedValue 作用域中运行动作
    动作结束时恢复此前作用域（包括异常退出）

提交任务:
    校验目标 Route
    若当前 Runtime 身份、Domain、Key 全部相等：立即运行执行包装
    否则：交给已绑定 RouteExecutor.tryExecute
```

这解释了为什么只比较 Context.routeKey() 不够，也解释了为什么回调需要保留原 Context 对象。外层是自定义 HandlerContext 时，跨 Route action 使用 DefaultRouteCallContext；回源后才再次得到原自定义对象。

业务代码可用 Contexts.current(MyHandlerContext.class) 读取自己的扩展字段，但必须知道当前动作确实是该上下文类型。事件、Timer 和目标 RouteCall 中不能假定它仍然是网络请求 Context。框架不提供公开 bind 来让任意线程冒充 Route 执行。


<a id="transport-handler-contexts"></a>

### 4.2 客户端与 RPC Handler 上下文

ClientHandlerContext 表示玩家/客户端接入来源，不限定具体传输协议，可携带任意 game-network Connection，包括 WebSocket 连接；名称本身不表示已鉴权。RpcHandlerContext 表示服务间 RPC 接入，包括 Call 和 Notify。

HandlerContext/DefaultHandlerContext 只包含解码消息及继承的调用字段。ClientHandlerContext/DefaultClientHandlerContext 增加借用 Connection；RpcHandlerContext/DefaultRpcHandlerContext 增加 sourceNodeId、sourceSlotId、command、requestId，不暴露 Connection，也不依赖 game-rpc。HTTP 保持独立 HttpContext 体系，不携带调用身份/Metadata。

所有 Connection 便捷分发重载都创建 DefaultClientHandlerContext，包括 Connection 为 null 时。HandlerContextFactory.create 返回 ClientHandlerContext；自定义工厂上下文继承 DefaultClientHandlerContext 或实现 ClientHandlerContext。普通 Handler 方法可接收 HandlerContext/DefaultHandlerContext 处理与传输无关的业务，ClientHandlerContext 处理客户端连接业务，或 RpcHandlerContext 处理 RPC 业务；消息和上下文参数的前后顺序不限。分发时所需子类型不兼容，以 HANDLER_CONTEXT_MISMATCH（3003）在参数解析和接纳前拒绝。仅接收消息的方法仍受支持。

DefaultRpcHandlerContext 要求 sourceNodeId 和 command 非 0、sourceSlotId 范围 0..254、businessIdType 范围 0..255，且 Metadata/message 非 null。非法数值抛 IllegalArgumentException，null 抛 NullPointerException。节点 ID、command 和非零 requestId 保留无符号线格式对应的负 int 位模式；requestId=0 表示 Notify。Domain 和非零 Key 在分发时校验，Domain 必须匹配精确消息 Handler 的注解配置。上下文构造不检查 command 与协议注册的对应关系、不按当前 Peer 拓扑检查来源 Slot，也不鉴权信封；这些检查由接入层负责。

RPC 适配层在回调返回前解码借用的 RpcRequest body，然后提交解码对象和复制的信封基本类型值。若要延后访问借用 ByteBuf，必须另行安排其生命周期。上下文保留解码消息且不复制，不拥有 RPC buffer；应用需提供适合延后在 Route 执行的消息。RPC 传输亲和值为 0 不代表 Runtime Key 可以为 0，接入方须选择非零业务 Key。sourceSlotId 仅用于回复选路。不增加自动适配器、RPC 响应编码、send/reply 方法或额外执行器。

```java
@Handler(domain = 1)
class PlayerHandlers {
    @HandlerMethod public void login(ClientHandlerContext context, LoginReq request) {
        Connection connection = context.connection();
        // 先在此校验 token，再手动绑定应用身份。
    }
    @HandlerMethod public void update(RpcHandlerContext context, UpdateReq request) {
        long roleId = context.businessId();
        int sourceNodeId = context.sourceNodeId();
        int sourceSlotId = context.sourceSlotId();
        int requestId = context.requestId();
        // 需要回复时，接入/业务代码通过 RPC 层编码和回复。
    }
}
// 外部 RPC 接入：此前已按注解选择 Domain 和业务 Key：
runtime.dispatch(new DefaultRpcHandlerContext(domain, key, businessIdType, businessId,
        metadata, decodedMessage, sourceNodeId, sourceSlotId, command, requestId));
```

迁移：此次源码契约变化后需重新编译 Handler/工厂。包含连接的 DefaultHandlerContext 构造改用 DefaultClientHandlerContext；从 HandlerContext/DefaultHandlerContext 访问连接改用 ClientHandlerContext（或 Contexts.current(ClientHandlerContext.class)）。不包含连接的 DefaultHandlerContext 构造器继续可用。原来继承 DefaultHandlerContext 并暴露连接的自定义上下文，改为继承 DefaultClientHandlerContext 或实现 ClientHandlerContext。通用业务身份访问不变。专用 Handler 不改变消息多态规则：每个精确消息类型仍只有一个 Handler，共用多个传输的消息使用公共 HandlerContext 签名，仅在需要时检查传输能力。

源码：[上下文类型](../../game-runtime/src/main/java/cn/managame/runtime/context)、[分发实现](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java)。[TransportContextTest](../../game-runtime/src/test/java/cn/managame/runtime/TransportContextTest.java) 验证共用串行 Route、接纳前类型拒绝、关闭后执行已接纳任务、Notify/Call 关联、嵌套 TCP/RPC 恢复及 Event/call 仅继承调用字段。[RPC 回复所有权与 Slot](OGBS-RPC-Java-25-Specification-1.0.zh-CN.md) 保持不变，可选真实 RPC 到 Runtime 网络接入由 game-spring 的 RpcAssemblyTest 验证。

## 5. Protocol 与 RouteKey 注册

```java
interface ProtocolProvider { void register(ProtocolRegistrar registrar); }

interface ProtocolRegistrar {
    void register(ProtocolDescriptor<?> descriptor);
    void bindResponse(Class<?> requestType, Class<?> responseType);
}

interface ProtocolDescriptor<T> {
    ProtocolType type();
    int command();
    Class<T> messageType();
}
```

ProtocolType 为 REQUEST、RESPONSE、NOTIFY。工厂为 `Protocols.request(command, type)`、`response`、`notify`。command 使用 int 的完整 32 位模式，不限制为正数或 24 位。

注册表按“ProtocolType + command”和精确消息 Class 建索引；两种索引都不得重复。REQUEST 与 RESPONSE 可以使用相同 command。bindResponse 的两端必须分别注册为 REQUEST、RESPONSE；每个请求至多绑定一个响应，但不强制所有请求都绑定响应。

```java
ProtocolDescriptor<?> ProtocolRegistry.get(ProtocolType type, int command);
ProtocolDescriptor<?> ProtocolRegistry.get(Class<?> messageType);
Class<?> ProtocolRegistry.getResponseType(Class<?> requestType);

long RouteKeyExtractor<T>.extract(T message);
RouteKeyBinding<T> RouteKeyBinding.of(
    Class<T> messageType, RouteKeyExtractor<T> extractor);
RouteKeyBinding<T> RouteKeyBinding.ofField(Class<T> messageType, String field);
RouteKeyBinding<T> RouteKeyBinding.ofMethod(Class<T> messageType, String method);
long RouteKeyRegistry.getRouteKey(Object message);
```

非 null 类型的查询未命中时返回 null。Key 注册独立于协议注册；按精确 Class 查询，未找到提取器返回 0。提取器异常传回调用方，0 无法作为有效分发 Key。显式上下文及外部 Key 入口不使用提取规则替换 Key；只有消息提取重载主动执行提取。

## 6. Handler

`@Handler(domain = ...)` 是 handlers 列表内对象必须具备的类型注解，可以从父类继承。`@HandlerMethod(domain = ...)` 的非零 domain 覆盖类上的 domain，否则使用类配置。

两个注解还声明 `String routeKey() default ""`、`String routeKeyMethod() default ""`，可选规则引用已注册消息类上的字段或 public 无参实例方法。方法注解的非空规则替换整个类规则，包括从字段切换为方法。均未配置时不添加提取器，外部 Key 入口无需规则。两种名字同时配置或只有空白字符时构建拒绝，类规则非法时即使方法覆盖也拒绝。同一消息同时存在注解规则和 builder 显式绑定时按重复配置拒绝，不设置隐式优先级。

方法必须包含且仅包含一个已注册 REQUEST/NOTIFY 消息参数，可以额外带一个 Context 参数及零到多个显式注册的自定义类型参数，参数顺序不限，例如：

```java
@HandlerMethod
public void onMessage(MyMessage message) { /* ... */ }

@HandlerMethod
public void onMessage(MyHandlerContext context, MyMessage message) { /* ... */ }
```

Context 参数可为 Context、InvocationContext、HandlerContext，或自定义 HandlerContext 实现。EventContext、TimerContext 等不属于 HandlerContext 的类型不能替代 HandlerContext。分发会检查实际 Context 对象是否满足方法参数类型。

消息按精确 Class 匹配；每种消息最多一个 Handler。dispatch 的前置校验与接纳失败抛 RuntimeDispatchException；已开始执行的 Handler 异常交给 RuntimeErrorHandler，即使当前是同 Route 内联执行也不会改成业务响应。

### 6.1 方法签名判定示例

以下 M 表示已注册 REQUEST/NOTIFY 类型，CustomContext 表示 HandlerContext 子类型：

| 形式 | 是否可作为 Handler 方法 | 原因 |
| --- | --- | --- |
| public void handle(M m) | 可以 | 单个消息参数 |
| public void handle(HandlerContext c, M m) | 可以 | 一个消息、一个合法上下文 |
| public void handle(M m, CustomContext c) | 可以 | 参数顺序不限；分发检查实际 Context |
| public void handle(Extra value, M m) | 可选注册绑定后可以 | Extra 是显式注册的应用参数类型 |
| public void handle(Connection c, M m) | 不自动绑定 | 从 ClientHandlerContext 读取可选连接 |
| public M handle(M m) | 不可以 | 返回值必须 void，不自动发送返回值 |
| public static void handle(M m) | 不可以 | 必须是实例方法 |
| public void handle(M a, M b) | 不可以 | 消息参数不唯一 |
| public void handle(TimerContext c, M m) | 不可以 | TimerContext 不是此 Handler 可接收的上下文 |
| public void handle(M... messages) | 不可以 | 不支持 varargs |

分发检查失败与业务执行失败必须分开：RuntimeDispatchException 表示前置条件或接纳失败；Handler 内抛出的异常进入 RuntimeErrorHandler。即使同 Route 内联，调用方也不能通过捕获 Handler 的原始业务异常来获得业务结果。


<a id="automatic-handler-dispatch"></a>

### 6.2 外部 Key 分发与少数消息提取场景

业务常规调用是 `runtime.dispatch(connection, routeKey, businessIdType, businessId, message)`。Runtime 按消息精确类型查找 Handler 注解的 Domain，保留调用方传入的非零 Key，不读取协议成员、不调用已注册提取器，再构造包含连接/消息、显式身份及空 Metadata 的 DefaultClientHandlerContext。businessIdType 必须为 0..255，否则抛 IllegalArgumentException；businessId 保留所有 long 值，包括 0 和负数，身份解释/验证由应用负责。匿名调用可使用 `dispatch(connection, routeKey, message)`，身份为 0/0。连接可为 null，通过 Context 读取。需要自定义上下文子类型的 Handler 使用显式上下文入口或 §6.4 的配置工厂；内置上下文不兼容时以 HANDLER_CONTEXT_MISMATCH（3003）拒绝。自定义参数按 §6.3 绑定，不隐式注入 Connection。

少数需要按协议内容路由的消息调用 `runtime.dispatch(connection, businessIdType, businessId, message)`，只有未配置上下文工厂时，匿名提取入口才可省略身份。Runtime 选择 Handler Domain，并调用按精确类型注册的 RouteKey 提取器；它可由显式绑定或可选注解规则编译而来。没有绑定或结果为 0 时抛 INVALID_ROUTE_KEY（3005），没有 Handler 时抛 HANDLER_NOT_FOUND（3002），Runtime 已关闭时抛 RUNTIME_CLOSED（3001）。不通过回退或重试替换 Key。提取器异常发生在提交线程、接纳之前，直接传回调用方；开始执行后的 Handler 异常仍交给 RuntimeErrorHandler。所有这些便捷重载均不继承外层当前上下文的业务身份/Metadata，保留既有执行器容量、同 Route 内联恢复及关闭规则。自定义 GameRuntime 实现需实现新增重载。

```java
@Handler(domain = 1)
class LoginHandler {
    @HandlerMethod
    public void login(ClientHandlerContext context, LoginReq request) {
        Connection connection = context.connection();
    }
}
// 常规路径：调用方已经选择 Key，不读取 request.userId。
runtime.dispatch(connection, 99L, loginReq);

// 少数场景（未配置上下文工厂）：配置 @HandlerMethod(routeKey = "userId")
// 或 @HandlerMethod(routeKeyMethod = "getUserId") 后：
runtime.dispatch(connection, loginReq);
```

`ofField`/`ofMethod` 立即编译，无效配置抛 IllegalArgumentException，null 类型/名字抛 NullPointerException。字段允许 private 及继承，最近声明优先。方法必须 public、实例、无参数、非 varargs，可以继承。值只接受 byte/short/int/long 或 Byte/Short/Integer/Long，精确拓宽为 long；字符串、浮点、静态成员及不存在的成员拒绝。包装值为 null 时提取抛 NullPointerException。方法抛 RuntimeException/Error 原样传回，受检异常包装成带 cause 的 IllegalStateException。0 可以读取，但不能路由。JPMS 必须允许 privateLookupIn，不可访问成员在配置时拒绝。内部 MessageRouteKey 一次编译 MethodHandle，不逐消息反射查找，也不使用 monitor。

源码见 [注解](../../game-runtime/src/main/java/cn/managame/runtime/handler/HandlerMethod.java)、[RouteKeyBinding](../../game-runtime/src/main/java/cn/managame/runtime/route/RouteKeyBinding.java)、[MessageRouteKey](../../game-runtime/src/main/java/cn/managame/runtime/internal/MessageRouteKey.java)、[DefaultGameRuntime](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java)。[HandlerDispatchTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerDispatchTest.java) 验证外部 Key 优先（包括协议字段为 null）、注解覆盖、private/继承字段、getter 异常、无效签名/配置、显式上下文保留、关闭接纳和相同 Key 串行。[DemoHandlerTest](../../game-demo/src/test/java/cn/managame/demo/DemoHandlerTest.java) 验证仅标记 Handler 的 Spring UserHandler：请求 userId=10001 时使用调用方传入的 Key 99。

<a id="handler-arguments"></a>

### 6.3 Context 业务身份与可选 Handler 参数

默认业务写法为 `handle(HandlerContext context, MyMessage request)`，通过 `context.businessId()` 获取业务 ID，需要时读取 `context.businessIdType()`。无需定义 RoleId/GuildId/RoomId 包装类型或注册自定义参数。鉴权和身份类别检查属于业务接入，Context 访问本身不执行这些检查；Key 与业务身份仍独立。直接访问复用 Context 中已有的身份对，避免为每种身份类别分配包装对象和注册转换。下述自定义参数绑定仅供需要额外值或更强类型约束的应用作为可选扩展，不是读取身份的前置要求。

`cn.managame.runtime.handler.HandlerArgumentBinding<T>` 是包含 `Class<T> type` 与 `Function<HandlerContext, ? extends T> resolver` 的 record，可直接构造或用 `of(type, resolver)` 创建。两个成员及 `resolve(context)` 输入均不可为 null。基本类型、Context 派生类型抛 IllegalArgumentException。通过 builder.handlerArguments 注册，复制后的列表替换旧配置，默认空。按声明的精确 Class 绑定，不使用命名约定、反射推断构造器、父类匹配或框架 RoleId 类型。重复绑定类型、同时注册为协议的类型、同一方法重复的自定义类型参数、未绑定参数类型均在构建时抛 IllegalArgumentException；允许未被方法使用的合法绑定。

参数分类依次为 Context、已注册协议、显式参数绑定。仍要求恰好一个入站消息、至多一个 Context。不同自定义类型可投影同一身份对；方法也可省略所有自定义参数和 Context。构建时编译 MethodHandle 参数适配，不在每次分发引入反射查找、额外线程或同步 monitor；没有自定义参数的方法复用空参数数组。

完成 Route/Handler/上下文校验后，在提交线程、接纳之前、绑定目标 Context 作用域之前，按自定义参数声明顺序各解析一次。解析器收到的 context 才是本次输入；Contexts.current() 可能为空或属于外层上下文。解析器必须线程安全，不访问目标 Route 状态，宜由上下文数据创建不可变值。`resolve` 对 null 结果抛 NullPointerException，错误运行时类型抛 ClassCastException。解析器 RuntimeException/Error 原样传回，不提交任务、不通知 RuntimeErrorHandler；已经解析的对象不回滚。同 Route 内联也遵循此规则，之后仍可能发生过载/执行器关闭拒绝。捕获值由 Runtime 借用，不复制/销毁；已接纳任务可在关闭后执行，不再次解析。Handler 执行异常仍按既有规则处理。

```java
// 注册该 Handler 及其请求协议，不需要参数绑定。
@Handler(domain = 1)
class RoleHandler {
    @HandlerMethod public void handle(HandlerContext context, MyMessage request) {
        long roleId = context.businessId();
        // 仅在此处访问角色状态，当前已进入选定的 Route。
    }
}
// 身份来自已鉴权的接入层，与 Key 和消息字段独立。
runtime.dispatch(connection, 99L, 1, 10001L, request);
```

身份类型不自动序列化，也不注册为协议。尚未鉴权的登录可以继续使用 `login(ClientHandlerContext, LoginReq)`，不声明 RoleId。源码：[HandlerArgumentBinding](../../game-runtime/src/main/java/cn/managame/runtime/handler/HandlerArgumentBinding.java)、[RuntimeCompiler](../../game-runtime/src/main/java/cn/managame/runtime/internal/RuntimeCompiler.java)。[HandlerArgumentTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerArgumentTest.java) 验证参数顺序、接纳前仅解析一次、身份/Key/Domain 独立、显式上下文保留、关闭后执行已接纳任务、拒绝和无效绑定。可运行的 Spring 配置使用 [RoleHandler](../../game-demo/src/main/java/cn/managame/demo/bus/role/RoleHandler.java) 直接访问 Context，无参数绑定；[DemoIdentityTest](../../game-demo/src/test/java/cn/managame/demo/DemoIdentityTest.java) 验证 Key/身份独立、虚拟线程执行及缺少会话时的策略拒绝。已有消息/Context 签名继续可用；自定义 GameRuntime 实现需增加身份重载。绑定只作用于 HandlerMethod，不作用于 HTTP/Event/Cron。

demo 的 [GamePacketHandler](../../game-demo/src/main/java/cn/managame/demo/network/GamePacketHandler.java) 反序列化协议号选定的类型，再调用连接/消息 dispatch。其 §6.4 配置策略根据注解解析的 Domain 选择路由/身份：LOGIN 将配置的请求 userId 仅作为排队 Key，身份为 0/0；ROLE 从 Connection 的 AttributeKey 读取一次不可变的已鉴权 [GameSession](../../game-demo/src/main/java/cn/managame/demo/network/GameSession.java)。角色身份由业务鉴权显式设置，不隐式推断；Handler/domain 的策略由应用配置。Runtime 捕获的是解码对象而非 byte[] 帧，不需要保留应用 packet。[GamePacketNetworkTest](../../game-demo/src/test/java/cn/managame/demo/network/GamePacketNetworkTest.java) 验证 LoginReq、PingMessage 经 TCP 到达不同 HandlerMethod 并使用虚拟线程；[GamePacketDispatchTest](../../game-demo/src/test/java/cn/managame/demo/network/GamePacketDispatchTest.java) 验证接纳前拒绝及排队时的会话值保留。这些是接入示例，不代表自动发送响应或已实现鉴权。

应用可像 [GameDomain](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameDomain.java) 一样用自己的枚举组织注册，将显式正整数 ID/名称转成 RouteDomain，并在 @Handler/@HandlerMethod.domain 引用编译期 int 常量；Java 注解不能通过现有 int 元素接收任意用户枚举值，也不能调用枚举方法。不要用 ordinal() 作为持久配置 ID。ROLE 是 demo 业务标签，不具有框架预留的身份或 Key 语义。上述 TCP/Spring 测试执行了此配置。

<a id="handler-context-factory"></a>

### 6.4 根据 Domain 选择接入上下文

`cn.managame.runtime.handler.HandlerContextFactory` 是函数式接口：`ClientHandlerContext create(int domain, Connection connection, Metadata metadata, Object message)`。`builder.handlerContextFactory(factory)` 替换旧工厂，拒绝 null，默认未配置。构建成功后保留该工厂实例，不复制其字段，也不为其提供串行保护；Runtime 不关闭工厂或外部身份存储，其生命周期由应用负责。配置时 `dispatch(connection, message)` 使用工厂；未配置时仍使用既有精确类型消息 Key 提取、身份 0/0。显式上下文、三个/五个参数的外部 Key 重载、四个参数的显式身份/提取重载都绕过工厂，注册工厂不能重写调用方明确指定的路由/身份。有工厂时此入口不调用已配置的注解/RouteKeyBinding 提取器，即使它会失败也不调用，不组合规则，也不回退。

调用前检查关闭状态、非 null 消息及精确 Handler 存在性。传入 @HandlerMethod/@Handler 选择的有效 Domain，不从调用方或协议描述取 Domain。在目标 Context 作用域绑定前调用一次；工厂可从 Connection AttributeKey 或线程安全的外部 Map 获取身份，再按 Domain 选择 Key/businessIdType/businessId，保留传入 Metadata，并返回兼容的应用 Context 子类型。必须保留 Domain 及原消息/连接/Metadata 引用；非网络工厂仍允许 null 连接，可通过抛异常拒绝未鉴权请求。Metadata 可明确提供，但不自动继承外层上下文；Contexts.current() 此时可能属于外层任务。应捕获不可变值，不访问 Route 专属状态。工厂与参数解析器均在提交线程执行，工厂创建先于校验和参数解析，不增加执行器或线程。

返回 null 抛 NullPointerException。改变 Domain 抛 ROUTE_DOMAIN_MISMATCH（3004）；替换消息/连接/Metadata 或 Handler 上下文类型不兼容抛 HANDLER_CONTEXT_MISMATCH（3003）。Key 为 0 抛 INVALID_ROUTE_KEY（3005）；Handler 缺失抛 HANDLER_NOT_FOUND（3002）；Runtime 已关闭抛 RUNTIME_CLOSED（3001）。工厂 RuntimeException/Error 原样传回，不通知 RuntimeErrorHandler、不接纳任务；工厂上下文拒绝时不运行参数解析器。工厂在返回前关闭 Runtime，后续校验仍拒绝。工厂/参数求值之后仍可能发生执行器过载/关闭拒绝，已创建值不销毁、不回滚。接纳后保留返回上下文实例和已解析参数，不再次调用工厂或身份查询；可变自定义字段仍由应用负责。Handler 执行异常仍按既有 RuntimeErrorHandler 规则处理；同 Route 工厂分发内联执行并恢复外层实例。

```java
builder.handlerContextFactory((domain, connection, metadata, message) -> {
    // identities 是应用管理的线程安全 Connection 到已鉴权身份 Map。
    Identity identity = Objects.requireNonNull(identities.get(connection), "Not authenticated");
    return switch (domain) {
        case ROLE_ID -> new DefaultClientHandlerContext(domain, identity.roleRouteKey(), ROLE_TYPE,
                identity.roleId(), metadata, message, connection);
        case GUILD_ID -> new DefaultClientHandlerContext(domain, identity.guildRouteKey(), GUILD_TYPE,
                identity.guildId(), metadata, message, connection);
        default -> throw new IllegalArgumentException("Unsupported domain: " + domain);
    };
});
// 注解确定 Domain，策略提供路由与身份。
runtime.dispatch(connection, decodedMessage);
```

demo 使用 Connection 的 [GameSession](../../game-demo/src/main/java/cn/managame/demo/network/GameSession.java) 属性而非 Map。[GameRuntimeConfig](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java) 将注解解析的 Domain 传给 [GameDomain.handlerContext](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameDomain.java)，LOGIN（ID 2）仅用请求 userId 作为排队 Key，身份为 0/0，不读取或创建会话；Key 0 在接纳前拒绝。ROLE（ID 1）拒绝缺失会话，并保留业务传入的 Key 与 GameDomain.ROLE_BUSINESS_ID_TYPE/角色值，不将 Key 固定为 roleId。建连不绑定会话，业务只能在 login 内校验 token 成功后通过 context.connection() 手动绑定；无效 token 使未绑定连接继续保持无会话。会话要求非零 Key，并直接保存 long roleId；是否存在会话表示业务是否已绑定，不以某个 ID 值作为标记，ID 范围由业务约束。UserHandler 尚未实现生产鉴权，TCP 探针以仅用于测试的 token 校验验证失败不绑定和成功在 login 内绑定。网络 [GamePacketHandler](../../game-demo/src/main/java/cn/managame/demo/network/GamePacketHandler.java) 只解码并调用双参数入口，鉴权与身份存储清理由应用负责。未配置工厂的 builder 行为不变；采用工厂只改变明确配置后的双参数入口。[HandlerContextFactoryTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerContextFactoryTest.java) 验证两个 Domain 与外部 Map、方法 Domain 覆盖、定制上下文、不调用消息 Key 提取器、Map 删除后保留已捕获输入、接纳前失败、显式重载优先、内联恢复及工厂创建期间关闭。demo TCP/分发测试验证基于属性的策略。

## 7. RouteExecutor

```java
interface RouteExecutor extends AutoCloseable {
    RouteExecuteStatus tryExecute(int domain, long key, Runnable task);
    default void close() {}
}
```

状态为 ACCEPTED、OVERLOADED、CLOSED。

| 创建方式 | 串行策略 | 容量 |
| --- | --- | --- |
| RouteExecutors.platformThreads(workers) | 固定数量平台线程分片 | 每个分片默认 65,536 个等待任务 |
| new StripedRouteExecutor(workers, queueCapacity) | 完整 Route 哈希到单线程分片 | 每分片等待队列；不含正在执行任务 |
| RouteExecutors.virtualThreads() | 每个活跃 Route 一个串行 Mailbox | 默认总计 65,536 个未完成任务，单 Route 最多 1,024 个等待任务 |
| new VirtualThreadRouteExecutor(capacity) | 活跃 Mailbox 由虚拟线程处理 | 所有 Route 合计，包含正在执行任务；单 Route 上限为 min(capacity, 1024) |
| new VirtualThreadRouteExecutor(capacity, routeCapacity, idleTimeout) | 同上 | routeCapacity=1..capacity，单 Route 等待任务上限，不含正在执行的任务 |

参数必须为正数。平台线程方案中不同 Route 可能落到同一分片并互相等待；虚拟线程方案保留空闲 Mailbox 以有界复用，不淘汰活跃工作；默认值与并发机制见 §7.3。

两种执行器 close 都不等待完成，已接受任务继续处理。自定义执行器必须遵守规范的接纳和串行契约；不可用普通多线程线程池直接替代 Route 串行语义。

### 7.1 两种容量的计算示例

假设 StripedRouteExecutor 有 2 个分片、每分片 queueCapacity=100。当分片 A 已有一个执行中任务和 100 个等待任务时，下一项映射到 A 的任务会过载；即使分片 B 空闲，也不借用 B 来打破 A 的排队边界。容量统计不包括正在执行的那个任务。

VirtualThreadRouteExecutor(capacity=100) 则计算所有 Route 的未完成任务。若已有 99 个等待任务加 1 个执行中任务，容量已满；不能按“等待队列只有 99”再接受一个。虚拟线程数量不代表可无限接纳。

单 Route 上限防止一个热点 Route（例如刷包的客户端）占满全局容量：capacity=100、routeCapacity=10 时，某个 Route 已有 10 个等待任务，它的下一项返回 OVERLOADED，其他 Route 仍可接纳。正在执行的任务不计入单 Route 等待数。连接级的速率限制（在解码前丢弃或断开）属于接入层，仍建议应用在网络管线中配置。

同 Route 内联不经过上述容量检查，也不计入新的排队份额。递归触发同 Route Event/call 仍可能消耗调用栈，容量限制不会替业务防止无限递归。

### 7.2 自定义 Executor 的验收边界

扩展 SPI 时至少应验证：完整 Route 的互斥执行、同 Route 入队顺序、拒绝任务从不执行、已接受任务仅执行一次，以及关闭与提交竞争。错误返回后又执行 Runnable 会造成调用方误判；先返回 ACCEPTED 再静默丢弃也不符合契约。

任务包装绑定 Context 的工作由 Runtime 完成，Executor 只承担接纳与调度。Executor 不应自行猜测玩家 ID、协议类型或业务异常含义。调整队列容量或关闭行为属于 Java 实现契约变化，若改变可观察接纳语义还需同步标准 Spec。

<a id="route-mailbox-lifecycle"></a>

### 7.3 虚拟线程 Mailbox 保留与并发

`VirtualThreadRouteExecutor(int capacity)` 和 `RouteExecutors.virtualThreads()` 默认保留空闲 Mailbox 60 秒。`VirtualThreadRouteExecutor(int capacity, Duration idleTimeout)` 可设置其他正数、可用纳秒表示的期限。非正 capacity、零/负期限或期限溢出抛 IllegalArgumentException，null 期限抛 NullPointerException。空闲缓存 maximumSize 等于 capacity，默认 65,536 项。空缓存 Mailbox 不占未完成任务容量。Caffeine 异步维护淘汰，因此该上限是配置目标，不是同步的严格内存界限；任务容量也不限制所有保留队列数组的字节数。

活跃 Mailbox 由以完整 Domain/Key 为键的 ConcurrentHashMap 持有。短 compute/computeIfPresent 操作按 Key 原子协调队列修改、消费者启动和活跃/空闲交接，业务动作在这些操作外执行。执行器不再使用全局 synchronized 监视器。ConcurrentHashMap 内部仍可能同步哈希碰撞桶，不承诺无锁。每次入队/取队都受相应原子 Map 操作保护，因此可以使用 ArrayDeque。

消费者完成最后一个动作并发现无排队任务时，在同一 Key 操作中先把空 Mailbox 交给 Caffeine，再移除活跃映射。下次激活原子取走缓存 Mailbox，或创建新 Mailbox，并启动唯一虚拟线程消费者。任务异常记录日志，finally 释放容量并继续排空。消费者启动失败会移除新任务、回滚容量预留，然后传播失败。

Caffeine 从 game-core 传递引入，依赖及版本归属见 [Core Java 规范](OGBS-Core-Java-25-Specification-1.0.zh-CN.md#1-模块与职责)。私有缓存使用 maximumSize(capacity)、expireAfterWrite(idleTimeout) 和单调经过时间，采用 Caffeine 默认被动维护，不配置 Scheduler。每次重新进入空闲都重置期限。即使尚未物理回收，激活时也不能取到已过期 Mailbox。后续缓存写入及部分读取触发维护，使用 Caffeine 默认执行器；Runtime 不增加过期定时器或清理线程，维护不执行业务动作。没有后续缓存访问时，过期条目可能继续占用内存，直到后续维护或关闭，不承诺释放截止时间。例如停止流量后 Mailbox 过期，下一次向该 Route 提交会创建新 Mailbox，而不会复用过期实例。到期/容量淘汰只丧失复用机会，不丢失活跃任务。GameTime 调整不影响空闲时间。

一个 AtomicLong 同时保存关闭位和全部任务预留，包括等待 Key 协调或消费者启动的提交、排队及执行中任务。容量 CAS 与关闭是原子的：关闭前预留成功的提交可完成接纳并执行，后续提交返回 CLOSED。close 不等待业务完成，会清空空闲缓存，确保晚到空闲写入不会在关闭后保留，并让已接纳活跃工作排空，最终空 Mailbox 不再缓存。

已确认取舍是保留有界空闲缓存，避免短请求波次间反复创建队列，并把活跃队列单独持有，防止缓存策略破坏 RT-ROUTE-02/03。独立空闲条数配置需有实际需求再增加；生产吞吐和 GC 容量尚未压测。源码：[VirtualThreadRouteExecutor](../../game-runtime/src/main/java/cn/managame/runtime/executor/VirtualThreadRouteExecutor.java)。验证：[VirtualThreadRouteExecutorTest](../../game-runtime/src/test/java/cn/managame/runtime/executor/VirtualThreadRouteExecutorTest.java) 覆盖期限重置、后续提交不复用过期 Mailbox、长任务、容量淘汰、异常恢复、过期/提交并发、原子容量及关闭/排空；[RouteExecutorTest](../../game-runtime/src/test/java/cn/managame/runtime/executor/RouteExecutorTest.java) 覆盖执行器公共契约。


## 8. 跨 Route 调用与回调

只能从当前 Runtime 的业务执行上下文内调用：

```java
runtime.call(2, guildId,
    () -> loadGuildSnapshot(guildId),
    new RouteCallback<GuildSnapshot>() {
        public void onSuccess(GuildSnapshot snapshot) {
            // 已回到源 Route，Contexts.current() 是原始源上下文。
        }
        public void onFail(int errorCode) {
            // 框架错误；业务失败建议包含在结果对象中。
        }
    });
```

目标 action 运行在 DefaultRouteCallContext 下。它继承源 InvocationContext 的业务身份和 Metadata，但不继承自定义 Context 的额外字段。

无当前上下文或当前上下文属于其他 Runtime 时抛 IllegalStateException。目标校验或接纳失败走 onFail；action 抛异常时报告 ROUTE_CALL_EXECUTION_ERROR（3008）并尝试 onFail。回调异常只报告 RUNTIME_EXECUTION_ERROR（3009）。

源 Route 的回调提交失败会报告 ROUTE_CALLBACK_DISPATCH_FAILED（3010），此时回调不会在其他线程或 Route 上兜底执行。同 Route 调用可能在 call 返回前完成；调用方不得假定始终异步。此 API 没有 Future、超时参数或取消句柄。

## 9. EventBus

```java
interface Event {
    int routeDomain();
    long routeKey();
}
interface EventBus { void publish(Event event); }

@EventMethod(order = 10)
public void onChanged(MyEvent event) { /* Contexts.current() 获取上下文 */ }
```

使用 builder.eventHandlers 显式注册对象。`@EventHandler` 是可用的标记注解，当前构建器不强制要求它。监听方法只接收一个 Event 子类型参数，不接收额外 Context 参数。

按事件精确 Class 匹配，order 越小越先执行，相同值顺序不保证。监听器失败报告 3009 后继续下一个。无监听器也会校验目标 Route，并受接纳限制。

事件使用 DefaultEventContext。同一 Runtime 的 InvocationContext 发布时继承身份和 Metadata；其他来源使用身份 0 与空 Metadata。同 Route 发布可内联，publish 返回不等于所有跨 Route 监听器已完成。

<a id="static-event-publication"></a>

### 9.1 静态事件发布

`cn.managame.runtime.event.Events` 提供 `static void publish(Event)`、`static void bind(GameRuntime)`、`static boolean unbind(GameRuntime)`。业务直接调用 `Events.publish(event)`，无需获取 Runtime 或 EventBus。既有实例入口 `runtime.eventBus().publish(event)` 保留，用于显式的多实例集成。

Events 先从内部 ScopedValue 绑定读取所属 Runtime，没有所属实例时才读取 AtomicReference 保存的进程默认实例。只选择一次，再委托该 Runtime 的 EventBus。无所属/默认实例时抛 IllegalStateException；事件或绑定 Runtime 为 null 时抛 NullPointerException。既有 RuntimeDispatchException 拒绝码原样传播。当前所属 Runtime 已关闭时，即使另有开放的默认实例，也报告 RUNTIME_CLOSED。选择后的绑定变化不会向其他 Runtime 重试。同 Route 内联、EventContext 身份/Metadata 继承、Context 恢复、监听器顺序及异常隔离仍遵循 RT-EVENT-01–05。

启动在 Runtime 构建成功且仍运行时显式调用 `Events.bind(runtime)`，之后才允许外部发布；build 不自动选择进程默认实例。bind 按实例身份比较：重复绑定同一对象无操作；不同对象冲突时抛 IllegalStateException，不替换原绑定。unbind 仅实际移除指定对象的绑定时返回 true，否则返回 false。绑定借用 Runtime，不启动/关闭资源。内置 Runtime.close 原子标记关闭，并在关闭定时器/执行器前条件解除自身默认绑定，不能清除其他 Runtime 的绑定。自定义 GameRuntime 实现必须在自身关闭生命周期安排 `Events.unbind(this)`。

应用串行协调启动绑定与关闭，不得绑定已关闭的 Runtime；bind 自身不检查生命周期，也不重新开启实例。显式解绑/关闭不代表已经等待所有已选定实例的发布完成。与普通实例发布相同，Runtime 关闭可能拒绝尚未接纳的提交，已接纳工作按 RT-CLOSE-02 执行。无 Runtime 上下文时，默认绑定移除后发布抛 IllegalStateException；已接纳且仍在已关闭 Runtime 中执行的工作保留所属实例，发布报告 RUNTIME_CLOSED。

```java
// 仅用于启动；Spring/应用生命周期持有并关闭 Runtime。
Events.bind(runtime);
// 业务发布，也可从外部线程调用：
Events.publish(new ChangedEvent(1, 42));
// 显式移除默认绑定，不关闭 Runtime：
Events.unbind(runtime);
```

[demo Runtime 配置](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java) 绑定构建的实例；绑定失败时关闭它，再将其作为可关闭的 Spring Bean 暴露。业务监听器只需 @EventHandler，因为应用提供了注解扫描过滤器；Runtime 注解不因此依赖 Spring。源码：[Events](../../game-runtime/src/main/java/cn/managame/runtime/event/Events.java)、[RuntimeContexts](../../game-runtime/src/main/java/cn/managame/runtime/internal/RuntimeContexts.java) 及 [DefaultGameRuntime](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java)。验证：[EventsTest](../../game-runtime/src/test/java/cn/managame/runtime/event/EventsTest.java) 覆盖无实例/默认/所属选择、身份继承和内联 Context 恢复、冲突、旧实例关闭、已关闭所属实例拒绝，以及发布过程中的绑定变化；[DemoEventTest](../../game-demo/src/test/java/cn/managame/demo/DemoEventTest.java) 验证仅标记事件注解的 Spring 发现、静态发布及 Context 关闭后解绑。

## 10. GameTime、Timer 与 Cron

<a id="demo-task-integration"></a>

普通 Spring 的 [demo 任务接入](../../game-demo/README.zh-CN.md#demo-runtime-services) 通过 CronMethodFilter 发现带 @Cron 方法的类，再由 CronBeans 收集带注解的单例 Bean，通过 cronHandlers 注册并明确选择 UTC。包括已知 @Bean 产物类型和继承的 public 方法，无固定任务类型列表。扫描不提前创建无关 Bean，按对象引用去重，拒绝 prototype 或可识别的 Spring AOP 代理目标；类型未知的 FactoryBean 产物需提供可识别类型。发现属于应用启动行为，在 Runtime 构建时冻结，不向 Runtime 引入 Spring 依赖。onCron 使用 `*/10 * * * * ?`，目标为 Domain 3/Key 1。Spring TimerRef Bean 默认延迟 3000 毫秒安排 tasks::onTimer，并在销毁时取消；仅标记 HttpHandler 的扫描通过 httpHandlers 注册返回业务对象的接口，main 监听器接到 HttpServer.asyncHandler(runtime.http()::dispatch)，默认端口 8080。这些是应用默认值，不增加 Runtime 默认值或公共 API，不引入 Spring 调度器或额外业务执行器。[DemoServicesTest](../../game-demo/src/test/java/cn/managame/demo/DemoServicesTest.java) 验证真实 HTTP/1.1、定时和 cron 触发、路由/线程上下文、取消及关闭。

### 10.1 GameTime

```java
import cn.managame.runtime.time.GameTime;

long nowMillis = GameTime.currentTimeMillis();
LocalDateTime localNow = GameTime.now(ZoneId.of("Asia/Shanghai"));
GameTime.setClock(Clock.fixed(Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC));
GameTime.resetClock();
```

默认 Clock.systemUTC()，Clock 引用使用 volatile，作用域是整个进程，不属于某个 Runtime。now 使用显式 ZoneId；setClock 和 now 均拒绝 null。测试结束后应 resetClock，避免影响其他 Runtime。

setClock/resetClock 只替换业务墙钟，不通知调度器、不自动重排现存 Timer/Cron，也不会自动补跑。网络 timeout 与耗时统计仍使用单调时间。动态业务 Timer 由业务持有 TimerRef，cancel 后按新的 GameTime 重新计算 Duration 并 schedule。

### 10.2 一次性 Timer

```java
TimerRef RuntimeTimer.schedule(
    int domain, long key, Duration delay, Runnable task);
boolean TimerRef.cancel();

@Cron(value = "0 */5 * * * ?", domain = 1, routeKey = 42)
public void refresh() { /* ... */ }
```

schedule 在调用时校验 Runtime 和 Route；delay 允许零，不允许负值。每个 Runtime 有一个守护调度线程，到期后将业务任务提交到目标 Route。任务运行于 DefaultTimerContext。

cancel 仅在取得触发资格之前成功；重复取消或已经触发时返回 false，不能撤销已排队的业务任务。过载或执行异常通过错误处理器报告；Runtime 关闭期间停止调度，不提供每个计时器的取消通知。

### 10.3 Cron 表达式

Cron 方法必须 public、实例 void、无参数。通过 cronHandlers 注册对象。cronZone 默认 UTC，可显式设为 `ZoneId.of("Asia/Shanghai")`。

当前表达式支持：

| 项目 | 范围/约定 |
| --- | --- |
| 字段 | 秒、分、时、日、月、星期，共六项 |
| 数字范围 | 秒/分 0–59，时 0–23，日 1–31，月 1–12，星期 1–7 |
| 星期 | 1 为星期日 |
| 语法 | 数字、*、逗号列表、范围、步长；? 仅限日/星期 |
| 日和星期 | 都有限制时取 AND |
| 不支持 | 名称、L、W、#、年份字段、完整 Quartz 扩展 |
| 搜索窗口 | 查找未来八年内的下一次触发；无结果时拒绝 |

例如 `0 */5 * * * ?` 表示每五分钟的第 0 秒触发。Cron 通过同一套一次性 RuntimeTimer 调度，在本轮业务方法结束后读取当前 GameTime，重新计算未来的一次触发；任务异常不重试，本次 Route 接纳失败后仍安排下一周期。不补发所有错过的时刻，不适合充当实时游戏 Tick。

### 10.4 Cron 取消与重排

```java
interface CronScheduler {
    boolean cancel(Class<?> type, String methodName);
    boolean reschedule(Class<?> type, String methodName);
    void rescheduleAll();
}

GameTime.setClock(testClock);
runtime.cron().rescheduleAll();
runtime.cron().cancel(SystemCron.class, "dailyReset");
runtime.cron().reschedule(SystemCron.class, "dailyReset");
```

SystemCron/testClock 是应用定义的类型/变量。Key 使用反射方法的 **DeclaringClass + methodName**；继承父类未覆盖的方法时，使用父类 Class。重复注册相同 Key 在 build 时失败，即使来自不同实例。

- cancel：活动项返回 true，取消后不再继续下一周期；未知或已取消项返回 false。已经被 Cron 认领执行的业务方法允许完成。
- reschedule：取消旧 TimerRef，按当前 GameTime 重新计算；可重新启用已取消项。未知 Key 返回 false。
- rescheduleAll：逐项重排所有注册项，包含此前取消的项，不是跨项原子操作。
- 关闭后 cancel 返回 false；reschedule/rescheduleAll 抛携带 RUNTIME_CLOSED 的 RuntimeDispatchException。
- 新旧调度通过代次隔离，旧回调不能覆盖新调度。已经提交到 Route 但尚未被 Cron 认领的旧代任务会跳过业务方法。
- 取消/重排不打断已开始的方法；重排时若旧方法仍在执行，新任务继续遵守同 Route 串行约定。

Cron 的取消包含停止后续周期，区别于只取消一次性触发的 TimerRef。显式重排可能再次遇到同一个日历时刻；不提供按日历时刻去重或业务幂等保证。

### 10.5 Timer 与 Cron 的内部协作

RuntimeTimers 使用单个 ScheduledThreadPoolExecutor 负责到期信号，业务任务仍交给 RouteExecutor。每个一次性任务有一个触发/取消竞争标记；到期方成功认领后才提交 Route。remove-on-cancel 让取消的调度项可从调度队列移除，关闭后不继续执行尚未到期的延迟项。

Cron 在这一层之上维护注册项及调度代次。Timer 到期并不立即授权 Cron 方法执行：任务进入 Route 后还要确认自己仍属于当前有效代次。重排可以使已入队但尚未被 Cron 认领的旧代动作失效；已执行的旧代方法完成后也不能重新挂上旧周期。

这两个检查点用途不同：

```text
Timer 触发资格 → 尝试提交 Route → Cron 代次/执行资格检查 → 执行业务方法
```

普通一次性 Timer 没有 Cron 代次机制，不能把 Cron 的旧代跳过语义套到 TimerRef.cancel。Cron 的下一周期是在当前方法结束后重新计算；业务方法耗时会影响下一次可安排时刻，不积压一串历史触发。


## 11. 错误与关闭

```java
interface RuntimeErrorHandler { void onError(RuntimeError error); }

record RuntimeError(int errorCode, Context context,
                    int routeDomain, long routeKey, Throwable cause) {}

int RuntimeDispatchException.errorCode();
```

Runtime 错误编号见 [OGBS Core](OGBS-Core-1.0.zh-CN.md)。当前实现报告的 RuntimeError 包含发生错误的 Context 和 Route。错误处理器应快速返回，避免再次抛异常；其异常会由实现记录。

close 幂等：停止调度器，按对象身份对 Executor 去重并分别关闭，不等待队列排空。关闭后新 dispatch、publish、schedule 被拒绝；已执行源任务中的 call 可以通过 onFail 得到 RUNTIME_CLOSED，但回源提交仍可能受执行器关闭影响。协议与 Key 注册表仍可只读查询。

### 11.1 API 结果与异步错误的区别

| 入口/阶段 | 调用者直接观察 | 错误处理器观察 |
| --- | --- | --- |
| dispatch / publish 前置校验或拒绝 | RuntimeDispatchException | 不把拒绝伪装成业务执行成功 |
| Handler / Event 方法执行异常 | 不由 API 自动构造业务响应 | RUNTIME_EXECUTION_ERROR |
| call 无合法源上下文 | IllegalStateException | 不发起目标调用 |
| call 目标拒绝 | 尝试 onFail(errorCode) | 回源失败时另报 3010 |
| call action 抛异常 | 尝试 onFail(3008) | ROUTE_CALL_EXECUTION_ERROR |
| call 回调抛异常 | 不产生第二次回调 | RUNTIME_EXECUTION_ERROR |
| Timer 到期后 Route 拒绝 | 无同步调用栈可返回 | 通过调度错误路径报告；关闭停止调度 |
| close 后查询注册表 | 保持只读可用 | 不重新开启执行 |

错误处理器不保证固定在一个专用错误线程运行，它随触发路径被调用；应快速返回，避免访问不属于当前 Route 的可变状态。需要外部告警时自行做好异步交付边界。

### 11.2 后续维护检查点

新增一种执行入口时，应明确目标 Route、Context 类型、身份继承、接纳失败如何表达、执行异常如何报告、关闭时如何处理。新增 Context 字段时，应明确是原实例保留还是跨 Context 复制，不能默认扩大身份传播。

这里的 MethodHandle、ScopedValue、平台/虚拟线程属于 Java 实现机制。只要保持标准行为，其他语言可以使用不同机制；Java 内部算法优化也不需要重写 Route 的业务定义。


## 12. 源码与测试

- [GameTime](../../game-runtime/src/main/java/cn/managame/runtime/time/GameTime.java)
- [CronScheduler](../../game-runtime/src/main/java/cn/managame/runtime/timer/CronScheduler.java)
- [GameTimeTest](../../game-runtime/src/test/java/cn/managame/runtime/time/GameTimeTest.java)
- [CronSchedulerTest](../../game-runtime/src/test/java/cn/managame/runtime/timer/CronSchedulerTest.java)

- [GameRuntime / 主 API](../../game-runtime/src/main/java/cn/managame/runtime/GameRuntime.java)
- [GameRuntimeBuilder / 注册校验](../../game-runtime/src/main/java/cn/managame/runtime/GameRuntimeBuilder.java)
- [DefaultGameRuntime / 执行与关闭](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java)
- [Contexts / ScopedValue](../../game-runtime/src/main/java/cn/managame/runtime/context/Contexts.java)
- [RuntimeTest](../../game-runtime/src/test/java/cn/managame/runtime/RuntimeTest.java)
- [RouteExecutorTest](../../game-runtime/src/test/java/cn/managame/runtime/executor/RouteExecutorTest.java)

在仓库根目录执行 `mvn -pl game-runtime -am test` 验证 Runtime 与依赖模块；完整验证执行 `mvn verify`。


### 12.1 易错契约的测试定位

下表提供现有测试方法入口，便于修改相关规则时定位回归检查；不表示已穷举所有线程交错。

| 契约 | 测试类与方法 |
| --- | --- |
| 同 Route 内联、事件失败隔离、外层 Context 恢复 | RuntimeTest.sameRouteInlineEventsAreOrderedAndIsolated |
| 多 Runtime 即便 Route 数值相同也不能内联 | RuntimeTest.separateRuntimesNeverInlineOnEqualRoute |
| 跨 Route 计算及原始源 Context 恢复 | RuntimeTest.crossRouteSuccessFailureAndSourceRestoration |
| 目标拒绝与回源拒绝分别处理 | RuntimeTest.rejectedTargetFailsOnSourceAndRejectedReturnOnlyReports |
| Timer 新 Context 与取消边界 | RuntimeTest.timerHasFreshContextAndCancellationIsBeforeSubmissionOnly |
| 改钟不自动重排 | CronSchedulerTest.changingGameClockNeedsExplicitRescheduleAndDoesNotChangeDynamicTimer |
| 排队旧代失效、运行旧代不能覆盖新调度 | CronSchedulerTest.cancellationAndRescheduleInvalidateAlreadyQueuedGenerations / rescheduleDuringExecutionCannotBeUndoneByOldCompletion |
| 共享 Executor 只关闭一次、错误回调隔离 | RuntimeTest.sharedExecutorClosesOnceAndErrorHandlerCannotEscape |
<a id="runtime-http-api"></a>

## 13. HTTP 注解与 Route 接入

本绑定在 game-runtime 内实现 [RT-HTTP-01–08](OGBS-Runtime-1.0.zh-CN.md#runtime-http-profile)。Network 的 [HttpResponseCallback](OGBS-Network-Java-25-Specification-1.0.zh-CN.md#http-async-response) 是适配器的传输边界，业务通过 HttpResultCallback 提交普通对象。不使用 CompletionStage，不另建业务执行器。

### 13.1 注册、Context 与公开类型

`GameRuntime.http()` 返回冻结的 HttpDispatcher，其 `void dispatch(FullHttpRequest, cn.managame.network.http.HttpResponseCallback)` 借用请求，通过 `HttpServerBuilder.asyncHandler(runtime.http()::dispatch)` 接入。业务对象不实现这个传输回调。Builder 的 `httpHandlers(Iterable<?>)` 复制/替换注册，默认空；`httpContextFactory(HttpContextFactory)` 拒绝 null。未配置 Key 规则、或要求与 DefaultHttpContext 不兼容的自定义 Context 的入口必须有工厂，其他入口可使用默认 Context。空注册在未关闭时返回 404，无需工厂。

`@HttpHandler` 是可继承的运行期类型注解，提供 `int domain() default 0`；`@HttpMethod` 是运行期方法注解，提供必填 `String value()`（原始路径）、`HttpRequestMethod method() default HttpRequestMethod.POST`、`int domain() default 0`。`HttpRequestMethod` 是 cn.managame.runtime.http 的公开枚举，包含 GET、POST、PUT、PATCH、DELETE、HEAD、OPTIONS、TRACE。`@HttpMethod("/echo")` 使用默认 POST，`@HttpMethod(value="/lookup", method=HttpRequestMethod.GET)` 显式选择 GET。注解不接受字符串字面量或自定义 token。RuntimeCompiler 将枚举 name() 冻结为精确方法 token；入口匹配不将入站方法转换成枚举，因此已注册 path 上的未知方法仍返回 405 和排序的 Allow。两者 routeKey/routeKeyMethod 默认和覆盖规则见 §13.4。非零方法 Domain 覆盖类 Domain，最终 Domain 必须已注册，每个传入目标必须有 @HttpHandler。方法必须 public、实例方法、非 varargs，返回 void 或业务结果的引用类型。基本类型返回声明、实现 Netty HttpObject 的类型（包括 FullHttpResponse）在构建时拒绝；包装数字、record、POJO、map、list、string 都是普通结果对象。参数仍为零到两个，至多一个输入（FullHttpRequest、String 或 HttpRequestCodec 解码的具体引用类型）和一个兼容 Context/HttpContext 或自定义 HttpContext 子类型，顺序不限。Context 可省略，执行期间用 Contexts.current(HttpContext.class) 获取。HttpContext 不属于 InvocationContext，因此拒绝 InvocationContext 参数。请求绑定见 §13.5；不自动展开 future。错误签名、不兼容默认 Context、缺失必需工厂、重复入口或未知 Domain 均在执行器所有权转移前构建失败。Handler/Event/Cron 仍要求 void。

路径以 `/` 开头，不含 query/fragment/空格/控制字符，原始文本大小写敏感精确匹配；方法来自 HttpRequestMethod；CONNECT 不是枚举成员，不能注册。匹配只移除 query，不解码：`/player?id=42` 匹配 `/player`，`/player/`、`/%70layer` 不匹配。不自动提供 HEAD→GET、OPTIONS、classpath 扫描或流式方法 API。

`HttpContext extends Context` 在 routeDomain()/routeKey() 上增加 `FullHttpRequest request()` 和 **`HttpResultCallback responseCallback()`**，不定义 businessIdType()、businessId() 或 metadata()。可扩展的 `DefaultHttpContext extends DefaultContext` 仅有 `(int domain, long key, FullHttpRequest, HttpResultCallback)` 构造器，请求/回调不得为 null。应用可在自定义 HttpContext 子类型增加会话/鉴权字段，Runtime 不推导或复制这些字段到 EventContext/RouteCallContext。普通 HTTP 来源的目标 Context 使用身份 0/0 和空 Metadata；回调恢复同一原始 HTTP 实例。这沿用已有基础 Context 规则，不增加 HTTP 专属身份传播。

函数式 `HttpContextFactory.create(int domain, long routeKey, FullHttpRequest request, HttpResultCallback callback)` 在接入线程、Route 接纳前运行，不绑定新 Context。保留选定 Domain/Key、原样请求/结果回调，返回兼容 Context。routeKey 为零（无注解规则）时由工厂确定非零 Key。回调是 Runtime 的完成门控；不得释放借用请求，额外 retain 引用属于应用。工厂可 `callback.onResponse(401, errorObject)` 后返回 null，跳过业务。不隐式信任身份、不鉴权 header、不查询 RouteKeyRegistry、不继承提交者 Context。

### 13.2 对象完成、编码、错误与所有权

`HttpResultCallback` 提供 `boolean onResponse(Object result)`（默认状态 200）、`boolean onResponse(int statusCode, Object result)`、`boolean onFail(Throwable cause)`。状态码必须 200..599；非法状态抛 IllegalArgumentException，null 失败原因抛 NullPointerException，均不抢占完成。null 结果有效，默认编码为 JSON `null`。void 返回不自动回复，方法需立即或稍后显式完成；对象返回自动完成，包含 null。不增加 Runtime 截止时间或隐式重试。

Builder 的 `httpResultCodec(HttpResultCodec)` 选择非 null codec。默认 `HttpResultCodec.json()` 使用 ObjectMapper 默认配置创建独立 ObjectWriter：UTF-8 JSON、`application/json; charset=UTF-8`，不自动发现模块。支持的 bean/record/collection 遵循 Jackson 配置，不承诺任意对象都可序列化；不支持的属性、getter 异常或编码失败作为服务端失败完成。自定义 codec 可使用应用序列化器/模块，函数式 `byte[] encode(Object result) throws Exception` 返回独立所有权的非 null byte[]，`String contentType()` 默认 JSON。构建在启动资源前捕获并用原生 header 校验验证非空白 Content-Type。codec 实例被保留、不深拷贝；encode 必须能跨 Route/回调线程并发，传出的数组不得再修改。codec 签名不包含 ObjectMapper/Netty/HTTP 版本类型。

原子门控先抢占完成再编码。仅获胜结果在调用线程同步编码；失败竞争对象忽略、不编码、不转移资源。调用方仍拥有普通对象，编码期间必须稳定；外部回调使用不可变 DTO/快照，不使用可变 Route 状态。编码器不保留对象以便返回后延迟序列化。方法自动结果在同一 Route 任务内编码，不增加队列/执行器。适配器从字节与媒体类型创建自有传输响应；实际 framing/version/HEAD/204/304 规范化及发送由 Network 负责。数字状态与对象结果不携带协议版本。Runtime 当前仅服务 HTTP/1.1，其他传输 Profile 尚未实现。

编码异常或 null 编码字节报告 RUNTIME_EXECUTION_ERROR，并且只调用一次 Network 失败回调，当前 Profile 尝试 500/关闭。异步编码失败即使方法已返回，也报告原始 HTTP Context；兼容 Context 建立前的工厂完成使用所选 Domain/Key 0 诊断。通过 Object 动态返回传输对象、或提交 Netty HttpObject/ReferenceCounted 值也会失败完成；不支持的引用计数参数不被消费，仍由调用方负责。业务回调不接收/消费 FullHttpResponse/ByteBuf。完成布尔值反映首次传输完成接纳，不代表序列化成功、对端收到或持久化。断连后尚未完成的获胜回调可能先编码，再由传输层拒绝/丢弃字节；重复结果不编码。

| 边界 | HTTP 结果 | Runtime/业务行为 |
| --- | --- | --- |
| Runtime 已关闭 | 匹配前 503 | 方法不执行 |
| 非 origin-form 或 fragment | 400 | 方法不执行 |
| 缺失路径/错误方法 | 404 / 405，排序后的 Allow | 工厂/方法不执行 |
| 提取/工厂输入非法或零 Key | 400 | 方法不执行；非法提取跳过工厂 |
| 工厂显式完成 | 编码对象响应或失败 | 方法不执行 |
| 不兼容工厂结果或其他提取/工厂异常 | onFail，Network 500/关闭 | RUNTIME_EXECUTION_ERROR，所选 Domain/Key 0 |
| 过载/执行器关闭或提交与关闭竞争 | 503 | 方法不执行；释放 retain 请求 |
| 方法异常 | 未完成时 onFail | RUNTIME_EXECUTION_ERROR，不重试 |
| 获胜结果编码失败 | Network 失败回调 | RUNTIME_EXECUTION_ERROR，不重试/回滚 |
| 对象返回 null | 默认 200、JSON null | 正常完成 |
| void 返回 | 等待显式完成/关闭 | 不自动生成结果 |

dispatcher 参数 null 时在 retain/完成前抛异常。RuntimeErrorHandler 异常沿用隔离日志。业务失败可返回应用 DTO/状态，不必作为框架异常。Network 传输规范化与 codec 分离，错误响应不暴露异常详情。

Runtime 在 Route 提交前 retain 请求，业务调用及自动结果编码结束后释放，拒绝则立即释放。使用 Handler/Event/call 相同的 Route 执行器/Context 路径，同 Domain/Key 串行、嵌套可内联。后续 call 回调恢复原始 HttpContext，不重新获取请求引用；返回前复制请求数据，或在每条异步/接纳失败路径管理独立 retain 引用。断连不取消已接纳业务。Runtime.close 拒绝新入口、由执行器排空已接纳工作，不关闭 HttpServer、不等待延迟完成。回调接纳失败可能让响应无法结束；停机前安排在途完成。

### 13.3 示例、兼容性与验证

[RuntimeHttpExample](../../game-demo/src/main/java/cn/managame/demo/examples/runtime/RuntimeHttpExample.java) 在 POST `/echo` 返回应用 EchoResult record，在 GET `/lookup` 的跨 Route 回调提交 PlayerResult。POST /echo 省略 method，验证 POST 默认值；GET /lookup 显式选择 HttpRequestMethod.GET。POST body 字段 playerId 是类 Key 默认，GET query 字段 lookupId 覆盖它。示例无需工厂，使用默认 HttpContext 并读取 routeKey()；这些 Key 仅作路由输入，不证明已认证身份。业务方法不构造 FullHttpResponse 或 HTTP 版本。[执行测试](../../game-demo/src/test/java/cn/managame/demo/examples/runtime/RuntimeHttpExampleTest.java) 通过真实 HttpServer 验证 UTF-8 DTO 结果。

源码：[公开 HTTP 包](../../game-runtime/src/main/java/cn/managame/runtime/http)、[RuntimeCompiler](../../game-runtime/src/main/java/cn/managame/runtime/internal/RuntimeCompiler.java)、[RuntimeHttp](../../game-runtime/src/main/java/cn/managame/runtime/internal/RuntimeHttp.java)。测试：[RuntimeHttpTest](../../game-runtime/src/test/java/cn/managame/runtime/http/RuntimeHttpTest.java)、[HttpRouteKeyTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpRouteKeyTest.java)、[HttpResultTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpResultTest.java)，覆盖默认 POST 与显式枚举方法、精确方法匹配及 405/Allow（含未知 token）、注册（含拒绝 InvocationContext 参数）、工厂、HTTP 发起 Event/call 的默认身份、自定义 HTTP Context 原实例恢复、共用 Route 顺序、query/body 规则、所有权、延迟完成、DTO/null/自定义 codec、完成竞争及编码失败。依赖变更需根 `mvn clean verify`，定向验证用 `mvn -pl game-runtime -am test`。已有 RPC 测试/示例引用已移除 API，当前阻塞根验证；HTTP 示例单独编译执行。生产容量及所有断连竞争未验证，其他 HTTP 版本尚未实现。

Runtime 依赖 game-core、game-network、Jackson Databind，并从 Core 传递引入共享 Caffeine，不新增 artifact/RPC 依赖；自定义 GameRuntime 实现需实现 http()。未注册 HTTP 的既有 Builder 可继续使用。HttpMethod.method 从默认 GET 的 String 改为默认 POST 的 HttpRequestMethod，Handler 源码需要迁移并重新编译，既有编译注解不属于兼容绑定。将 `method="POST"` 改为 `method=HttpRequestMethod.POST` 或省略；原来隐式 GET 的入口均需显式 `method=HttpRequestMethod.GET`。默认 POST 按 JSON body 字段选择 Key，不继承 GET query 行为或安装 GET 别名。HttpContextFactory 现在第二参数为选定 long routeKey、第四参数为 HttpResultCallback；原三参数/网络回调工厂需迁移。移除 DefaultHttpContext 的身份/Metadata 构造参数及 HTTP businessIdType()/businessId()/metadata() 访问，改用 Route 访问器或应用明确定义的 Context 字段。接收 InvocationContext 的 HTTP 方法改为 Context/HttpContext 或自定义 HttpContext 子类型。普通 InvocationContext 传播规则不变。原 FullHttpResponse 返回方法改为 DTO/void，回调响应改为业务对象。该显式契约变化使响应值独立于 HTTP 版本，不保留不兼容的原生响应 API。

### 13.4 RouteKey 字段与方法规则

@HttpHandler 和 @HttpMethod 都增加 `String routeKey() default ""` 与 `String routeKeyMethod() default ""`。方法级非空配置整体替换类规则，字段与方法之间切换也如此；两者都空则继承。任一层不得同时配置非空字段和方法，纯空白值在构建时拒绝。不隐式推导字段或 Java getter 名。两层均无规则时由工厂提供 Key；有规则则先提取，可选工厂必须保留该 Key。不提供工厂时 DefaultHttpContext 提供 Route、请求及结果回调，不含身份/Metadata。自定义 HttpContext 子类型仍需由兼容工厂提供。

`routeKey = "playerId"` 在 GET 中通过 Netty QueryStringDecoder 按 UTF-8 解码 query，要求大小写敏感的精确字段名只有一个值；解码后重复也拒绝。值必须是可选负号加 ASCII 数字、可解析为非零 long；加号、空白、小数、指数、溢出拒绝。其他方法读取 ByteBuf 可读区域中一个严格 UTF-8 JSON 对象的顶层属性，不回退 query。接受整数 number token 或十进制整数字符串。浮点/指数 token、null/布尔/数组/对象字段、缺失字段、任意位置的重复 JSON 属性、非法 JSON、额外尾部值、非法 UTF-8、溢出和零都返回 400。属性名按字面匹配，点号不是嵌套路径表达式。Content-Type 不选择来源或解析器，配置字段的非 GET 请求不论该 header 都期待 JSON；应用在 pipeline 决定媒体策略。

包级 HttpRouteKey 使用 Jackson Core 流式 JsonFactory，启用严格重复检测并禁用字符集探测（UTF-8）。完整校验对象后才接纳 Key，在 duplicate() 上使用不释放引用的 ByteBufInputStream，不改变原始 indices/refCnt，不反序列化业务 DTO。Jackson 2.21.3 默认嵌套深度 1000、number 长度 1000、string 长度 20,000,000、字段名长度 50,000，不额外设置总文档/token 限制。约束违反返回 400，Network 仍限制完整 body 与 URI；query 解码在 Network URI 限制内不额外截断参数数量。提取不新增 Runtime 业务线程或第二个队列。

`routeKeyMethod = "playerKey"` 在注册的 Handler 对象上精确绑定 public 实例方法 `long playerKey(FullHttpRequest request)` 或返回 Long 的对应方法。构建时一次编译 MethodHandle；缺失/private/static、错误参数/返回类型或 varargs 在构建时拒绝。不推导无参 getter 或任意反射表达式。方法在接入线程、工厂/接纳前运行，借用请求，不得访问 Route 所有的状态，可被并发调用，实例字段不因此串行。IllegalArgumentException 映射 400，其他异常或 null Long 报告 RUNTIME_EXECUTION_ERROR 并 onFail；返回零映射 400。后续需要请求数据时自行获取独立所有权。

例如 `@HttpHandler(domain=1, routeKey="playerId")` 配合 `@HttpMethod(value="/guild", routeKey="guildId")` 使用默认 POST 从 body 选择 guildId；显式 method=HttpRequestMethod.GET 时从 query 选择 guildId。自定义 `routeKeyMethod="playerKey"` 替换类字段规则并调用声明的提取方法。失败不尝试其他规则，也不允许工厂修改选定 Key。[HttpRouteKeyTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpRouteKeyTest.java) 覆盖两个覆盖方向、来源隔离、完整 JSON 校验、非法 UTF-8、精确 64 位边界、buffer indices/refCnt 保留、工厂 Key 保留、提取/注册失败。更新后的可运行 [RuntimeHttpExample](../../game-demo/src/main/java/cn/managame/demo/examples/runtime/RuntimeHttpExample.java) 通过真实 HttpServer 演示 body/query 提取。

### 13.5 请求绑定

Builder `httpRequestCodec(HttpRequestCodec)` 替换默认 codec，拒绝 null。函数方法 `Function<FullHttpRequest,Object> decoder(Type type)` 在构建时按每个输入方法调用一次，传入完整声明泛型 Type。返回的解码器必须线程安全，在接入线程、Key/Context 选择之后、接纳之前运行。配置非法在执行器所有权转移前失败。解码 IllegalArgumentException 返回 400，不执行业务；其他异常走服务端失败路径。

默认使用 `HttpRequestCodec.json()`。String 读取可读 UTF-8 body；其他引用类型使用按方法编译的 Jackson ObjectReader。GET 将解码 query 转成对象：单值为字符串，重复值为字符串数组，采用 Jackson 默认标量转换。非 GET 读取 JSON body 并拒绝额外尾部值。忽略 DTO 未知字段，允许独立 RouteKey 属性。解码结果 null 拒绝。支持 record、POJO、具体泛型 map/list；未解析类型变量、通配符及基本类型参数在构建时拒绝。FullHttpRequest 保留原借用路径。绑定不增加 Bean Validation、不为 String 推断 query 名、不强制 Content-Type、不鉴权 RouteKey；自定义 codec 可定义这些策略。body Key 提取与 DTO 绑定分开解析，不声明单次解析性能。

可使用 `public Result submit(RequestDto request)` 或 `public Result echo(String body)`，需要时在执行期间调用 `Contexts.current(HttpContext.class)`。默认解码值不保留原生缓冲区；HttpContext 的请求仍只借用到方法返回。[RuntimeHttpTest](../../game-runtime/src/test/java/cn/managame/runtime/http/RuntimeHttpTest.java) 验证泛型、query 数组、String、非法输入及失败不接纳。

## 14. Metadata 接入、外部回调、排空与诊断

`dispatch(connection, metadata, message)` 将非 null 的原始 Metadata 实例传给接入工厂；无工厂时按消息提取 Key，身份为 0/0。六参数 `dispatch(connection, key, businessIdType, businessId, metadata, message)` 绕过工厂，保留显式输入。其他重载使用空 Metadata，不隐式继承外层值。HandlerContextFactory 现在为四参数，三参数 lambda 必须迁移并保留输入 Metadata 实例。更换 Metadata 实例与替换消息/连接一样，以 HANDLER_CONTEXT_MISMATCH（3003）拒绝。packet 的 command/seq/code 可使用应用自定义 Core key；Runtime 不新增保留 key。

`dispatchRpc(sourceNodeId, sourceSlotId, command, requestId, key, businessIdType, businessId, metadata, message)` 查找精确 Handler 的有效注解 Domain，构造 DefaultRpcHandlerContext。沿用普通校验及参数解析路径，不引入 game-rpc 依赖。应用在分发前解码/复制借用 RPC body、检查 command/类型及身份，选择 Key/身份。此入口不发送回复、不选择 Peer、不修改 game-rpc 传输行为；可选对象 RPC 适配由 game-spring 提供，不增加核心反向依赖。[TransportContextTest](../../game-runtime/src/test/java/cn/managame/runtime/TransportContextTest.java) 验证便捷信封。

`runtime.callback(RouteCallback<T>)` 要求当前 Route 属于本 Runtime，否则抛 IllegalStateException。登记一个未完成回调，捕获原始 Context，返回线程安全的一次完成适配器。首次 onSuccess/onFail 回到源 Route（已在该 Route 时内联），重复完成忽略。onFail 要求正错误码，非法值抛 IllegalArgumentException 且不消费登记。回调异常报告 RUNTIME_EXECUTION_ERROR，回源接纳失败报告 ROUTE_CALLBACK_DISPATCH_FAILED。完成提交后释放回调登记，已提交回调任务继续计数。外部调用同步失败也必须完成适配器，并保证外部超时路径；没有独立 cancel 句柄。恢复 Context 的借用字段可能失效，初始 Handler 返回前应复制必要数据。

`shutdown()` 停止外部接纳和定时，保留执行器，不可恢复且幂等。本 Runtime 已执行的上下文可发起后续工作。`awaitTermination(Duration)` 要求先 shutdown 且调用方为外部管理线程，否则 IllegalStateException。null 超时抛 NullPointerException，负值抛 IllegalArgumentException，中断抛 InterruptedException，超时返回 false 且不关闭资源；零为非阻塞查询。等待已接纳动作和登记回调，单纯未完成 HTTP 响应不计入。`close()` 保留立即关闭执行器且不等待的语义。平滑顺序为 shutdown → 等待成功 → close → 关闭 network/RPC/data。[RuntimeDrainTest](../../game-runtime/src/test/java/cn/managame/runtime/RuntimeDrainTest.java) 验证 CAS 接纳/排空边界和回调登记。

`stats()` 返回 `cn.managame.runtime.diagnostic.RuntimeStats`：accepting、inFlight（排队/执行动作和登记回调）、queued、running、completed 动作（包括失败/内联）、rejected 停机/执行器接纳拒绝、errors 已报告错误及 executionNanos。rejected 不包括所有接纳前校验或解码错误；时间包含重叠嵌套区间，不是 CPU 时间。原子观察及 LongAdder 计数为近似值，各字段间可能变化，不代表业务成功。GameRuntime 扩展接口后，自定义实现需补齐方法。可选 Spring 装配及生命周期见 [game-spring](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md)，Runtime 本身不依赖 Spring。
