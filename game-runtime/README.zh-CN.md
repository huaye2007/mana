# game-runtime

[包结构、职责与 import 迁移](../docs/package-layout.md)

[English](README.md) | 简体中文 | [架构与职责](docs/architecture.md)

基于 Java 25、仅依赖 JDK 的 [OGBS Game Runtime v0.1 草案](docs/OGBS%20Game%20Runtime%20Specification%20v0.1.md)实现。Command、Event、Cron、Dynamic Timer 和 Callback 统一通过 Route Runtime 执行业务。

## 构建与示例

在仓库根目录运行：

~~~sh
mvn -pl game-runtime verify
java -cp "game-runtime/target/classes;game-runtime/target/test-classes" cn.managame.runtime.execution.RuntimeExample
~~~

上述示例使用 Windows 类路径分隔符；Linux/macOS 请改用 `:`。也可以在 IDE 中使用 JDK 25 运行 [RuntimeExample](src/test/java/cn/managame/runtime/execution/RuntimeExample.java)。示例包含 Command 路由、同步 Event、Callback 重新进入 Route 和测试时钟控制的 Timer。

执行 `mvn -pl game-runtime install` 后引入：

~~~xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-runtime</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
~~~

## Route 执行

游戏项目定义类型标记，无需实例化或单独注册：

~~~java
public final class GameRoutes {
    private GameRoutes() {}
    public interface Player extends RouteType {}
    public interface Guild extends RouteType {}
    public interface Rank extends RouteType {}
}
~~~

类型按精确 Class 身份区分；同名或继承关系不会合并队列。串行保证限定在同一个 GameRuntime 内，类型不提供实时调度保证。固定枚举 API 已移除，详见[路由类型约定与迁移](docs/route-types.md)；该说明替代原始 v0.1 草案的固定分组约定。

Route 由 `Class<? extends RouteType> + long routeKey` 确定，类型由游戏项目定义，Runtime 不内置业务分类。每个实体拥有独立 FIFO 队列，由执行域内的工作线程按批次串行执行；默认 BALANCED 策略下批次之间可以换线程，KEY_AFFINITY 策略则按 key 固定分配平台线程。Route 队列按可配置的分片管理，各分片使用独立锁，不同 Route 可以并发。空队列会被回收。

~~~java
try (GameRuntime runtime = GameRuntime.builder().build()) {
    RouteTask task = runtime.dispatch(GameRoutes.Player.class, 10001, () -> {
        HandlerContext context = HandlerContexts.current();
        // 同步调用或虚拟线程阻塞期间，逻辑 Route 仍由当前任务占用。
    });
    task.join();
}
~~~

普通 dispatch 始终入队，即使目标就是当前 Route。平台线程 Handler 不得通过 `RouteTask.join/get` 等待任何未完成的 RouteTask；虚拟线程 Handler 不得等待当前执行域内未完成的 RouteTask。已完成结果和 Handler 外部等待不受此限制。跨域循环依赖及第三方 Future 等待仍需业务避免。

业务方法返回 `void`，返回前必须完成本次调用中的状态访问。脱离当前调用的异步任务，需要通过 Callback 或 dispatch 重新进入 Runtime。Runtime 保证业务顺序，不保证固定物理线程。

`HandlerContexts.current()` 基于 JDK 25 `ScopedValue` 提供动态作用域，在调用之外访问会报错；可用 `currentOrNull()` 进行可选查询。嵌套 Event 执行后会恢复外层 Context。Context 不作为业务方法参数注入。

业务类型可绑定独立的平台线程或虚拟线程执行域，配置资源隔离、实体批次轮转和独立任务额度。房间可选择 KEY_AFFINITY 固定线程分片；玩家可选择 BALANCED 动态分配工作线程。路由 ID 不依赖方法参数位置，也可以不出现在方法签名中。详见[执行域与实体队列](docs/execution-domains.md)。未配置执行域时使用兼容的默认虚拟线程域；显式配置后，所有使用的类型都必须绑定。

执行域还支持自定义 RouteDispatcher 后端，包含独立的 Disruptor 适配模块。close(Duration) 提供限时关闭报告；无参 close() 默认等待最多 30 秒，未完成时抛出 RuntimeShutdownException。详见[扩展接口、等待保护与关闭](docs/dispatchers.md)。

## 协议与 Command 绑定

显式注册协议；Request 和 Response 可以使用相同或不同协议号：

~~~java
ProtocolRegistry protocols = ProtocolRegistry.builder()
    .register(1001, ProtocolType.REQUEST, UseItemReq.class)
    .build();

ParameterResolverRegistry parameters = ParameterResolverRegistry.builder()
    .registerRouteSource(RoleId.class,
        invocation -> ((Session) invocation.connection()).roleId())
    .build();

@Handler(routeType = GameRoutes.Player.class)
public class Items {
    @HandlerMethod
    public void use(UseItemReq request, RoleId roleId) {
        CommandContext context = (CommandContext) HandlerContexts.current();
        // 通过应用自己的协议组件显式回复。
    }
}

GameRuntime runtime = GameRuntime.builder()
    .protocols(protocols).parameters(parameters)
    .defaultRoute(GameRoutes.Player.class, RoleId.class, RoleId::value)
    .handler(new Items()).build();

runtime.command(new UseItemReq(7), session, 41);
~~~

requestId 是 CommandHandlerInvocation 的固定字段，通过 CommandContext.requestId() 访问。需要扩展信息时传入 new CommandHandlerInvocation(request, connection, requestId, metadata)；不在 Metadata 中重复保存请求号。零表示本地或单向命令，无关联编号的旧构造函数和 command(request, connection) 默认使用 0，负值被拒绝。解析、准入和执行失败都保留原 invocation，可在 Context 尚未创建时读取请求号。

导入和业务类型定义见完整示例。Runtime 只需登记执行的命令，响应类型、编解码器及请求响应关系由应用消息绑定维护。旧 response(...) 和 responseTo(...) API 标记为弃用，仅保留迁移兼容，命令执行不使用这些关系。

Command 方法必须为 public、非 static、非变长参数，返回 void，并恰好接受一个已注册的 REQUEST 参数。其他参数按精确声明的类型绑定。`ParameterResolver<T>` 始终接收 `CommandHandlerInvocation`，通过 `register(ValueType.class, resolver)` 或 `registerRouteSource(ValueType.class, resolver)` 注册。同一语义类型重复注册立即失败，普通注册与路由源注册也不能重复。普通解析器在活跃 Route 内执行，可以通过应用服务访问该 Route 的业务状态。Event 直接接收事件，不使用该参数注册表。

路由解析器采用显式源类型和实例注册。可用 `.routeKeyResolver(Source.class, resolver)` 配合 `@Handler(routeKeyResolver = Resolver.class)` 指定解析策略，或使用 `.defaultRoute(routeType, Source.class, resolver)` 提供类型默认解析策略。Route Source 可以是 Request、语义参数，也可以是不出现在方法参数中的预绑定语义值。语义路由源必须用 `registerRouteSource(...)` 注册，普通状态解析器被用作路由源时会在初始化阶段报错。只有选中的路由源参数槽在准入之前解析，其实现必须线程安全、不阻塞，仅读取不可变的请求或会话身份，不能访问受 Route 保护的业务状态。RouteKeyResolver 同样受此限制。其余参数在任务进入 Route 后解析。路由与参数共用同一语义值时只解析一次并复用。顺序从 Route 准入开始保证，并发调用方仅仅进入 `command()` 并不意味着已经获得执行顺序。Java 基本类型参数由对应包装类型结果注入。

`build()` 扫描传入的实例并验证绑定。热路径仅查询不可变注册表、调用预绑定解析器、填充参数槽并通过 MethodHandle 调用业务，不重新扫描注解或搜索解析器。依赖注入与对象构造由应用负责。可通过 `protocols()`、`commands()`、`events()` 访问注册表。
初始化由 `HandlerBinder` 组织：`HandlerMethodScanner` 查找入口，Binder 校验并绑定协议、参数和路由，`HandlerInvokers` 一次性适配调用签名。本模块不生成动态 Handler 类或字节码。Cron/Event 分别使用无参和单参数调用器；1～3 参数 Command 使用固定参数数量的调用器，不构造调用参数数组；更多参数使用一个数组，并在原位复用重复语义值。这不代表完整消息处理没有其他分配。详见[基准方法](benchmarks/README.zh-CN.md)和[实测结果](benchmarks/RESULTS.md)。

## Event

Event 是普通业务对象，通过 `.eventType(MyEvent.class)` 显式声明，无须继承 Runtime 类型或实现 Runtime 接口。

~~~java
@EventHandler(routeType = GameRoutes.Player.class,
              routeKeyResolver = ItemEventRoute.class)
public class ItemEvents {
    @EventMethod(order = 0)
    public void onUsed(ItemUsed event) {
        // 只声明一个参数，类型必须是已注册的 Event。
    }
}
~~~

在 Builder 中注册事件类型、路由解析器源类型及实例、Handler 实例。解析器可以接受 Event 的业务父类。Subscriber 按发布对象的精确类型查找，注册父类事件不会自动订阅全部子类。

`runtime.publish(event)` 按 `order` 升序处理 Subscriber。同 order 时按 Handler 注册顺序、随后按确定的方法签名顺序处理。同一 Runtime 中与当前 Route 完全一致的 Subscriber 同步执行，拥有独立的 Context；事件对象直接传入，不再构造 EventHandlerInvocation 包装；其他 Subscriber 入队，发布方不等待完成。Runtime 外发布时全部入队。Subscriber 执行或路由解析失败独立报告，不阻止其余 Subscriber。顺序只约束处理和提交，不约束跨 Route 的完成时间。

## 时钟、Cron 和 Timer

默认使用 UTC 系统时钟。可通过 `.clock(GameClock.system(zone))` 配置时区。`GameClock` 提供日历时间 `now()`、`nowMillis()`、`zoneId()`，以及独立的单调时间 `nanoTime()`。自定义时钟未覆盖 `nanoTime()` 时默认使用 `System.nanoTime()`。

~~~java
@Cron(value = "0 */5 * * * ?", routeType = GameRoutes.Rank.class, routeKey = 1)
public void refreshRank() {
    // 通过 GameRoutes.Rank.class + 1 执行，不在 Scheduler 线程上运行业务。
}

cn.managame.runtime.execution.TimerTask timer = runtime.schedule(
    GameRoutes.Player.class, 10001, Duration.ofSeconds(5), () -> refresh());
timer.cancel();
~~~

Cron 方法必须 public、非 static、返回 void 且无参数。Cron 使用静态 long RouteKey，不使用 RouteKeyResolver。

Cron 支持六字段：秒、分、时、月内日、月、星期。支持通配符、列表、范围、步长、英文月份及星期缩写；星期采用 Quartz 编号 SUN=1 至 SAT=7。两个日期字段中必须恰有一个为 `?`。可选年份和 Quartz 的 `L`、`W`、`#` 扩展不支持，会在初始化时拒绝。不可能出现的日期也会被拒绝。日历计算使用配置时区，处理夏令时跳过与重复时段。

只有注册了业务 Timer/Cron 时，Scheduler 才每 10ms 轮询统一时钟；普通实体任务调度不依赖它。Scheduler 仅负责向 Route 提交任务。日历任务和相对延迟任务分别保存在有序集合中，共用定时容量和每次轮询预算；单任务取消为 O(log N)。每次轮询提取数量有上限的批次；Cron 计算、Route 投递和异常观察器都在定时队列锁之外执行。时钟向前跳跃时，每次轮询把每个 Cron 错过的触发合并为一次，随后计算当前时间之后的下一次触发；计算期间保留该 Cron 的定时容量槽位；向后调整不会重放已提交的触发。`schedule(..., Duration, ...)` 使用单调时间，调整日历时间不会改变等待时长；`scheduleAt(..., Instant, ...)` 和 Cron 使用日历时间。轮询间隔和 Route 排队可能推迟实际执行，不提供硬实时保证。

Timer 在 Scheduler 中等待，不占用 Route。取消原子地执行 `SCHEDULED -> CANCELLED` 并移除待触发任务。到期认领后进入 `DISPATCHING`，此时 cancel 返回 false；准入成功变成 `DISPATCHED`，准入失败变成 `REJECTED`。`timer.completion()` 是稳定的 RouteTask：业务返回后成功，投递或业务异常时失败，取消时以 CancellationException 完成；可通过 onComplete 在指定 Route 接收结果。DISPATCHED 只表示已接受，业务结果以 completion 为准。已经接受的 RouteTask 不支持取消。

确定性测试示例：

~~~java
MutableGameClock clock = new MutableGameClock(
    Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
try (GameRuntime runtime = GameRuntime.builder()
        .clock(clock).automaticScheduling(false).build()) {
    runtime.schedule(GameRoutes.Player.class, 1, Duration.ofSeconds(5), () -> refresh());
    clock.advance(Duration.ofSeconds(5));
    runtime.runDueTimers(); // 提交到期任务，不等待业务执行完成。
    runtime.dispatch(GameRoutes.Player.class, 1, () -> {}).join();
}
~~~

## Callback 与 RPC 对接

在 Handler 内创建 Callback，捕获当前 Route 和传播后的 Metadata：

~~~java
RuntimeCallback<MyResponse> callback = runtime.callback(
    body -> applyResponse(body),
    failure -> handleFailure(failure));
// 之后由传输完成线程调用：
callback.onSuccess(decodedResponse);
// 或 callback.onFail(originalFailure)。
~~~

成功和失败入口均通过 Callback 保留容量投递业务回调，只有一次成功接受的通知会执行。`isSignalled()` 表示通知已被认领，提交尚未结束时就可能为 true，不代表业务回调已经执行。`completion()` 返回稳定的 `RouteTask`，表示回调业务完成或失败。旧 `isCompleted()` 已弃用，仍保留原来的通知状态含义。容量等可重试的准入拒绝会报告并抛出 HandlerException，将 isSignalled() 重置为 false，completion() 保持未完成，适配层可显式重试。Runtime 永久关闭时抛出以 RuntimeClosedException 为 cause 的 HandlerException，completion() 明确失败且不再允许重试；非拒绝类后端故障也按终态失败处理。调用方放弃重试时，可调用 abort(rejection) 使原 completion 明确失败，不执行成功或失败业务回调。尚未通知时也可 abort；已有通知或终止操作占有回调时返回 false，不能取消已接受的通知。完成监听器仍遵守路由准入。Runtime 不保留被拒绝的响应，也不自动重试。另一线程正在提交通知时，竞争调用可能返回 false。普通 Lambda API 继承当前 Route；完整 `CallbackDefinition<T>` 允许覆盖 Route，也可用于 Handler 外创建 Callback。

失败处理器现在接收 Throwable。onFail(Throwable) 保留原始异常，包括受检异常、应用错误详情及 cause；未配置失败处理器时，以原始异常为 cause 构造 HandlerException，报告并使 completion() 失败。旧 onFail(int) 已弃用，仅作为包装 CallbackFailureException 的兼容入口；原整数失败处理器需要迁移为异常处理器。显式失败处理器在 Callback 路由上接管错误。Callback 保留路由和传播后的 Metadata，Context 是普通 `HandlerContext`。异步回复时，业务在 Command Handler 中捕获连接和请求关联信息，再在回调中显式发送。

Runtime 原样传递正整数失败码，不再定义 RPC Wire 错误码常量。错误码空间由传输或应用组件管理。对接 game-rpc 时使用其 `RpcError.TIMEOUT.code()` 等定义；本地 `RuntimeOverloadedException` 到 Wire 错误码的映射由适配层负责。

本模块不依赖 RPC 或 Netty。对接 game-rpc 时，由适配层解码响应 Envelope，再调用 `RuntimeCallback.onSuccess` 传入响应对象，或调用 `onFail` 传入错误码。game-rpc 回调直接运行在完成线程，响应可能包含借用的缓冲区；适配层必须提供独立拥有的解码对象，或正确管理异步交接时的引用保留与释放。Handler 完成不会自动发送协议响应。

## 任务完成通知

在 Handler 内调用 `onComplete(listener)`，会捕获调用方在同一 Runtime 中的当前 Route 和 Metadata：

~~~java
// 当前处于 Player Handler。
RouteTask guildTask = runtime.dispatch(GameRoutes.Guild.class, guildId, () -> updateGuild());
RouteTask notification = guildTask.onComplete(failure -> {
    if (failure == null) updatePlayer();
    else handleGuildFailure(failure);
});
~~~

成功时 listener 接收 null，失败时接收原任务异常。内部完成通知以迭代方式投递，长链连续拒绝不会通过递归耗尽调用栈；业务 listener 仍进入目标 Route 执行。即使原任务已经完成，listener 仍重新入队，不内联执行。Handler 外可显式指定 `task.onComplete(new Route(GameRoutes.Player.class, playerId), listener)`。Callback 的 `completion()` 同样支持该接口。

返回的 RouteTask 表示监听器执行完成，和原任务的完成状态独立。完成通知使用 Callback 准入额度；拒绝时报告异常并使该通知任务失败，不自动重试。注册监听器不预留准入容量，因此关闭开始后才完成的原任务，其监听器投递可能被拒绝。从未收到通知的 RuntimeCallback 不计入已准入业务任务，其 completion 仍等待适配层通知；适配层需要在成功、超时或传输关闭时最终调用 onSuccess/onFail。关闭后实际尝试的通知会让 completion 明确失败。

Timer 的结果、两种时钟、进程关闭协调和线上诊断详见[定时结果与诊断](docs/timers-and-diagnostics.md)。

## Command API 迁移

已删除隐式回调回复机制，包括 `InvocationOrigin` 和 `ResponseTarget`。构造方式改为 `new CommandHandlerInvocation(request, connection, requestId, metadata)` 和 `new CommandContext(route, protocol, invocation)`。调用方移除原来的最后一个 target 参数及 `responseTarget()` 访问，将回复逻辑放入显式回调，使用业务捕获的连接和请求关联信息。Runtime 不再向原 Command 连接转发 Callback 错误。

## Metadata、异常与关闭

`Metadata` 是基于稀疏数组的不可变键值集合。Key ID 为 uint16：0..199 框架保留，200..65535 由业务定义。`MetadataKey.application(id, type)` 检查业务范围。值类型为 `Integer`、`Long`、`String`。业务应统一定义每个 ID；同一 Metadata 中同 ID 的类型冲突会立即报错。

`metadata.with(key, value)` 返回新值。默认 `MetadataPropagator.copy()` 安全共享不可变 Metadata；通过 `.metadataPropagator(...)` 自定义子调用需要传播的字段。Event、Callback、普通 dispatch 和 Timer 传播 Metadata；Cron 使用空 Metadata。

`exceptionHandler(...)` 接收包含 source、可选 Context 和 cause 的统一 `HandlerException`。命令失败还保留 invocation() 及 commandStage()（RESOLUTION、ADMISSION、EXECUTION）；解析失败可能没有 Context，准入失败保留已经解析出的命令 Context。每次命令失败只报告一次，提交处 catch 不应再次回复。Runtime 创建的 CommandContext 也提供原 invocation。路由身份解析或 Command 准入失败会报告并同步抛出；普通参数解析发生在 Invocation 内，解析和业务执行失败会使返回的 `RouteTask` 失败。Event Subscriber 独立处理异常；Cron、Timer 和 Callback 异常统一报告，之后继续处理 Route 队列。异常观察器应及时返回；观察器自身失败会记录日志，不阻塞后续 Route 任务。

`close(Duration)` 停止准入、取消尚未触发的 Timer/Cron，并在一个共享截止时间内等待已接受任务。返回的 `ShutdownReport` 包含剩余 Route、执行域和 `backendFailures()`；每个后端错误包含域名、操作和 cause。某个后端关闭或终止检查抛异常，不会阻断其他域清理；报告存在后端错误时 `terminated()` 为 false。每个后端只尝试一次 shutdown，错误保留在后续报告中；再次 close 可以等待正常超时任务继续完成，不会修复故障后端。无参 `close()` 默认最多等待 30 秒，可通过 Builder 配置，未完整关闭时抛出携带报告的 `RuntimeShutdownException`。两种方法都应在 Handler 外调用。超时不打断业务，也不释放实体所有权，已接受的批次继续执行；尚未投递的完成监听器和迟到的 Callback 通知属于新提交，关闭开始后可能被拒绝。

传输、编解码、服务发现、持久化、依赖注入和进程生命周期编排不属于本模块。

## 容量、过载与指标

执行域任务容量和业务定时器分别配置，默认值如下：

~~~java
DomainLimits capacity = new DomainLimits(
    100_000, // 每个域的普通任务上限
    4096,    // 每个 Route 的普通任务上限
    10_000,  // 每个域的额外 Callback 保留槽位
    64       // 每个 Route 的额外 Callback 保留槽位
);
TimerOptions timers = new TimerOptions(100_000, 1024); // Timer 容量、每次轮询条目预算

// 没有显式执行域映射时，配置默认域。
GameRuntime runtime = GameRuntime.builder()
    .domainLimits(capacity).routeShards(64).timerOptions(timers).build();

// 显式执行域自行配置容量与队列管理分片。
var players = ExecutionDomain.platform("players").threads(4)
    .limits(capacity).routeShards(64).build();
~~~

`GameRuntime.Builder.domainLimits` 和 `routeShards` 只配置默认域；显式映射使用各自 `ExecutionDomain.Builder` 的设置。`timerOptions` 始终是 Runtime 全局业务定时器配置，执行域不包含 Timer 字段。分片数只控制队列锁，与工作线程数独立。旧 `GameRuntime.Builder.limits(RuntimeLimits)` 桥已弃用，会将全部七个字段分别应用到对应配置，后续 Builder 调用覆盖相应字段。

以上默认值不是经压测得出的容量建议。任务上限同时计算运行中和排队任务。Command、普通 dispatch、跨 Route Event、Cron、Timer 使用普通容量；Callback 还能使用额外保留槽位，并继续遵守 Route 的 FIFO 顺序。Inline Event 属于当前任务，不额外占用槽位。

普通 dispatch 或 Timer 注册满载时抛出 `RuntimeOverloadedException`，原因是 `GLOBAL_CAPACITY`（兼容默认域）、`DOMAIN_CAPACITY`（显式执行域）、`ROUTE_CAPACITY` 或 `TIMER_CAPACITY`。Command 准入将其包装为 `HandlerException`。跨 Route Event、到期 Timer/Cron 拒绝会报告异常处理器，不阻止其余 Subscriber。被拒绝的触发不自动重试，周期 Cron 仍保留下一次触发。可重试的 Callback 通知拒绝还会向调用方抛出异常，并重置通知标记，支持适配层显式重试；永久关闭或非拒绝类后端故障则终态失败；RouteTask 完成通知被拒绝时，则使返回的通知任务失败，不自动重试。保留容量也耗尽时，由适配层选择重试、立即失败或向上游施加背压；保留槽位不能保证任意数量的未完成 RPC 都能被接受。

取消释放 Timer 容量，任务完成或失败释放任务容量。Cron 在计算下一次触发期间仍保留一个定时槽位；注册数超过定时容量时初始化失败。

`RuntimeMetrics` 提供当前未完成任务、活跃 Route、定时任务数量，成功/失败任务数，拒绝次数（包含 Timer 注册），以及排队和执行时长的累计值与最大值。并发读取为近似快照，时长采用单调纳秒计时，不受可调整业务时钟影响。

`runDueTimers()` 每次最多处理 `maxTimersPerPoll` 个到期条目，已有轮询执行时返回零；异常观察器重入调用也返回零而不阻塞，此时零不代表队列为空。确定性测试应关闭自动调度，完成每次轮询后再调整时钟。

## 从初始 v0.1 实现迁移

语义路由源使用 `registerRouteSource(Type.class, resolver)`，其他 Command 参数使用 `register(Type.class, resolver)`。注册接口已移除 Invocation Class 首参数，`ParameterResolver<I,T>` 收窄为始终接收 Command 的 `ParameterResolver<T>`。容量改用 `DomainLimits`、独立的 `routeShards` 和 `TimerOptions`；Runtime 全局旧配置桥已弃用，ExecutionDomain 不再接受 RuntimeLimits。自定义后端必须实现可靠续批的 `reschedule`。旧 Callback `isCompleted()` 按意图改为 `isSignalled()` 或 `completion().isDone()`。调用方迁移后需重新编译 0.1.0-SNAPSHOT。已移除的 `cn.managame.runtime.RpcErrorCode` 仍由上层 RPC/业务组件替代。

## 验证

`mvn -pl game-runtime verify` 验证并发提交顺序、虚拟线程等待、同 Route 等待拒绝、Context 恢复、解析器绑定与初始化失败、协议关系、事件分发及异常隔离、Timer 取消、时钟跳跃、Cron/夏令时、Callback 路由与失败传播。架构回归还覆盖多层 Callback 路由与 Metadata 传递、Callback 保留容量与拒绝后重试、Route 内业务参数解析、并发全局准入、分片 FIFO、关闭竞争、Cron 容量保留、锁外观察器调用、泛型覆盖、可靠续批、完成通知和逐域关闭故障隔离。这些是正确性测试，不是容量基准测试。

### 基础设施完成状态观察

`RouteTask.observeCompletion(observer)` 向基础设施清理或失败通知代码报告终态：成功参数为 `null`，失败参数为原始异常，也能观察后端故障。提前登记的观察器在完成线程执行，终态后登记的观察器在登记线程执行；均在完成状态锁外调用，必须及时返回，不保证 Route 或 HandlerContext。观察器异常与任务和其他观察器隔离。路由业务通知继续使用 `onComplete`。RouteTask 使用显式终态和 CountDownLatch 等待，不提供任意线程上的续接链。

适配器已持有路由和 Metadata 时，可调用 `runtime.callback(targetRoute, metadata, onSuccess, onFailure)`，仍使用 Callback 准入预留。`runtime.dispatch(routeType, key, metadata, action)` 通过普通任务准入接收显式上下文数据。这两个重载原样使用传入 Metadata，不读取当前 Context，也不执行 MetadataPropagator；原有重载继续保留上下文传播行为。
