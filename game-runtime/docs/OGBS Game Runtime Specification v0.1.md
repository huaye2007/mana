# Open Game Backend Specification
## Game Runtime Specification

**Version:** 0.1 Draft  
**Status:** Draft

---

# 1. 目标

OGBS Game Runtime 定义游戏服务器进程内部核心业务逻辑的统一执行模型。

核心目标：

- 定义游戏业务的统一执行入口。
- 保证相同业务实体相关操作严格有序。
- 统一 Command、Event、Cron、Dynamic Timer、Callback 的执行语义。
- 定义 HandlerContext、Metadata、时间等基础 Runtime 能力。
- 定义 ParameterResolver 与 RouteKeyResolver 的职责。
- 保证 Java、Rust、Go 等不同语言实现具有一致的运行语义。

OGBS 定义的是：

> **Semantic Compatibility**

而不是不同语言完全一样的 API。

---

# 2. 非目标

Game Runtime 不负责：

```text
TCP / WebSocket / KCP / UDP
Connection Transport
Codec
RPC Wire Protocol
Service Discovery
Database Persistence
Deployment
Configuration Center
Dependency Injection
Process Lifecycle
```

这些由其他 Foundation Component 负责。

---

# 3. 核心模型

Game Runtime 最核心的执行标识为：

```text
RouteGroup + RouteKey
```

定义：

```text
Route = RouteGroup + RouteKey
```

例如：

```text
PERSONAL + 100001
SHARED   + 50001
REALTIME + 80001
```

其中：

- `RouteGroup` 表示执行类别。
- `RouteKey` 表示该类别下需要保证顺序性的逻辑实体。

---

# 4. RouteKey

RouteKey 统一使用 64-bit signed integer。

Java：

```java
long
```

Rust：

```rust
i64
```

Go：

```go
int64
```

第一版不引入额外的 `RouteKey` 对象。

业务 ID 与 RouteKey 是不同概念：

```text
RoleId
GuildId
RoomId
BattleId
```

通过 RouteKeyResolver 转换：

```text
GuildId
   ↓
RouteKeyResolver
   ↓
long RouteKey
```

业务 ID 不应直接实现某个 RouteKey 类型。

---

# 5. RouteGroup

v0.1 固定提供：

```text
LOGIN
PERSONAL
SHARED
REALTIME
```

第一版不支持业务自定义 RouteGroup。

---

## 5.1 LOGIN

用于：

```text
登录
角色加载
角色初始化
进入游戏前的数据准备
```

此类业务通常 I/O 较多。

实现可以使用：

```text
Virtual Thread
Coroutine
Async Runtime
```

---

## 5.2 PERSONAL

用于玩家个人状态：

```text
背包
任务
属性
养成
邮件
个人活动
```

通常业务 RouteKey 来源于角色 ID。

但是 OGBS 不在公共 API 中硬编码 `roleId`。

PERSONAL SHOULD 提供项目级默认 RouteKey 解析策略，使普通 Handler 无需重复声明 Resolver。

---

## 5.3 SHARED

用于共享业务状态：

```text
Guild
Rank
World State
Shared Activity
Alliance
```

RouteKey 可能来自：

```text
guildId
rankId
activityId
```

通常显式配置 RouteKeyResolver。

---

## 5.4 REALTIME

用于实时业务：

```text
Room
Scene
Battle
Tick
Realtime Simulation
```

应尽量避免：

```text
Blocking IO
Long-running Task
Heavy External Request
```

具体执行线程模型由实现决定。

---

# 6. Route 严格顺序

完全相同的：

```text
RouteGroup + RouteKey
```

必须严格顺序执行。

提交：

```text
A
B
C
```

必须：

```text
A complete
    ↓
B start
    ↓
B complete
    ↓
C start
```

同一个 Route 任意时刻只能存在一个 Active Route Task。

---

# 7. Suspend / Await

业务发生：

```text
await
suspend
virtual-thread blocking
coroutine suspension
```

时：

> 可以释放物理线程，但是不能释放逻辑 Route。

例如：

```text
PERSONAL + 10001

Handler A
    ↓
await database
```

物理线程可以执行其他 Route。

但：

```text
PERSONAL + 10001
```

仍然处于 Busy 状态。

必须等 A 完整结束以后才执行该 Route 的下一任务。

因此：

```text
Route Affinity != Thread Affinity
```

---

# 8. Thread Affinity

OGBS 不要求同一个 Route 永远运行在相同物理线程。

合法：

```text
A -> Thread 1
B -> Thread 5
C -> Thread 2
```

只需要保证：

```text
A complete before B
B complete before C
```

---

# 9. Same Route Dispatch

当前：

```text
PERSONAL + 10001
```

正在执行 A。

A 主动：

```text
dispatch(PERSONAL, 10001, B)
```

B 必须进入 Route Queue。

不得普通同步重入。

如果业务希望立即调用另一段逻辑，应使用普通方法调用。

---

# 10. 禁止等待自己的 Route

以下行为可能死锁：

```text
A
 ↓
submit B to same Route
 ↓
wait B
```

因为：

```text
B 等 A
A 等 B
```

Runtime SHOULD 在能够检测时直接报告错误。

---

# 11. Handler

Handler 表示具有共同 Route 语义的一组业务方法。

Java 示例：

```java
@Handler(
    routeGroup = RouteGroup.PERSONAL
)
public class ItemHandler {

    @HandlerMethod
    public void useItem(
        RoleId roleId,
        UseItemReq req
    ) {
    }
}
```

统一术语：

```text
Handler
HandlerMethod
HandlerInvocation
```

---

# 12. HandlerMethod

HandlerMethod 是具体业务入口。

Command HandlerMethod 返回值必须为：

```text
void
```

例如：

```java
@HandlerMethod
public void useItem(
    RoleId roleId,
    UseItemReq req
) {
}
```

不通过：

```java
return UseItemRes;
```

自动响应协议。

原因是：

> HandlerMethod 完成和协议响应是两个不同概念。

业务需要能够自己控制：

```text
什么时候响应
响应哪条消息
是否响应
响应顺序
```

具体：

```text
send()
reply()
respond()
```

由业务或上层协议组件定义，不属于 Game Runtime 标准。

---

# 13. HandlerInvocation

一次具体业务执行称为：

```text
HandlerInvocation
```

逻辑结构：

```text
HandlerInvocation
├── HandlerMethod
├── HandlerContext
└── Invocation-specific Data
```

只有真正具有额外语义时才创建专门 Invocation。

不因为触发来源不同机械创建大量类型。

---

# 14. HandlerContext

基础 Context：

```text
HandlerContext
├── RouteGroup routeGroup
├── long routeKey
└── Metadata metadata
```

HandlerContext 表示：

> 当前业务调用运行在哪个 Runtime 环境。

它不是业务参数容器。

---

# 15. Current HandlerContext

业务需要 Runtime Context 时：

```java
HandlerContext context =
    HandlerContexts.current();
```

HandlerContext 不作为 HandlerMethod 参数注入。

因此不推荐：

```java
public void xxx(
    HandlerContext context,
    XxxReq req
)
```

而是：

```java
public void xxx(XxxReq req) {
    HandlerContext context =
        HandlerContexts.current();
}
```

---

# 16. CommandContext

Command Invocation 可以拥有：

```text
CommandContext extends HandlerContext
├── command
└── connection
```

Request 对象不属于 Context。

Request 属于 Invocation Payload。

---

# 17. Protocol

协议类型：

```text
REQUEST
RESPONSE
NOTIFY
```

Req、Res、Notify 分别拥有独立协议定义以及协议号。

例如：

```text
GetRoleReq
GetRoleRes
RoleChangedNotify
```

---

# 18. Req / Res 协议号

Request 和对应 Response：

可以共用协议号：

```text
GetRoleReq = 1001
GetRoleRes = 1001
```

也可以使用不同协议号：

```text
GetRoleReq = 1001
GetRoleRes = 1002
```

OGBS 不假定二者一定相同或一定不同。

---

# 19. Protocol Naming Convention

推荐：

```text
<Name>Req
<Name>Res
<Name>Notify
```

例如：

```text
LoginReq
LoginRes

UseItemReq
UseItemRes

RoleLevelUpNotify
```

初始化工具可以利用命名规则建立 Req / Res 关系。

运行时不得通过字符串拼接动态猜测协议。

---

# 20. ProtocolRegistry

所有协议元数据统一通过：

```text
ProtocolRegistry
```

查询。

Registry 必须能够确定：

```text
Message Type -> Protocol
Protocol Identity -> Message Type
Request -> Response
Request Protocol -> Response Protocol
```

由于 Req / Res 可以使用相同整数协议号，因此裸 `protocolId` 不一定能够唯一确定 Message Type。

ProtocolRegistry 必须结合 ProtocolType 或协议上下文无歧义查询。

---

# 21. CommandRegistry

ProtocolRegistry 描述：

> 协议是什么。

CommandRegistry 描述：

> 当前进程谁处理这个协议。

HandlerMethod 不重复声明 Protocol ID。

禁止：

```java
@HandlerMethod(command = 1001)
```

使用：

```java
@HandlerMethod
public void getRole(GetRoleReq req) {
}
```

初始化阶段通过 Request Type 查询 ProtocolRegistry，完成绑定。

---

# 22. ParameterResolver

业务方法可以声明语义参数：

```java
@HandlerMethod
public void useItem(
    RoleId roleId,
    UseItemReq req
) {
}
```

`RoleId` 由业务定义。

Runtime 通过 ParameterResolver 注入。

逻辑匹配：

```text
Invocation Type + Parameter Type
        ↓
ParameterResolver
```

例如：

```text
CommandHandlerInvocation + RoleId
    -> CommandRoleIdResolver
```

---

# 23. ParameterResolver Binding

Resolver 查找必须在：

```text
compile time
```

或：

```text
runtime initialization
```

完成。

运行热路径不得动态搜索 ResolverRegistry。

以下问题初始化直接失败：

```text
Resolver missing
Resolver conflict
Resolver ambiguous
Unsupported parameter
```

---

# 24. Trigger Payload

Framework 自带 Payload 不需要 ParameterResolver。

Command：

```text
CommandHandlerInvocation.request
```

直接绑定 Request 参数。

Event：

```text
EventHandlerInvocation.event
```

直接绑定 EventMethod 唯一参数。

---

# 25. RouteKeyResolver

ParameterResolver 与 RouteKeyResolver 职责不同。

ParameterResolver：

```text
Invocation
   ↓
RoleId
```

RouteKeyResolver：

```text
RoleId
   ↓
long RouteKey
```

概念：

```text
RouteKeyResolver<T>
    T -> long
```

RouteKeyResolver 不关心业务值来自：

```text
Command
Event
Session
其他 Runtime Source
```

---

# 26. Resolve Once

如果同一个语义值同时用于：

```text
RouteKey Resolution
HandlerMethod Argument
```

SHOULD 只解析一次。

Runtime 可以预绑定 Argument Slot。

不建议热路径使用：

```text
Map<Class<?>, Object>
```

保存参数。

---

# 27. EventHandler

Event 独立于 Command Handler。

例如：

```java
@EventHandler(
    routeGroup = RouteGroup.PERSONAL,
    routeKeyResolver = RoleEventRouteKeyResolver.class
)
public class RoleEventHandler {

    @EventMethod
    public void onLevelUp(
        RoleLevelUpEvent event
    ) {
    }
}
```

---

# 28. EventMethod

`EventMethod` 必须：

- 返回 `void`。
- 声明且只声明一个参数。
- 该参数必须是 Event 对象。

标准形式：

```java
@EventMethod
public void onLevelUp(
    RoleLevelUpEvent event
) {
}
```

禁止为了 Route 解析而要求：

```java
@EventMethod
public void onLevelUp(
    RoleId roleId,
    RoleLevelUpEvent event
) {
}
```

Route Source 与 EventMethod 参数绑定完全独立。

---

# 29. Event Route Source

Event 通常可以通过业务父类提供路由字段。

例如：

```java
public abstract class RoleEvent {

    private final long roleId;

    public long roleId() {
        return roleId;
    }
}
```

具体 Event：

```java
public final class RoleLevelUpEvent
        extends RoleEvent {
}
```

Route Resolver：

```java
public final class RoleEventRouteKeyResolver
        implements RouteKeyResolver<RoleEvent> {

    @Override
    public long resolve(RoleEvent event) {
        return event.roleId();
    }
}
```

Event 不应该实现 Runtime RouteKey 接口。

业务 Event 只提供业务数据。

---

# 30. EventRegistry

逻辑结构：

```text
Event Type
    ↓
EventMethod Binding[]
```

同一 Event 可以拥有多个 Subscriber。

`@EventMethod` 可以定义：

```text
order
```

默认：

```text
0
```

初始化阶段按照：

```text
order ASC
```

排序。

---

# 31. Event Publish

调用：

```java
eventPublisher.publish(event);
```

Runtime：

```text
Event Type
   ↓
EventRegistry
   ↓
Subscriber Bindings
   ↓
resolve each subscriber Route
```

---

# 32. Same Route Event

如果 Event Subscriber 解析出的：

```text
RouteGroup + RouteKey
```

与当前：

```text
HandlerContexts.current()
```

完全相同：

> MUST 直接同步执行 EventMethod。

例如：

```text
Current:
PERSONAL + 10001

Subscriber:
PERSONAL + 10001
```

执行：

```text
Handler A
   ↓
publish
   ↓
EventMethod
   ↓
EventMethod complete
   ↓
Handler A continue
```

不再重新投递 Route Queue。

---

# 33. Inline Event Invocation

Same Route Event 属于：

```text
Inline Event Invocation
```

它仍然是一个独立 HandlerInvocation，但不是一个新的 Route Task。

Route 外层仍然只有：

```text
Handler A
```

这一个 Active Route Task。

---

# 34. Different Route Event

如果 Subscriber Route 不同：

```text
Current:
PERSONAL + 10001

Subscriber:
SHARED + 50001
```

则：

```text
publish
   ↓
dispatch SHARED + 50001
```

当前业务不等待该 Subscriber 完成。

---

# 35. Event Without Current Context

如果 publish 发生在 Runtime Handler 之外，没有当前 HandlerContext：

所有 Event Subscriber 都正常投递各自 Route。

不存在 Same Route Inline。

---

# 36. Event Order

Event `order` 只保证：

```text
Subscriber processing/submission order
```

不保证：

```text
cross-route completion order
```

例如：

```text
A same route
B different route
C same route
```

执行：

```text
A inline
B dispatch
C inline
```

B 可能在 C 前完成，也可能在 C 后完成。

---

# 37. Event Failure

某一个 EventMethod 失败：

```text
MUST NOT
```

阻止其他 Subscriber 被处理。

失败进入统一 HandlerException 处理。

---

# 38. Cron

静态定时任务直接使用：

```text
@Cron
```

不创建：

```text
TimerHandler
TimerMethod
```

Java 示例：

```java
@Cron(
    value = "0 */5 * * * ?",
    routeGroup = RouteGroup.SHARED,
    routeKey = 1
)
public void refreshRank() {
}
```

Cron 的：

```text
cron expression
RouteGroup
RouteKey
```

初始化时必须全部确定。

RouteKey 为静态 long。

Cron 不使用 RouteKeyResolver。

---

# 39. Cron Execution

Cron Scheduler 只负责时间触发。

时间到：

```text
Cron Scheduler
    ↓
Route Dispatch
    ↓
RouteGroup + RouteKey
    ↓
Business Method
```

Scheduler Thread 不直接执行游戏业务逻辑。

---

# 40. Dynamic Timer

动态 Timer：

```java
TimerTask task = timer.schedule(
    RouteGroup.PERSONAL,
    roleId,
    Duration.ofSeconds(5),
    () -> {
    }
);
```

创建以后首先进入：

```text
Timer Scheduler
```

不是 Route Queue。

---

# 41. Dynamic Timer Execution

流程：

```text
schedule
   ↓
Timer Scheduler
   ↓
wait
   ↓
deadline
   ↓
Route Dispatch
   ↓
RouteGroup + RouteKey
   ↓
business callback
```

等待 Timer 不能占用 Route。

---

# 42. Timer Cancellation

创建 Timer 必须返回对应任务引用。

例如：

```java
TimerTask task = timer.schedule(...);

task.cancel();
```

至少支持：

```text
cancel()
isCancelled()
```

Timer 状态：

```text
SCHEDULED
   ├── cancel
   │      ↓
   │  CANCELLED
   │
   └── deadline
          ↓
      DISPATCHED
```

一旦已经 `DISPATCHED` 到 Route Runtime：

> cancel 不再保证阻止业务执行。

---

# 43. Callback

Callback 用于异步调用完成后重新进入正确 Route。

默认：

```text
Callback Route
=
创建 Callback 时当前
HandlerContext.RouteGroup + RouteKey
```

例如：

```text
PERSONAL + 10001
    ↓
rpc.call(...)
    ↓
RPC response
    ↓
PERSONAL + 10001
    ↓
callback
```

Callback 不允许直接在 RPC IO Thread 上执行游戏业务。

---

# 44. Callback Success

业务常规写法：

```java
rpc.call(req, res -> {
});
```

`res` 是已经反序列化完成的 Response Body 对象。

业务不应该看到：

```text
RpcResponse Envelope
ByteBuffer
Raw Body
```

---

# 45. Callback Failure

Runtime 的失败入口为 onFail(Throwable failure)，失败处理器接收完整异常，保留受检异常、业务参数与 cause。Runtime 不定义 RPC 或业务错误码空间。

RPC 与应用边界负责将 RPC 错误码及参数映射成应用异常。旧 onFail(int) 仅作为已弃用兼容入口，将 code 包装成 CallbackFailureException。

不单独定义 onTimeout、onNetworkError。容量拒绝可重试；决定放弃时使用 abort(failure) 使 completion 终态失败，不执行成功或失败业务回调。abort 不能取消已经接受的通知。

---
# 46. Callback API Semantics

常规：

```java
rpc.call(req, res -> {
});
```

特殊错误处理：

```java
rpc.call(
    req,
    res -> {
    },
    failure -> {
    }
);
```

没有 Fail Handler 时进入统一异常处理，并使 Callback 的 completion 失败。

存在 Fail Handler 时：

> 业务接管该 Callback 的失败处理。

---

# 47. Callback Default Failure Handling

没有显式 Fail Handler 时，无论 Callback 在 Command、Event、Timer 中创建，还是独立创建，都以原始异常为 cause 构造 HandlerException，在目标路由上交给 HandlerExceptionHandler，并使 Callback 的 completion 失败。

存在 Fail Handler 时，由它在目标路由上处理失败；处理器正常返回表示该回调已处理完毕，抛出异常则进入统一异常处理。

Callback 只携带目标 Route 和传播后的 Metadata，不隐式保存原 Command 或回复目标。业务需要异步回复时，在 Command Handler 中捕获连接和请求关联信息，并在回调中显式发送。

---

# 48. Callback Route Override

Callback 默认继承当前 Route。

特殊情况下允许完整 Callback 定义重写：

```text
RouteGroup
RouteKey
```

普通 Lambda API 不暴露这些参数。

---

# 49. Callback Captured State

回调额外业务参数使用语言本身闭包能力：

Java：

```java
long roleId = ...;

rpc.call(req, res -> {
    handle(roleId, res);
});
```

OGBS 不引入 Callback Parameter Map。

---

# 50. Metadata

HandlerContext 包含：

```text
Metadata
```

Metadata 用于：

```text
traceId
requestId
source
runtime call information
debug information
```

不用于替代：

```text
RoleId
GuildId
RoomId
```

等业务参数。

---

# 51. Metadata Key

Metadata Key 使用 uint16 ID。

范围：

```text
0 ~ 199
    OGBS / Framework Reserved

200 ~ 65535
    Application Defined
```

业务自己保证业务范围内 ID 唯一。

---

# 52. Metadata Type

v0.1 提供：

```text
INT
LONG
STRING
```

Key 的类型在定义时确定。

Entry 不重复存储 Type。

---

# 53. Metadata Representation

高性能实现推荐稀疏小数组。

Java 示例：

```java
final class Metadata {

    int size;

    short[] keys;
    long[] numbers;
    String[] strings;
}
```

由于常见 Metadata：

```text
<= 10 entries
```

线性查找通常足够。

OGBS 不强制具体内部存储。

---

# 54. Metadata Propagation

Event、Callback 通常创建新的 Metadata。

Runtime SHOULD 提供统一的：

```text
MetadataPropagator
```

概念。

例如：

```text
Parent Metadata
     ↓
Propagator
     ↓
Child Metadata
```

具体默认复制哪些字段 v0.1 暂不强制。

---

# 55. Unified Time

Runtime 与业务 SHOULD 使用统一时间 API。

业务不推荐直接使用：

```java
System.currentTimeMillis()
Instant.now()
LocalDateTime.now()
```

统一通过：

```text
GameClock
```

获取。

---

# 56. GameClock

概念能力：

```text
nowMillis
now
zoneId
```

必须支持替换时间源，方便测试。

测试环境可以：

```text
setTime
advance
```

模拟：

```text
跨天
跨周
活动开始
活动结束
Cron
每日刷新
```

---

# 57. Cron Time Source

Cron 必须使用 Runtime 统一时间和时区。

不能：

```text
Business -> GameClock
Cron -> System Clock
```

使用不同时间源。

---

# 58. HandlerException

Runtime 定义统一：

```text
HandlerException
```

用于 HandlerInvocation 执行异常边界。

可能来源：

```text
Parameter Resolution
RouteKey Resolution
Handler Invocation
Event
Cron
Timer
Callback
```

---

# 59. Exception 与 Route

如果 A 发生异常：

```text
A
 ↓
HandlerException
```

Runtime 必须：

```text
terminate A
    ↓
release Route
    ↓
continue B
```

任何业务异常都不能导致 Route 永久阻塞。

---

# 60. Initialization Validation

以下问题必须在编译期或 Runtime 初始化阶段失败：

```text
Protocol conflict
Protocol relationship invalid

Handler conflict
Invalid HandlerMethod

ParameterResolver missing
ParameterResolver duplicate
ParameterResolver ambiguous

RouteKeyResolver invalid
Route source unresolved

EventMethod parameter count != 1
EventMethod parameter is not Event
Event binding invalid

Cron expression invalid
Cron RouteGroup invalid
Cron RouteKey invalid
```

不能拖到业务第一次执行时再发现。

---

# 61. Runtime Hot Path

初始化完成后，热路径应该接近：

```text
lookup binding
    ↓
resolve pre-bound values
    ↓
resolve RouteKey
    ↓
Route Dispatch
    ↓
bind HandlerContext
    ↓
invoke method
```

SHOULD NOT 重复：

```text
annotation scanning
reflection matching
generic analysis
resolver registry searching
protocol naming inference
```

---

# 62. Trigger Unification

v0.1 Trigger：

```text
Command
Event
Cron
Dynamic Timer
Callback
```

最终统一：

```text
Trigger
   ↓
HandlerInvocation
   ↓
RouteGroup + RouteKey
   ↓
Route Runtime
   ↓
HandlerContext
   ↓
Business Logic
```

其中唯一特殊的同步嵌套规则：

```text
Event Subscriber
+
same Route as current HandlerContext
        ↓
Inline synchronous invocation
```

普通 Same Route Dispatch 仍然进入 Route Queue。

---

# 63. Cross-language Compatibility

Java、Rust、Go 实现必须保持：

```text
Route identity
Route ordering
Single active task
Suspend semantics
Event same-route inline semantics
Event different-route dispatch
Timer wait outside Route
Callback route re-entry
Parameter Resolver binding semantics
RouteKey resolution semantics
HandlerContext dynamic scope
Protocol relationship semantics
Metadata ID semantics
Unified time semantics
Exception route release semantics
```

具体线程、协程、反射、宏、代码生成方案由语言实现自行决定。

---

# 64. Core Principle

OGBS Game Runtime 的核心原则：

> **任何访问或修改核心游戏状态的独立业务执行，都必须归属明确的 RouteGroup + RouteKey。相同 Route 的独立业务 Task 必须严格顺序执行。**

以及：

> **Runtime 保证的是业务顺序，而不是物理线程亲和性。**

这构成 OGBS Game Runtime v0.1 的基础。