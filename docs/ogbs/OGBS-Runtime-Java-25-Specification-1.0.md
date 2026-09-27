# OGBS Runtime Java 25 Development Specification 1.0

文档类型：**Java 开发规范**。对应标准：[OGBS Runtime Specification](OGBS-Runtime-1.0.md)。

本文规定 Java 实现的公开 API、默认配置、异常形式、线程与资源机制、扩展接入以及验证要求。Java 实现必须同时满足本文和对应标准规范；不能只满足方法签名而忽略行为契约。代码与规范冲突时应修正实现，设计变更则同步修订两层规范。下文明确标注的待实现、未验证能力不代表已完成。

状态：当前仓库实现约定。语义规范见 [OGBS Runtime](OGBS-Runtime-1.0.md)，共享类型见 [OGBS Core](OGBS-Core-1.0.md)。

## 1. 模块与主 API

Maven 坐标为 `cn.managame:game-runtime:1.0.0-SNAPSHOT`，入口包名 `cn.managame.runtime`，按职责分为子包，依赖 `game-core`，要求 Java 25。

以下代码块列出接口签名；类型以源码为准。

```java
public interface GameRuntime extends AutoCloseable {
    void dispatch(HandlerContext context);
    <T> void call(int routeDomain, long routeKey,
                  Supplier<T> action, RouteCallback<T> callback);
    EventBus eventBus();
    RuntimeTimer timer();
    CronScheduler cron();
    ProtocolRegistry protocols();
    RouteKeyRegistry routeKeys();
    void close();
}

public interface RouteCallback<T> {
    void onSuccess(T result);
    void onFail(int errorCode);
}
```

没有独立 start 阶段、动态注册 API 或公开的任意 Runnable 分发入口。build 成功后可使用；外部网络回调需由接入层构造 HandlerContext 后 dispatch。

公开 API 的包划分：

| 包 | 内容 |
| --- | --- |
| cn.managame.runtime | GameRuntime、GameRuntimeBuilder |
| cn.managame.runtime.context | Context 体系、默认实现、只读 Contexts |
| cn.managame.runtime.route | Domain、Key 绑定/提取/注册、RouteCallback |
| cn.managame.runtime.executor | Executor SPI、绑定与官方实现 |
| cn.managame.runtime.protocol | 协议描述、注册、请求响应关联 |
| cn.managame.runtime.handler | Handler 注解 |
| cn.managame.runtime.event | Event、EventBus、事件注解 |
| cn.managame.runtime.timer | RuntimeTimer、TimerRef、Cron、CronScheduler |
| cn.managame.runtime.time | GameTime |
| cn.managame.runtime.error | RuntimeError、处理器与分发异常 |
| cn.managame.runtime.internal | 注册编译、调度与上下文绑定内部实现，应用不得依赖 |

原先 `cn.managame.runtime.*` 中的类型已迁移到对应子包。Java 通配 import 不包含子包，调用方需更新 import。Maven artifact 仍为一个 game-runtime，未拆成多个发布单元。详见 [模块目录](../../game-runtime/README.md)。

## 2. 构建

```java
GameRuntimeBuilder.builder()
    .routeDomains(Iterable<RouteDomain>)
    .routeExecutors(Iterable<RouteExecutorBinding>)
    .protocols(Iterable<? extends ProtocolProvider>)
    .routeKeys(Iterable<RouteKeyBinding<?>>)
    .handlers(Iterable<?>)
    .eventHandlers(Iterable<?>)
    .cronHandlers(Iterable<?>)
    .errorHandler(RuntimeErrorHandler)
    .cronZone(ZoneId)
    .build();
```

上述列表配置方法采用替换语义，每次调用复制本次 Iterable，不累加旧配置。列表默认空，Cron 默认 UTC，默认错误处理器写入 System.Logger。

`RouteDomain.of(int id, String name)` 要求 id > 0、name 非 null。`RouteExecutorBinding.of(executor, int... domains)` 必须绑定至少一个 Domain。构建要求每个已注册 Domain 恰好绑定一个 Executor，不允许绑定未知 Domain。

构建时解析注解并编译 MethodHandle；注册表在成功后固定。没有 classpath 扫描，实例由调用方显式提供。注解方法必须 public、实例方法、返回 void、非 varargs。错误配置通常抛出 IllegalArgumentException，null 输入可能抛出 NullPointerException。

构建失败不自动关闭调用方提供的 Executor；成功后 Runtime 负责关闭。不要把生命周期独立的 Runtime 绑定到同一个可关闭 Executor，除非明确实现了外部所有权适配。

### 2.1 构建阶段应完成什么

构建不是把对象列表保存下来等第一次消息再解析。实现需要在返回可用实例前，编译 Domain/Executor 绑定、协议与响应关系、Key 提取器、Handler/Event/Cron 方法和 Cron 表达式。重复或无法解析的定义在启动时拒绝，不能让同一部署因为消息到达顺序不同而选择不同 Handler。

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
interface EventContext extends InvocationContext { Event event(); }
interface TimerContext extends Context {}
interface RouteCallContext extends InvocationContext {}
```

Metadata 位于 `cn.managame.core`。默认实现的构造器：

| 类型 | 构造参数 |
| --- | --- |
| DefaultContext | (int domain, long key) |
| DefaultInvocationContext | (int domain, long key, int businessIdType, long businessId, Metadata metadata) |
| DefaultHandlerContext | (int domain, long key, Object message) |
| DefaultHandlerContext | (int domain, long key, int businessIdType, long businessId, Metadata metadata, Object message) |
| DefaultEventContext | (Event event, int businessIdType, long businessId, Metadata metadata) |
| DefaultTimerContext | (int domain, long key) |
| DefaultRouteCallContext | (int domain, long key, int businessIdType, long businessId, Metadata metadata) |

短 HandlerContext 构造器使用身份 0 和空 Metadata。businessIdType 范围 0–255，Metadata 和 message 不得为 null。默认类可继承，允许接入层增加连接、请求关联等信息；这些额外信息不会自动复制进 EventContext 或 RouteCallContext。

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
long RouteKeyRegistry.getRouteKey(Object message);
```

非 null 类型的查询未命中时返回 null。Key 注册独立于协议注册；按精确 Class 查询，未找到提取器返回 0。提取器异常传回调用方；0 无法作为有效的分发 Key。dispatch 不会因为注册了提取器就自动修正 Context。

## 6. Handler

`@Handler(domain = ...)` 是 handlers 列表内对象必须具备的类型注解，可以从父类继承。`@HandlerMethod(domain = ...)` 的非零 domain 覆盖类上的 domain，否则使用类配置。

方法必须包含且仅包含一个已注册 REQUEST/NOTIFY 消息参数，可以额外带一个 Context 参数，参数顺序不限，例如：

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
| public M handle(M m) | 不可以 | 返回值必须 void，不自动发送返回值 |
| public static void handle(M m) | 不可以 | 必须是实例方法 |
| public void handle(M a, M b) | 不可以 | 消息参数不唯一 |
| public void handle(TimerContext c, M m) | 不可以 | TimerContext 不是此 Handler 可接收的上下文 |
| public void handle(M... messages) | 不可以 | 不支持 varargs |

分发检查失败与业务执行失败必须分开：RuntimeDispatchException 表示前置条件或接纳失败；Handler 内抛出的异常进入 RuntimeErrorHandler。即使同 Route 内联，调用方也不能通过捕获 Handler 的原始业务异常来获得业务结果。


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
| RouteExecutors.virtualThreads() | 每个活跃 Route 一个串行 Mailbox | 默认总计 65,536 个未完成任务 |
| new VirtualThreadRouteExecutor(capacity) | 活跃 Mailbox 由虚拟线程处理 | 所有 Route 合计，包含正在执行任务 |

参数必须为正数。平台线程方案中不同 Route 可能落到同一分片并互相等待；虚拟线程方案在 Mailbox 空闲后移除对应状态。

两种执行器 close 都不等待完成，已接受任务继续处理。自定义执行器必须遵守规范的接纳和串行契约；不可用普通多线程线程池直接替代 Route 串行语义。

### 7.1 两种容量的计算示例

假设 StripedRouteExecutor 有 2 个分片、每分片 queueCapacity=100。当分片 A 已有一个执行中任务和 100 个等待任务时，下一项映射到 A 的任务会过载；即使分片 B 空闲，也不借用 B 来打破 A 的排队边界。容量统计不包括正在执行的那个任务。

VirtualThreadRouteExecutor(capacity=100) 则计算所有 Route 的未完成任务。若已有 99 个等待任务加 1 个执行中任务，容量已满；不能按“等待队列只有 99”再接受一个。虚拟线程数量不代表可无限接纳。

同 Route 内联不经过上述容量检查，也不计入新的排队份额。递归触发同 Route Event/call 仍可能消耗调用栈，容量限制不会替业务防止无限递归。

### 7.2 自定义 Executor 的验收边界

扩展 SPI 时至少应验证：完整 Route 的互斥执行、同 Route 入队顺序、拒绝任务从不执行、已接受任务仅执行一次，以及关闭与提交竞争。错误返回后又执行 Runnable 会造成调用方误判；先返回 ACCEPTED 再静默丢弃也不符合契约。

任务包装绑定 Context 的工作由 Runtime 完成，Executor 只承担接纳与调度。Executor 不应自行猜测玩家 ID、协议类型或业务异常含义。调整队列容量或关闭行为属于 Java 实现契约变化，若改变可观察接纳语义还需同步标准 Spec。


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

## 10. GameTime、Timer 与 Cron

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

Runtime 错误编号见 [OGBS Core](OGBS-Core-1.0.md)。当前实现报告的 RuntimeError 包含发生错误的 Context 和 Route。错误处理器应快速返回，避免再次抛异常；其异常会由实现记录。

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

