# OGBS Runtime Java 25 Development Specification 1.0

[English](OGBS-Runtime-Java-25-Specification-1.0.md) | **[简体中文](OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md)**

文档类型：**Java 开发规范**。行为由 [Runtime 规范](OGBS-Runtime-1.0.zh-CN.md) 定义；本文定义 Java API、默认值、异常、线程与验证。共享类型见 [Core](OGBS-Core-1.0.zh-CN.md)。

## 1. 模块与主 API

Maven `cn.managame:game-runtime:1.0.0-SNAPSHOT`，Java 25。依赖 game-core、game-network（HTTP 入口）、Jackson Databind 2.21.3（HTTP 结果与 JSON Key 提取）；定时器使用 game-network 带来的 Netty HashedWheelTimer；不依赖 RPC。

```java
public interface GameRuntime extends AutoCloseable {
    void dispatch(HandlerContext context);
    void dispatch(Connection connection, long routeKey, Object message);
    default void dispatch(Connection connection, long routeKey, int businessIdType, long businessId, Object message);
    void dispatch(Connection connection, long routeKey, int businessIdType, long businessId, Metadata metadata, Object message);
    default void dispatch(Connection connection, Object message);
    void dispatch(Connection connection, Metadata metadata, Object message);
    void dispatch(Connection connection, int businessIdType, long businessId, Object message);
    void dispatchRpc(int sourceNodeId, int sourceSlotId, int command, int requestId, long routeKey,
                     int businessIdType, long businessId, Metadata metadata, Object message);
    <T> void call(int routeDomain, long routeKey, Supplier<T> action, RouteCallback<T> callback);
    <T> CapturedRouteCallback<T> callback(RouteCallback<T> callback);
    HttpDispatcher http();
    EventBus eventBus();
    RuntimeTimer timer();
    CronScheduler cron();
    ProtocolRegistry protocols();
    RouteKeyRegistry routeKeys();
    void shutdown();
    boolean awaitTermination(Duration timeout) throws InterruptedException;
    RuntimeStats stats();
    void close();
}
public interface RouteCallback<T> {
    void onSuccess(T result);
    void onFail(int errorCode);
    default void onFail(int errorCode, Throwable cause) { onFail(errorCode); }
}
public interface CapturedRouteCallback<T> extends RouteCallback<T> { boolean cancel(); }
```

构建成功即可使用；没有 start、动态注册或任意 Runnable 派发。业务常用入口是 `dispatch(connection, routeKey, businessIdType, businessId, message)`，Domain 来自 Handler 注解（§6.2）。配置 `handlerContextFactory` 后，`dispatch(connection, message)` 按 Domain 选择 Key 与身份（§6.4）。HTTP 通过 `asyncHandler(runtime.http()::dispatch)` 接入（§13）。

| 包 | 内容 |
| --- | --- |
| cn.managame.runtime | GameRuntime、GameRuntimeBuilder |
| .context | Context 层次、默认实现、Contexts |
| .route | RouteDomain、RouteKeyBinding/Extractor/Registry、RouteCallback |
| .executor | RouteExecutor SPI、绑定、官方执行器 |
| .protocol | 协议描述、注册、请求/响应配对 |
| .handler | Handler 注解、HandlerArgumentBinding、HandlerContextFactory |
| .http | HTTP 注解、HttpDispatcher、HttpContext、HttpContextFactory、HttpResultCallback、codec |
| .event / .timer / .time / .error / .diagnostic | 事件、定时器与 Cron、GameTime、错误、RuntimeStats |
| .internal | 编译、调度、上下文绑定；不是应用 API |

## 2. 构建

```java
GameRuntimeBuilder.builder()
    .routeDomains(Iterable<RouteDomain>)
    .routeExecutors(Iterable<RouteExecutorBinding>)
    .protocols(ProtocolRegistry)
    .routeKeys(Iterable<RouteKeyBinding<?>>)
    .handlers(Iterable<?>)
    .handlerArguments(Iterable<? extends HandlerArgumentBinding<?>>)
    .handlerContextFactory(HandlerContextFactory)
    .httpHandlers(Iterable<?>)
    .httpContextFactory(HttpContextFactory)
    .httpResultCodec(HttpResultCodec)
    .httpRequestCodec(HttpRequestCodec)
    .eventHandlers(Iterable<?>)
    .cronHandlers(Iterable<?>)
    .errorHandler(RuntimeErrorHandler)
    .cronZone(ZoneId)
    .build();
```

列表 setter 复制并**替换**之前的值（先 `handlers(A)` 再 `handlers(B)` 只注册 B）。默认：空列表、UTC Cron 时区、System.Logger 错误处理。`RouteDomain.of(id, name)` 要求 id > 0；每个 Domain 通过 `RouteExecutorBinding.of(executor, domains...)` 恰好绑定一个执行器。

build 在返回前完成全部编译：Domain/执行器绑定、协议与响应配对、Key 提取器、Handler/Event/Cron/HTTP 方法（MethodHandle）和 Cron 表达式。不扫描 classpath，由调用方传入实例。Handler/Event/Cron 方法为 public、非 static、非可变参数且返回 void；HTTP 方法也可返回业务对象。重复或无法解析的定义抛 IllegalArgumentException（null 参数抛 NullPointerException）：

| 检查 | 典型错误 |
| --- | --- |
| Domain/执行器 | ID 重复、未知 Domain、缺少绑定 |
| 协议 | 消息类或 type+command 重复；响应配对指向未注册或方向错误的类型 |
| Handler | 消息未注册、一条消息两个方法、未知 Domain、上下文参数不属于 HandlerContext 层次 |
| Event | 参数不是恰好一个 Event |
| Cron | 表达式或 Route 非法、声明类加方法名重复 |

构建失败不关闭传入的执行器；构建成功后由 Runtime 持有并逐个关闭一次。Handler 实例在多个 Route 间共享，其字段不会被串行化，可变状态应按 Route 组织。

## 3. 最小示例

```java
public record Ping(long playerId) {}

@Handler(domain = 1)
public final class PingHandler {
    @HandlerMethod public void onPing(HandlerContext context, Ping message) {
        System.out.println(context.routeKey() + ": " + message);
    }
}

try (GameRuntime runtime = GameRuntimeBuilder.builder()
        .routeDomains(List.of(RouteDomain.of(1, "player")))
        .routeExecutors(List.of(RouteExecutorBinding.of(RouteExecutors.virtualThreads(), 1)))
        .protocols(ProtocolRegistry.of(ProtocolDescriptor.notify(1001, Ping.class)))
        .handlers(List.of(new PingHandler()))
        .build()) {
    runtime.dispatch(connection, 42L, new Ping(42));
}
```

close 不等待任务完成；优雅停止使用 shutdown → awaitTermination → close（§14）。

## 4. Context 与作用域

```java
interface Context { int routeDomain(); long routeKey(); }
interface InvocationContext extends Context { int businessIdType(); long businessId(); Metadata metadata(); }
interface HandlerContext extends InvocationContext { Object message(); }
interface ClientHandlerContext extends HandlerContext { Connection connection(); }
interface RpcHandlerContext extends HandlerContext { int sourceNodeId(); int sourceSlotId(); int command(); int requestId(); }
interface EventContext extends InvocationContext { Event event(); }
interface RouteCallContext extends InvocationContext {}
interface TimerContext extends Context {}
interface HttpContext extends Context { FullHttpRequest request(); HttpResultCallback responseCallback(); }  // §13
```

每个接口都有可扩展的 Default 实现（DefaultHandlerContext、DefaultClientHandlerContext、DefaultRpcHandlerContext、DefaultEventContext、DefaultRouteCallContext、DefaultTimerContext、DefaultHttpContext）；短构造器使用身份 0/0 和空 Metadata。businessIdType 为 0..255；message 与 Metadata 非 null。

`Contexts.current()` 返回当前绑定的上下文（未绑定抛 NoSuchElementException），`currentOrNull()` 返回 null，`current(Class)` 做类型转换（类型不符抛 ClassCastException）。绑定使用 Java 25 ScopedValue 并同时记录所属 Runtime；只有 Runtime 执行时才绑定，嵌套执行结束后恢复外层值。当前 Runtime、Domain、Key 全部相同时提交直接内联执行，否则交给绑定的执行器。回调恢复原上下文对象本身；`call` 的目标计算得到只带调用身份与 Metadata 的 DefaultRouteCallContext。

<a id="transport-handler-contexts"></a>

### 4.2 客户端与 RPC Handler 上下文

ClientHandlerContext 表示经任意 game-network Connection（包括 WebSocket）进入的客户端请求，不代表已认证。所有带 Connection 的派发重载都构造 DefaultClientHandlerContext，connection 可为 null，Runtime 不检查也不关闭它。RpcHandlerContext 表示服务间 RPC 请求（Call 与 Notify；requestId 0 表示 Notify），不暴露连接。DefaultRpcHandlerContext 要求 sourceNodeId、command 非零且 sourceSlotId 为 0..254。RPC 适配层须在回调返回前解码借用的 body，并选择非零业务 Key；上下文不持有 RPC 缓冲。

Handler 方法可接收 HandlerContext（与传输无关）、ClientHandlerContext 或 RpcHandlerContext；实际上下文不兼容时，在参数解析与准入之前以 HANDLER_CONTEXT_MISMATCH（3003）拒绝。每个消息类仍只有一个 Handler，多种传输共用的消息应使用 HandlerContext。测试：[TransportContextTest](../../game-runtime/src/test/java/cn/managame/runtime/TransportContextTest.java)。

## 5. Protocol 与 RouteKey 注册

```java
record ProtocolDescriptor(Type type, int command, Class<?> messageType, Class<?> responseType) {
    enum Type { REQUEST, RESPONSE, NOTIFY }
    static ProtocolDescriptor request(int command, Class<?> type);                        // 无响应
    static ProtocolDescriptor request(int command, Class<?> type, Class<?> responseType);
    static ProtocolDescriptor response(int command, Class<?> type);
    static ProtocolDescriptor notify(int command, Class<?> type);
}
final class ProtocolRegistry {
    static ProtocolRegistry of(ProtocolDescriptor... descriptors);   // 另有 of(Collection)、empty()
    ProtocolDescriptor get(ProtocolDescriptor.Type type, int command);
    ProtocolDescriptor get(Class<?> messageType);
    List<ProtocolDescriptor> descriptors();
}

RouteKeyBinding.of(type, extractor); RouteKeyBinding.ofField(type, "field"); RouteKeyBinding.ofMethod(type, "method");
long RouteKeyRegistry.getRouteKey(Object message);   // 无绑定时为 0
```

`ProtocolRegistry.of` 在构建时校验：描述按 type+command 及精确消息类各自唯一，请求的 responseType 必须已注册为 RESPONSE，否则抛 IllegalArgumentException。各类型的 command 空间相互独立，响应可以沿用请求的 command，也可以不同，例如 `request(1003, LoginReq.class, LoginRes.class)` 配 `response(2003, LoginRes.class)`。command 使用全部 32 位；只有 REQUEST 可以带 responseType。查询未命中返回 null。通过 `builder.protocols(registry)` 传入；多个注册表可对其 `descriptors()` 再调用 `ProtocolRegistry.of(...)` 合并。

## 6. Handler

Handler 对象标注 `@Handler(domain = …)`（可继承）。方法上非零的 `@HandlerMethod(domain = …)` 覆盖类上的 Domain。方法恰好接收一个已注册的 REQUEST/NOTIFY 消息、最多一个上下文参数（Context、InvocationContext、HandlerContext 或其子类型）以及任意已显式绑定的自定义参数（§6.3），顺序不限。消息按精确类匹配，每个消息一个 Handler。

| 形式 | 是否合法 |
| --- | --- |
| `void handle(M m)`、`void handle(HandlerContext c, M m)`、`void handle(M m, CustomContext c)` | 是 |
| `void handle(Extra x, M m)` | 为 Extra 配置绑定后合法 |
| `void handle(Connection c, M m)` | 否；从 ClientHandlerContext 读取 |
| `M handle(M m)`、`static void handle(M m)`、`void handle(M a, M b)`、`void handle(TimerContext c, M m)`、`void handle(M... m)` | 否 |

前置条件与准入失败抛带 Core 错误码的 RuntimeDispatchException（不采集栈，过载拒绝只付出一次分配；toString 含 `FrameworkErrorCodes.describe`）；开始执行后的异常交给 RuntimeErrorHandler（内联执行也一样），不会抛给调用方。

<a id="automatic-handler-dispatch"></a>

### 6.2 指定 Key 派发与消息提取

`dispatch(connection, routeKey, businessIdType, businessId, message)` 使用 Handler 注解的 Domain 和传入的非零 Key，不读取消息内容，构造带空 Metadata 的 DefaultClientHandlerContext；`dispatch(connection, routeKey, message)` 使用身份 0/0。businessIdType 超出 0..255 抛 IllegalArgumentException。

对于路由信息在消息内的少数消息，`dispatch(connection, businessIdType, businessId, message)`（未配置上下文工厂时也可用 `dispatch(connection, message)`）使用精确类的 Key 绑定：来自 `routeKeys(...)`，或来自 `@Handler`/`@HandlerMethod` 的 `routeKey = "字段"` / `routeKeyMethod = "方法"`。方法级规则整体替换类级规则；同时给出两个名称、名称为空白、或注解规则与 Builder 绑定指向同一消息时构建失败。无绑定或 Key 为 0 → INVALID_ROUTE_KEY（3005）；无 Handler → HANDLER_NOT_FOUND（3002）；已关闭 → RUNTIME_CLOSED（3001）。提取器异常在准入前于提交线程抛出。这些重载从不继承外层上下文的身份或 Metadata。

`ofField`/`ofMethod` 在构建时编译一次：字段可为 private 或继承（就近优先）；方法为 public、非 static、无参数；值须为 byte/short/int/long 或其包装类型。包装值为 null 抛 NullPointerException；getter 抛出的受检异常包装为 IllegalStateException。测试：[HandlerDispatchTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerDispatchTest.java)。

```java
runtime.dispatch(connection, 99L, loginReq);         // 调用方已选好 Key，不读取 loginReq
// 配置 @HandlerMethod(routeKey = "userId") 且无上下文工厂时：
runtime.dispatch(connection, loginReq);
```

<a id="handler-arguments"></a>

### 6.3 业务身份与可选 Handler 参数

业务身份从上下文读取：`context.businessId()` / `businessIdType()`，不需要包装类型。Key 与身份相互独立；认证由接入层负责。

`HandlerArgumentBinding.of(Type.class, context -> value)` 注册额外参数类型（精确类；不能是基本类型、Context 或协议）。重复绑定、方法中有未绑定的参数类型或同一方法重复某类型时构建失败。解析器每次派发执行一次，按声明顺序在提交线程、准入之前运行；必须线程安全、不访问 Route 状态，并返回该类型的非 null 值（否则 NullPointerException / ClassCastException）。解析器异常直接抛出，不提交任务。已接纳的任务保留解析结果。绑定只作用于 HandlerMethod。测试：[HandlerArgumentTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerArgumentTest.java)；示例：[RoleHandler](../../game-demo/src/main/java/cn/managame/demo/bus/role/RoleHandler.java)。

<a id="handler-context-factory"></a>

### 6.4 按 Domain 选择接入上下文

```java
@FunctionalInterface
interface HandlerContextFactory {
    ClientHandlerContext create(int domain, Connection connection, Metadata metadata, Object message);
}
```

配置后，`dispatch(connection, message)` 与 `dispatch(connection, metadata, message)` 在提交线程、准入之前调用它一次，传入 Handler 注解的有效 Domain；此时不执行任何 Key 提取器。工厂按 Domain 选择 Key 与身份（例如从 Connection 属性或线程安全的身份表），可返回应用自定义子类型。必须保持 Domain 以及 message、connection、Metadata 引用不变：Domain 改变 → ROUTE_DOMAIN_MISMATCH（3004），对象被替换或上下文不兼容 → HANDLER_CONTEXT_MISMATCH（3003），Key 为 0 → INVALID_ROUTE_KEY（3005），返回 null → NullPointerException。抛异常即拒绝派发（不重试，不进入错误处理）。显式 Key、显式上下文与显式身份的重载绕过工厂。Runtime 不关闭也不复制工厂。

```java
builder.handlerContextFactory((domain, connection, metadata, message) -> {
    Identity id = Objects.requireNonNull(identities.get(connection), "Not authenticated");
    return switch (domain) {
        case ROLE_ID -> new DefaultClientHandlerContext(domain, id.roleRouteKey(), ROLE_TYPE, id.roleId(), metadata, message, connection);
        default -> throw new IllegalArgumentException("Unsupported domain: " + domain);
    };
});
runtime.dispatch(connection, decodedMessage);
```

demo 把认证后的 [GameSession](../../game-demo/src/main/java/cn/managame/demo/network/GameSession.java) 存在 Connection 上，由 [GameDomain](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameDomain.java) 按 Domain 选择。测试：[HandlerContextFactoryTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerContextFactoryTest.java)。

## 7. RouteExecutor

```java
interface RouteExecutor extends AutoCloseable {
    RouteExecuteStatus tryExecute(int domain, long key, Runnable task);   // ACCEPTED、OVERLOADED、CLOSED
    default void close() {}
}
```

| 执行器 | 串行方式 | 容量 |
| --- | --- | --- |
| RouteExecutors.platformThreads(n) / StripedRouteExecutor(n, queue) | Route 哈希到 n 个单线程分片之一；同分片的 Route 互相等待 | 每个分片的等待任务数（默认 65,536），不含正在运行的任务 |
| RouteExecutors.virtualThreads() / VirtualThreadRouteExecutor(capacity[, routeCapacity]) | 每个有工作的 Route 一个无锁 Mailbox 和一个虚拟线程消费者 | 全部 Route 的未完成任务数（默认 65,536，含运行中）及每个 Route 在运行任务之外的等待任务数（默认 min(capacity, 1024)） |

两者都不阻塞地拒绝，关闭后继续执行已接纳的任务。单 Route 上限防止一个刷请求的 Route 占满共享容量；网络 pipeline 中仍应做连接级限流。同 Route 内联执行不受容量限制（递归仍消耗栈）。自定义执行器必须保证同 Route 互斥与顺序、被拒绝的任务不执行、已接纳的任务恰好执行一次，并处理关闭与提交的竞争。

<a id="route-mailbox-lifecycle"></a>

### 7.3 虚拟线程 Mailbox

Mailbox 只在 Route 有排队或运行中的任务时存在于以 Domain/Key 为键的 ConcurrentHashMap 中。Mailbox 由无锁队列和一个计数组成（排队加运行中的任务）：提交方 CAS 递增计数后入队，把计数从 0 变为 1 的提交方负责启动消费者；消费者执行完一个任务后递减计数，降到 0 时把计数标记为退役并从表中移除 Mailbox，然后退出。看到退役标记的提交方替换为新 Mailbox。因为计数只有在最后一个任务执行完后才归零，同一 Route 任意时刻至多一个消费者，任务按提交顺序执行；入队和出队都不取锁，也没有空闲缓存——空闲 Route 不占内存，不再依赖 Caffeine。一个 AtomicLong 同时保存关闭位与全部预留，准入与关闭是原子的；关闭后已接纳的任务继续执行完。测试：[VirtualThreadRouteExecutorTest](../../game-runtime/src/test/java/cn/managame/runtime/executor/VirtualThreadRouteExecutorTest.java)、[RouteExecutorTest](../../game-runtime/src/test/java/cn/managame/runtime/executor/RouteExecutorTest.java)。

## 8. 跨 Route 调用与回调

```java
runtime.call(2, guildId, () -> loadGuildSnapshot(guildId), new RouteCallback<GuildSnapshot>() {
    public void onSuccess(GuildSnapshot snapshot) { /* 回到来源 Route */ }
    public void onFail(int errorCode) { /* 框架错误 */ }
});
```

call 须在本 Runtime 的当前上下文中调用（否则 IllegalStateException）。目标在带来源身份与 Metadata 的 DefaultRouteCallContext 中运行。目标拒绝时以错误码调用 onFail；action 抛异常时报告 ROUTE_CALL_EXECUTION_ERROR（3008）并调用 onFail(3008)；回调抛异常报告 RUNTIME_EXECUTION_ERROR（3009）；无法回到来源时报告 ROUTE_CALLBACK_DISPATCH_FAILED（3010）。同 Route 调用可能在 call 返回前完成。没有 Future、超时或取消。

## 9. EventBus

```java
interface Event { int routeDomain(); long routeKey(); }
interface EventBus { void publish(Event event); }
@EventMethod(order = 10) public void onChanged(MyEvent event) { … }
```

通过 eventHandlers 注册的监听方法接收一个 Event 子类型，按精确类匹配、按 order 升序执行（相同 order 顺序不定），在事件所在 Route 的 DefaultEventContext 中运行；某个监听失败会被报告（3009），其余监听照常执行。从本 Runtime 内发布时继承身份与 Metadata，否则为 0/0 与空。即使没有监听也会校验 Route 与准入；没有监听的事件在校验后直接返回，不创建上下文、不提交 Route 任务，也不计入 RuntimeStats。

<a id="static-event-publication"></a>

### 9.1 静态发布

`Events.publish(event)` 通过当前执行所属的 Runtime 发布，否则使用启动时 `Events.bind(runtime)` 设置的进程默认 Runtime；两者都没有时抛 IllegalStateException。已绑定时绑定另一个 Runtime 会抛异常；重复绑定同一个不做任何事；`Events.unbind(runtime)` 只移除该对象的绑定。Runtime.close 会移除自己的默认绑定；自定义 GameRuntime 实现须调用 `Events.unbind(this)`。测试：[EventsTest](../../game-runtime/src/test/java/cn/managame/runtime/event/EventsTest.java)。

## 10. GameTime、Timer 与 Cron

<a id="demo-task-integration"></a>

[demo](../../game-demo/README.zh-CN.md#demo-runtime-services) 通过 CronMethodFilter/CronBeans 收集 @Cron Bean，调度一个 TimerRef Bean，并经同一 Runtime 提供 HTTP（[DemoServicesTest](../../game-demo/src/test/java/cn/managame/demo/DemoServicesTest.java)）。

### 10.1 GameTime

`GameTime.currentTimeMillis()`、`GameTime.now(ZoneId)`、`setClock(Clock)`、`resetClock()`。进程内唯一时钟（默认 `Clock.systemUTC()`）。修改时钟不会重排定时器或 Cron；时长与超时仍用单调时钟。测试结束须重置。

### 10.2 一次性 Timer

`TimerRef RuntimeTimer.schedule(domain, key, Duration delay, Runnable task)` 立即校验 Route（拒绝负延迟），到期后在新的 DefaultTimerContext 中把任务提交到该 Route。每个 Runtime 一个时间轮线程（Netty HashedWheelTimer，tick 10 ms、1024 槽）：调度与取消为 O(1)，适合大量 buff、冷却、过期计时器；精度为一个 tick——到期不早于延迟，通常在其后一个 tick 内触发。时间轮线程只把任务提交到 Route，业务代码不在其上运行。`TimerRef.cancel()` 只在触发前成功，不能撤回已提交的任务。

### 10.3 Cron 表达式

`@Cron(value = "0 */5 * * * ?", domain = 1, routeKey = 42)` 标在传给 cronHandlers 的对象的 public、非 static、无参 void 方法上。cronZone 默认 UTC。

| 项 | 规则 |
| --- | --- |
| 字段 | 秒 分 时 日 月 星期（共六段） |
| 范围 | 0–59、0–59、0–23、1–31、1–12、1–7（1 = 周日） |
| 语法 | 数字、`*`、列表、范围、步长；`?` 只用于两个日期字段 |
| 日期字段 | 日和星期最多限定其一，另一个必须是 `*` 或 `?`（与 Quartz 相同） |
| 不支持 | 名称、L、W、#、年字段 |
| 搜索范围 | 下一次触发须在八年内 |

每次执行后，从“当前 GameTime”与“刚计划的时刻”二者较晚者之后计算下一次触发，因此即使计时器在业务时钟到达前略早触发，同一时刻也不会被计划两次。被拒绝或失败的一次仍会调度下一次；错过的时刻不补发。

### 10.4 Cron 取消与重排

```java
boolean cancel(Class<?> type, String methodName);       // 原本活动时返回 true
boolean reschedule(Class<?> type, String methodName);   // 从当前 GameTime 重新计算；未知时返回 false
void rescheduleAll();
```

Key 为声明类加方法名。cancel 停止后续执行，已开始的一次照常完成。reschedule 会重新激活已取消的条目。代数机制让旧计时器以及排队中尚未认领的旧执行变为空操作。关闭后 cancel 返回 false，reschedule 抛 RuntimeDispatchException(RUNTIME_CLOSED)。重排可能再次经过同一时刻，业务逻辑需保持幂等。测试：[CronSchedulerTest](../../game-runtime/src/test/java/cn/managame/runtime/timer/CronSchedulerTest.java)、[CronScheduleTest](../../game-runtime/src/test/java/cn/managame/runtime/internal/CronScheduleTest.java)。

## 11. 错误与关闭

```java
interface RuntimeErrorHandler { void onError(RuntimeError error); }
record RuntimeError(int errorCode, Context context, int routeDomain, long routeKey, Throwable cause) {}
int RuntimeDispatchException.errorCode();
```

| 阶段 | 调用方看到 | 错误处理看到 |
| --- | --- | --- |
| dispatch/publish 校验或拒绝 | RuntimeDispatchException | — |
| Handler 或监听异常 | 无 | RUNTIME_EXECUTION_ERROR |
| 无当前上下文时调用 call | IllegalStateException | — |
| call 目标拒绝 / action 异常 / 回调异常 | onFail(code) / onFail(3008) / — | — / 3008 / 3009 |
| Timer 到期后被拒绝 | 无 | 拒绝错误码 |

错误处理在触发线程上运行，应尽快返回且不抛异常（其异常会被记录）。close 幂等：停止定时器，逐个关闭执行器一次且不等待；之后的 dispatch、publish、schedule 被拒绝；注册表仍可读取。

## 12. 源码与测试

源码：[GameRuntime](../../game-runtime/src/main/java/cn/managame/runtime/GameRuntime.java)、[GameRuntimeBuilder](../../game-runtime/src/main/java/cn/managame/runtime/GameRuntimeBuilder.java)、[DefaultGameRuntime](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java)、[RuntimeCompiler](../../game-runtime/src/main/java/cn/managame/runtime/internal/RuntimeCompiler.java)。[RuntimeTest](../../game-runtime/src/test/java/cn/managame/runtime/RuntimeTest.java) 中的关键测试：同 Route 内联事件与隔离、不同 Runtime 不内联、跨 Route 调用与来源恢复、目标拒绝与返回拒绝、Timer 新上下文、共享执行器只关闭一次。运行 `mvn -pl game-runtime -am test`。

<a id="runtime-http-api"></a>

## 13. HTTP 注解与 Route 接入

实现 [RT-HTTP-01–08](OGBS-Runtime-1.0.zh-CN.md#runtime-http-profile)。Network 的 [HttpResponseCallback](OGBS-Network-Java-25-Specification-1.0.zh-CN.md#http-async-response) 是传输边界；业务代码用 HttpResultCallback 与普通对象完成响应。

### 13.1 注册与上下文

`runtime.http()` 返回冻结的 HttpDispatcher，通过 `HttpServerBuilder.asyncHandler(runtime.http()::dispatch)` 接入。`@HttpHandler(domain, routeKey, routeKeyMethod)` 标在类上；`@HttpMethod(value = "/path", method = HttpRequestMethod.POST, domain, routeKey, routeKeyMethod)` 标在方法上（method 默认 POST，GET 须显式指定；HttpRequestMethod 为 GET、POST、PUT、PATCH、DELETE、HEAD、OPTIONS、TRACE）。方法上非零 Domain 覆盖类上的 Domain。方法为 public 实例方法，返回 void 或业务对象（不能是基本类型或 Netty HttpObject），最多一个输入（FullHttpRequest、String 或由 HttpRequestCodec 解码的 DTO）和最多一个上下文（Context、HttpContext 或其子类型），顺序不限。

路径按不含 query 的原始、区分大小写的文本匹配：`/player?id=42` 匹配 `/player`，`/player/` 不匹配。不自动处理 HEAD 或 OPTIONS。已知路径但方法不对时返回 405 并附排序后的 Allow 头。

`HttpContext` 在 Route 访问器之外增加 `request()` 与 `responseCallback()`，没有业务身份或 Metadata。`HttpContextFactory.create(domain, routeKey, request, callback)` 在准入前运行：须保持 Domain、已提取的非零 Key、request 与 callback 不变；没有 Key 规则时由它选择 Key；可以直接完成 callback（例如 401）并返回 null 以跳过方法。没有 Key 规则或使用自定义上下文类型的端点必须配置工厂。

### 13.2 完成、编码与所有权

`HttpResultCallback`：`onResponse(result)`（状态 200）、`onResponse(status, result)`（200..599）、`onFail(cause)`。返回对象（包括 null → JSON `null`）自动完成；void 方法须显式完成，可以立即也可以稍后。完成只认领一次；只编码胜出的结果，在完成线程上同步由 `HttpResultCodec` 编码（默认 Jackson JSON，`application/json; charset=UTF-8`，与默认请求解码共用同一套属性规则：任意可见性的字段加普通 getter/setter，因此同一个 DTO 读写的字段一致；请求体直接从 ByteBuf 流式解析，不先复制成 String；自定义 codec 返回独立持有的 byte[] 与内容类型，并须线程安全）。编码失败或把传输对象作为结果 → RUNTIME_EXECUTION_ERROR 并走 Network 失败路径（500 并关闭）。

| 情况 | HTTP | 是否执行方法 |
| --- | --- | --- |
| Runtime 已关闭 | 503 | 否 |
| 非 origin-form 目标或含 fragment | 400 | 否 |
| 未知路径 / 方法不对 | 404 / 405 + Allow | 否 |
| Key 或输入非法、Key 为 0 | 400 | 否 |
| 工厂直接完成 | 其响应 | 否 |
| 过载或执行器已关闭 | 503 | 否 |
| 方法抛异常 | 未完成时 500 | 报告错误 |
| 返回 null | 200 JSON null | 是 |

Runtime 持有 request 直到方法返回并完成自动编码（被拒绝时立即释放）。之后还需要的数据须在返回前复制。连接断开不取消已接纳的工作；Runtime.close 不关闭 HttpServer，也不等待延迟完成。

<a id="135-request-binding"></a>
<a id="135-请求绑定"></a>

### 13.3 Route Key 规则与请求绑定

类上的 `routeKey`/`routeKeyMethod` 适用于其全部方法；方法级的非空规则整体替换类级规则；同时给出两个名称或名称为空白时构建失败。`routeKey = "playerId"`：GET 读取恰好一个 query 值（Netty QueryStringDecoder，UTF-8）；其他方法读取单个严格 UTF-8 JSON 对象的顶层属性（拒绝重复名称、多余值、小数、null 与溢出），与 Content-Type 无关。值为非零的十进制 64 位整数。`routeKeyMethod = "playerKey"` 绑定 Handler 上 public 的 `long`/`Long playerKey(FullHttpRequest)`，在工厂与准入之前运行；IllegalArgumentException → 400，其他失败 → 500。提取在 body 的非释放 duplicate 上用 Jackson 流式解析。

`HttpRequestCodec`（默认 `HttpRequestCodec.json()`）按方法声明的泛型类型为每个方法编译一个解码器。String 接收原始 UTF-8 body；其他类型由 Jackson 读取：GET 用 query 参数构造对象（重复名称变为数组），其他方法解析 body，拒绝多余 token 并忽略未知属性。结果为 null 或抛 IllegalArgumentException → 400，不执行方法。类型变量、通配符与基本类型参数在构建时失败。

示例：[RuntimeHttpExample](../../game-demo/src/main/java/cn/managame/demo/examples/runtime/RuntimeHttpExample.java)（带 body Key 的 POST `/echo`，带 query Key 与跨 Route 回调的 GET `/lookup`）。测试：[RuntimeHttpTest](../../game-runtime/src/test/java/cn/managame/runtime/http/RuntimeHttpTest.java)、[HttpRouteKeyTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpRouteKeyTest.java)、[HttpResultTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpResultTest.java)。本层只提供 HTTP/1.1 语义。

## 14. Metadata 接入、外部回调、排空与诊断

`dispatch(connection, metadata, message)` 把同一 Metadata 实例交给上下文工厂（未配置时使用消息 Key 提取与身份 0/0）；六参数重载保留显式的 Key、身份与 Metadata。其他重载使用空 Metadata。

`dispatchRpc(...)` 解析 Handler 的 Domain 并创建 DefaultRpcHandlerContext；应用已解码 body 并选好 Key 与身份。它不发送任何内容；类型化适配位于 game-spring。

`runtime.callback(cb)` 须在本 Runtime 的当前 Route 中调用。它预留一个计入排空的待完成续接，返回线程安全的一次性 CapturedRouteCallback：第一次 onSuccess/onFail 回到来源 Route（已在该 Route 时内联执行），之后的调用被忽略；onFail 需要正的错误码，`onFail(code, cause)` 把原因交给业务回调的同名方法（默认忽略原因）。必须保证它被完成或取消，包括通过外部超时。外部调用同步失败、且已经用异常告知调用方时，用 `cancel()` 释放预留而不运行业务回调，避免同一失败被报告两次；已完成或已取消时 cancel 返回 false。`call` 的 action 抛异常时，回调收到 `onFail(3008, cause)`。

`shutdown()` 停止外部准入与定时器（不可逆、幂等）；正在运行的工作仍可提交下游工作。`awaitTermination(timeout)` 须在 shutdown 之后、由外部线程调用（否则 IllegalStateException），等待已接纳的动作与已登记的回调；超时返回 false，不关闭任何东西。`close()` 关闭执行器且不等待。优雅顺序：shutdown → awaitTermination → close → 关闭网络、RPC 与数据。测试：[RuntimeDrainTest](../../game-runtime/src/test/java/cn/managame/runtime/RuntimeDrainTest.java)。

`stats()` 返回 RuntimeStats(accepting, inFlight, queued, running, completed, rejected, errors, executionNanos)；计数为近似值，不代表业务成功。计数使用分段 LongAdder，所有工作线程不争用同一变量；`GameRuntimeBuilder.executionTiming(false)` 关闭每个任务两次读取单调时钟，此时 executionNanos 保持 0。Spring 装配见 [game-spring](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md)。
