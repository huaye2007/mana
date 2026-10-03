# OGBS Runtime Specification 1.0

[English](OGBS-Runtime-1.0.md) | **[简体中文](OGBS-Runtime-1.0.zh-CN.md)**

文档类型：**标准规范（语言无关）**。对应实现规范：[OGBS Runtime Java 开发规范](OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md)。

状态：仓库规范草案。适用实现：mana3 `game-runtime`。本文件定义语言无关的执行语义（包含统一业务时间与显式 Cron 重排）；具体类型和注解见 [Java 25 开发规范](OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md)。

## 1. 职责与边界

Runtime 是进程内的业务执行与路由调度组件，负责 Handler 与 HTTP 方法分发、Route 串行执行、上下文绑定、本地事件、定时任务和跨 Route 调用。

Runtime 不承担网络连接、RPC Peer 选择、普通消息/请求业务编解码、登录鉴权、持久化或分布式事务。HTTP 业务结果编码属于其 HTTP 适配器。接入层负责从可信来源构造上下文，并明确选择 Route。HTTP 分发将返回或延迟完成的响应交给传输层完成能力，实际发送由传输层负责；普通消息分发仍由接入层负责回复。

共享 Metadata 与框架错误码见 [OGBS Core](OGBS-Core-1.0.zh-CN.md)。业务失败应通过业务结果表达，不能占用 Runtime 框架错误码。

## 2. Route 模型

一个 Route 的完整标识是：

```text
(runtime instance, routeDomain, routeKey)
```

| 字段 | 语义 |
| --- | --- |
| routeDomain | 进程内显式注册的正整数业务域 |
| routeKey | 非零的 64 位键；有符号语言允许负值 |
| runtime instance | 执行器和上下文所属的 Runtime 实例 |

**RT-ROUTE-01**：每个已注册 Domain 必须绑定且仅绑定一个 RouteExecutor；一个 Executor 可以服务多个 Domain。Domain 名称用于描述，不参与路由身份。

**RT-ROUTE-02**：同一 Runtime 中相同 Domain 和 Key 的任务不得并发执行。不同 Route 可以并发，但不保证一定并行。

**RT-ROUTE-03**：外部提交的已接受任务按该 Route 的实际入队顺序串行执行；并发提交方之间不建立额外的先后顺序。

**RT-ROUTE-04**：已经处于同一 Runtime、同一完整 Route 的嵌套操作直接执行，不再次排队。因此嵌套操作可能先于队列里已有的任务完成。不同 Runtime 即使 Domain 和 Key 相同，也不得据此内联。

**RT-ROUTE-05**：业务不得借用 Route 串行保证在其他线程访问该 Route 拥有的可变状态。跨 Route 返回值应使用不可变值或快照。

Route 串行不是数据库事务。一次 Handler 发起跨 Route 调用后返回，源 Route 可以执行后续任务；回调看到的是回调执行时的业务状态。

### 2.1 如何划分业务状态的归属

以玩家域 1、公会域 2 为例，玩家 42 的背包属于 Route (1, 42)，公会 42 的成员表属于 Route (2, 42)。两个 Key 数值相同并不意味着它们共用串行边界。玩家 Handler 可以直接修改本 Route 的背包；读取公会可变状态时，应进入公会 Route，并返回不可变结果。Runtime 不负责从玩家 ID 推导公会 ID，也不替业务选择 Domain。

把多个玩家放入一个 Route 可以实现共同串行，但也会让它们互相等待；把同一份可变状态分给多个 Route 则会失去串行保护。Route 的粒度是业务数据所有权决策，不是执行器线程数的别名。

### 2.2 入队顺序与内联顺序

下面的时间线解释 RT-ROUTE-03/04，A、B、C 属于同一完整 Route：

```text
A 已开始；B 已被接纳并等待
A 内部提交 C
  → C 直接执行
  → C 完成，恢复 A 的 Context
A 继续并完成
B 开始
```

因此完成顺序可能是 C、A、B。调用方不能把“同 Route 串行”理解成所有 API 都异步 FIFO，也不能在 A 内等待 B 执行完成；B 需要 A 先退出。若 C 属于另一 Runtime，即便 Domain/Key 相等，也走该 Runtime 的接纳流程。

不同 Route 可能共享线程或分片，这只影响并行程度，不改变状态归属。业务不得根据当前线程相同就直接访问另一个 Route 的状态。


## 3. 执行器与接纳

RouteExecutor 的提交结果为：

| 结果 | 含义 |
| --- | --- |
| ACCEPTED | 任务已被接纳 |
| OVERLOADED | 容量不足，任务未被接纳 |
| CLOSED | 执行器关闭，任务未被接纳 |

**RT-EXEC-01**：提交不得等待队列容量腾出。短临界区不等同于等待容量；本规范不要求无锁实现。

**RT-EXEC-02**：返回 OVERLOADED 或 CLOSED 时不得执行任务；返回 ACCEPTED 时由执行器负责执行一次。

**RT-EXEC-03**：执行器必须以完整的 Domain 和 Key 隔离串行边界。不能仅按 Key 判断属于同一 Route。

**RT-EXEC-04**：同 Route 内联由 Runtime 判断，不经过执行器容量接纳。业务应控制递归深度；内联不提供栈深保护。

**RT-EXEC-05**：存在排队或执行中任务的 Route Mailbox 必须保持活跃，不能因空闲期限或缓存容量压力被淘汰。全部工作结束后，可保留空 Mailbox 以有界复用。到期 Mailbox 不得再复用；物理回收可延迟到后续缓存访问触发维护，或在缓存容量压力下进行。缓存完全没有访问时不承诺清理截止时间。后续提交可复用或重建 Mailbox，但不得丢失已接纳任务、改变入队顺序或引入第二个并发消费者。空闲时间从最近一次排空完成后开始，不从长任务开始时计算。Mailbox 失效不取消业务操作，也不使 Route 所有的业务数据失效。

例如 Route (1,42) 的动作 A 可能运行超过空闲期限，而等待队列已空。该 Route 仍活跃，此时提交 B 必须等待 A。只有两者都返回后，空 Mailbox 才进入空闲保留。回收与 C 的提交竞争时可以丢弃空 Mailbox，但 C 仍必须在同一串行边界执行且仅执行一次。默认值和维护时机由 Java 绑定规定，它们限制资源保留，不是执行截止时间。[VirtualThreadRouteExecutorTest](../../game-runtime/src/test/java/cn/managame/runtime/executor/VirtualThreadRouteExecutorTest.java) 验证复用、空闲过期、活跃工作保护和并发交接。

本仓库提供平台线程分片队列与虚拟线程 Mailbox 两种实现。它们的容量含义不同，见 Java 开发规范。队列容量是任务接纳限制，不是内存使用的完整上限。

## 4. 构建与注册

Runtime 通过显式注册构建，不要求 classpath 扫描或依赖注入框架。

构建阶段应完成以下校验并冻结注册表：

1. Domain 标识唯一，每个 Domain 的 Executor 绑定唯一且完整。
2. 协议的“类型 + command”唯一，消息类型唯一。
3. 请求与响应关联引用正确的已注册类型。
4. 一个消息类型最多一个 Handler，Handler 指向有效 Domain。
5. Handler、Event、Cron 方法签名有效。
6. RouteKey 提取器对同一消息类型最多注册一次。
7. Cron 表达式与目标 Route 有效。
8. HTTP 方法与原始 path 唯一、签名/Domain 与 RouteKey 规则有效；未配置 Key 规则的入口需要显式 Context 工厂。

**RT-BUILD-01**：构建失败不得返回可部分使用的 Runtime。构建成功后不得通过原始注册对象的修改改变协议注册表。

**RT-BUILD-02**：协议注册与 RouteKey 提取注册是独立能力。协议描述不隐式携带 Domain、Key 或编解码器。显式上下文分发使用传入的 Route。便捷消息分发选择 Handler 的 Domain，通常直接使用调用方传入的 Key；从消息提取属于按 RT-DISPATCH-04 单独选择的入口。

**RT-BUILD-03**：构建成功即进入可工作状态；关闭后不得重新启动。需要新实例时重新构建。

### 4.1 一次请求的完整接入流程

```text
接入层收到并解码消息
→ 验证连接身份与请求合法性
→ 显式提供 Key 和业务身份
→ 解析 Handler Domain，或验证显式上下文的 Domain
→ 需要时构造 HandlerContext
→ dispatch：校验、解析已注册参数并尝试接纳
→ 在目标 Route 绑定 Context
→ 执行 Handler
→ 恢复先前 Context
```

协议注册解决“消息是什么”，RouteKey 注册解决“如何从这个对象取 Key”，Handler 注册解决“由哪个方法处理”。这三件事独立；增加协议描述不会自动获得路由、鉴权或编解码能力。接入层既可以调用已注册的 Key 提取器，也可以使用已认证的玩家身份作为 Key。

dispatch 被接纳不代表业务已成功；Handler 成功也不代表响应已发送。响应编码、请求关联及网络发送由上层完成。接入失败不应通过再次提交相同消息来隐藏，特别是过载时无限重试会放大队列压力。


## 5. Context

| 上下文 | 内容 |
| --- | --- |
| Context | Domain、Key |
| InvocationContext | Context + businessIdType、businessId、Metadata |
| HandlerContext | InvocationContext + message |
| ClientHandlerContext | HandlerContext + 可选借用连接 |
| RpcHandlerContext | HandlerContext + 逻辑来源节点/Slot、command、requestId |
| HttpContext | Context + 借用 HTTP 请求 + 响应完成能力 |
| EventContext | InvocationContext + event |
| TimerContext | Context |
| RouteCallContext | InvocationContext |

businessIdType 是 8 位无符号身份类别，businessId 是 64 位业务身份；它们与 Route 身份分别表达不同用途。Runtime 不根据这些值执行鉴权。

**RT-CTX-01**：仅在业务任务实际执行期间绑定当前上下文；任务结束或抛出异常后必须恢复此前上下文。

**RT-CTX-02**：嵌套内联操作使用自身上下文，完成后恢复外层上下文。异步入队的任务不得从提交线程隐式继承未声明的上下文。

**RT-CTX-03**：当前上下文必须带有所属 Runtime 的内部身份，以区分多个 Runtime。

**RT-CTX-04**：Metadata 按共享不可变值约定传递。继承 Metadata 不代表接收方可以信任身份；可信边界仍由接入层负责。

**RT-CTX-05**：客户端专用 Handler 上下文携带显式传入的借用连接。借用不使连接保持活跃、不转移所有权、不鉴权对端，也不授权 Runtime 关闭连接。显式提交的任务允许没有连接。接纳后断连不取消 Handler。事件、定时器及跨 Route 目标上下文不隐式继承此连接。

**RT-CTX-06**：公共 Handler 上下文包含解码后的消息、路由和业务身份，客户端与 RPC 接入暴露不同的上下文能力。RPC 上下文携带逻辑来源节点、来源 Slot、command 和请求关联信息，而非物理连接。来源 Slot 是回复提示，不是 Route Key 或业务身份；请求关联值在 Notify 时为 0，在 Call 时非 0。接入层独立于传输亲和选择非零业务 Route Key 和 Handler 配置的 Domain。所需上下文能力不匹配时在接纳前拒绝。已接纳任务保留原上下文实例，包括关闭后执行；事件和跨 Route 目标只继承普通调用身份/Metadata，回源回调恢复原上下文。

例如来自节点 10/Slot 2、请求 ID 为 81 的 RPC Call，可在玩家 Route (1,99)、业务 ID 10001 下执行。后续回复通过 RPC 层使用逻辑来源和关联信息，此时 Slot 可能已对应另一条连接。上下文不鉴权这些值，也不保证响应送达。可选 RPC 解码/Runtime 接入及对象回复由 game-spring 提供；上下文保持区分，不增加统一发送 API。见 [Java 上下文绑定](OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#transport-handler-contexts) 和 [传输上下文测试](../../game-runtime/src/test/java/cn/managame/runtime/TransportContextTest.java)。

TimerContext 不携带业务身份和 Metadata。业务若需要定时保存某些信息，应显式捕获适当的不可变数据。

### 5.1 上下文传播矩阵

| 操作 | 执行时的上下文 | 身份与 Metadata | 自定义字段 |
| --- | --- | --- | --- |
| dispatch | 接入方传入的 HandlerContext | 使用传入值 | 保留原实例字段 |
| 内置便捷分发 | 使用外部 Key 或明确选择提取的新 HandlerContext | 显式传入时使用该身份，否则默认身份；显式传入或空 Metadata | 显式传入的借用连接，不隐式复制 |
| 配置的接入策略 | 策略为已选定 Handler Domain 创建的 HandlerContext | 策略选择身份；显式传入 Metadata | 保留返回实例及原消息/连接 |
| HTTP dispatch | 默认或工厂创建的 HttpContext | 显式路由规则；不隐式提供业务身份/Metadata | 保留原实例字段 |
| 同 Runtime InvocationContext 中发布事件 | 新 EventContext | 继承 | 不自动复制 |
| 外部线程或其他 Runtime 发布事件 | 新 EventContext | 默认身份、空 Metadata | 不复制 |
| 跨 Route 计算 | 新 RouteCallContext | 源为 InvocationContext 时继承 | 不自动复制 |
| 跨 Route 回调 | 原始源 Context | 原始值 | 恢复原实例 |
| Timer / Cron | 新 TimerContext | 不携带 | 不复制 |

“恢复原始源 Context”不等于恢复当时的业务数据。Context 中保存的连接或业务对象也不会因此获得快照、保活或线程安全保证。业务需要跨时间稳定的内容，应显式保存不可变值。

Context 的作用域覆盖正在执行的动作。自行启动的线程、任意第三方异步回调不因此获得 Runtime 的 Route 执行资格；即使语言运行时传播了某些线程局部值，也不能把它当成状态访问授权。


## 6. Handler 分发

Handler 只接收已注册的 REQUEST 或 NOTIFY 消息。RESPONSE 不作为入站 Handler 的分发目标。

**RT-DISPATCH-01**：按消息的精确类型查找 Handler，不执行父类或接口的多态匹配。

**RT-DISPATCH-02**：提交前必须检查 Runtime 状态、Domain、非零 Key、Handler 存在性、Handler Domain 和上下文类型。拒绝必须向调用方暴露明确失败。

**RT-DISPATCH-03**：一旦执行 Handler，其异常通过 Runtime 错误处理器报告，不作为业务响应自动发送给远端。

**RT-DISPATCH-04**：便捷消息分发先按消息精确类型查找 Handler，选择其配置的 Domain。常规入口直接接受调用方传入的 Key，不得调用消息提取器或用提取值覆盖该 Key。另一个入口可在接纳前通过已注册规则从消息提取 Key。两者均无需接入层构造 Route 或 Context。Key 为 0 时拒绝；只有选择了消息提取入口时，未注册提取规则才导致拒绝，提取失败直接传回调用方，不入队、不执行 Handler。两个入口使用与显式上下文分发相同的校验、同 Route 内联、串行队列、拒绝、异常及关闭规则。两种入口均允许接入方显式提供业务身份；省略时使用默认身份。内置便捷上下文保留显式传入的 Metadata，未传入时为空，绝不隐式继承外层身份/Metadata。存在连接不代表身份已认证；接入方负责鉴权并提供可信值。语言绑定规定入口 API 及具体默认数值。

**RT-DISPATCH-05**：Handler 可以直接从上下文读取业务身份，无需身份包装类型或自定义参数绑定。Handler 可接收应用自定义的额外类型参数，包括业务身份值。每种类型都需显式绑定从 HandlerContext 创建参数的规则，不根据类型名字、协议字段或 Route 字段推断身份。绑定在构建时固定，未注册或歧义的参数定义导致启动失败。在上下文/Route 校验之后、接纳之前，按参数声明顺序为本次分发解析一次。解析失败直接拒绝，不入队、不调用 Handler；它属于接入失败，不属于 Handler 执行失败。解析器可在提交线程并发运行，不受目标 Route 串行保护，因此不得访问 Route 所有的可变状态。已接纳任务使用解析后的值，不重复解析；对象所有权及不可变性仍由应用负责。参数绑定不改变 Route 选择、执行顺序、过载拒绝或关闭行为。

**RT-DISPATCH-06**：接入方可以为连接/消息分发显式配置上下文创建策略。先查找精确类型的 Handler 及其有效配置 Domain，再以该 Domain、借用连接、不可变 Metadata 和解码消息调用策略一次。策略选择 Key、业务身份，保留 Metadata 及可选上下文子类型，必须保留 Handler Domain 和原消息/连接/Metadata。策略在提交线程运行，位于目标作用域绑定、上下文校验、参数解析与接纳之前。null、抛出的失败或不兼容上下文在入队前拒绝，不回退到消息提取或其他策略。未配置策略时保留既有消息提取入口。显式上下文、外部 Key、显式身份入口绕过此策略。已接纳任务保留返回上下文，不重新读取身份存储或调用策略，包括关闭后执行；同 Route 内联恢复外层上下文。策略执行后仍可能发生关闭/拒绝。策略必须线程安全，不访问 Route 所有的可变状态。语言绑定规定入口选择及失败形式。

角色身份可以存储在连接属性，也可以存储在应用管理的连接到身份 Map；Runtime 不要求具体存储。应用可根据 Handler Domain 在玩家分支选择玩家路由/身份，在公会分支选择公会路由/身份，并负责鉴权、策略含义及存储生命周期。身份由鉴权后明确提供，Domain 不为连接鉴权。接纳前选择的输入继续用于该任务，即使之后存储改变或删除。可变自定义上下文不深复制，应用需捕获稳定值。见 [Java 接入策略](OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#handler-context-factory) 和 [HandlerContextFactoryTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerContextFactoryTest.java)。

例如角色 Handler 直接读取传入上下文的 businessId。Key 99 与身份 role/10001 各自独立，即使请求 userId=42 也不互相替换，无需角色/公会/房间身份包装类型。要求鉴权的接入策略在接纳前选择并校验可信身份；匿名登录可以使用身份 0/0。读取上下文值不表示请求已鉴权或连接可信；显式 Key 和显式上下文入口绕过配置的接入策略，因此由其调用方负责校验。见 [Java 参数契约](OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#handler-arguments)、[HandlerArgumentTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerArgumentTest.java) 和 [demo 身份测试](../../game-demo/src/test/java/cn/managame/demo/DemoIdentityTest.java)。

由用户决定哪些 Handler/Domain 需要角色身份，并且只在鉴权成功后显式提供它；Runtime 不建立 Domain 到角色的策略。[packet 接入示例](../../game-demo/src/main/java/cn/managame/demo/network/GamePacketHandler.java) 将按协议解码的对象及应用会话的路由/身份交给 dispatch。[TCP 验证](../../game-demo/src/test/java/cn/managame/demo/network/GamePacketNetworkTest.java) 证明匹配业务方法的执行，不证明自动响应发送；[接纳验证](../../game-demo/src/test/java/cn/managame/demo/network/GamePacketDispatchTest.java) 验证入队前捕获身份值，之后改变会话不影响已提交任务。连接建立不表示玩家已鉴权。此 demo 的 login Handler 中先由业务校验 token 成功，再手动绑定会话；校验失败时未绑定连接保持无会话。登录路由是独立的应用策略，不需要已鉴权角色。会话存储与登录前路由均为应用选择，不是框架要求。

例如调用方为 userId=42 的登录消息传入 Key 99，Handler 绑定 Domain 1，常规入口仍进入 (1,99)，即使已注册 userId 提取器或该提取器会失败。只有明确选择从消息提取时才进入 (1,42)。未注册提取规则不回退到连接 ID 或任意队列。显式上下文入口继续保留原实例、路由、身份及自定义字段。源码与验证见 [Java Handler 绑定](OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#automatic-handler-dispatch) 和 [HandlerDispatchTest](../../game-runtime/src/test/java/cn/managame/runtime/HandlerDispatchTest.java)。外部传 Key 是常规模型，提取规则服务于少数按协议路由的场景，不是所有 Handler 的前提。

上下文类型不匹配是接入错误。例如 Handler 要求自定义 HandlerContext 子类型时，接入方必须提供该类型的实例。

## 7. 本地事件

Event 自身提供目标 Domain 和 Key。事件是进程内消息，没有持久化、重放或远程投递语义。

**RT-EVENT-01**：事件按精确类型匹配监听器，按 order 从小到大依次调用；相同 order 不保证顺序。

**RT-EVENT-02**：事件进入自己的目标 Route；同 Route 发布可以内联。当前上下文属于同一 Runtime 且为 InvocationContext 时，事件继承业务身份与 Metadata，否则使用默认身份和空 Metadata。

**RT-EVENT-03**：一个监听器抛出异常必须报告，但不得阻止同次发布中的后续监听器执行。

**RT-EVENT-04**：没有监听器不构成 Handler 缺失错误；Route 合法性与任务接纳限制仍然适用。

**RT-EVENT-05**：未显式指定 Runtime 的便捷发布，首先选择当前执行上下文的所属 Runtime；不存在所属上下文时，选择显式配置的进程默认 Runtime；两者皆无则报告配置错误。选中的所属 Runtime 拒绝发布时，包括已经关闭的情况，不得转发到默认 Runtime。选中的 Runtime 执行与普通事件发布一致的 Route 校验、接纳、身份传播、监听器顺序及错误处理，不引入独立的事件执行机制。

每个进程仅有一个默认绑定。重复绑定同一 Runtime 幂等；已有绑定时绑定其他 Runtime 报告冲突，不得静默替换。解绑只移除指定实例，不关闭 Runtime，也不取消已接纳事件。参考实现会在 Runtime 关闭时解除自身绑定；生命周期所有者必须协调启动绑定与关闭。发布已选定 Runtime 后，即使绑定变化，也可能仍提交给该实例或观察到其关闭，不得向新绑定实例重试。这保证多 Runtime 共存时的隔离。例如当前执行属于 Runtime A，即使 B 为默认，仍向 A 发布；旧的已解绑 A 关闭不能移除 B 的绑定。API 名称、进程绑定实现及验证入口见 [Java 规范](OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#static-event-publication)。

发布方不得在任务可能尚未执行时随意修改事件对象。

## 8. 跨 Route 调用

调用由目标 Route、计算函数与回调构成。该能力面向进程内业务协作，不是 RPC，也没有网络超时或远端取消语义。

**RT-CALL-01**：调用必须来自同一 Runtime 正在执行的上下文；无上下文或其他 Runtime 的上下文不得作为源 Route。

**RT-CALL-02**：计算函数在目标 Route 的 RouteCallContext 中执行；继承源 InvocationContext 的业务身份和 Metadata，非 InvocationContext 使用默认值。

**RT-CALL-03**：成功结果或失败必须回到源 Route，并恢复原始源上下文对象；目标与源为同一 Route 时允许内联完成回调。

**RT-CALL-04**：目标 Route 校验失败、接纳失败或计算抛出异常，通过源 Route 的失败回调表达。计算抛出异常还必须报告 ROUTE_CALL_EXECUTION_ERROR。

**RT-CALL-05**：回调自身抛出异常时只报告执行错误，不得再次调用失败回调形成递归。

**RT-CALL-06**：如果源执行器已经关闭或过载，回调无法回到源 Route，应报告 ROUTE_CALLBACK_DISPATCH_FAILED；不得改为在错误的 Route 执行回调。此情况下不承诺回调一定到达。

调用完成前，源 Route 可以处理其他任务；该 API 不提供跨 Route 的原子状态更新。业务失败应通过结果对象返回，框架失败使用框架错误码。

### 8.1 跨 Route 时间线与状态再检查

例如玩家请求加入公会：

```text
玩家 Route：检查玩家状态，发起公会 Route 调用，当前 Handler 返回
玩家 Route：可以继续处理下线、取消申请或其他请求
公会 Route：执行计算，返回不可变结果
玩家 Route：回调入队，轮到回调时恢复原 Context
玩家 Route：重新检查玩家当前状态，再应用结果
```

两段玩家逻辑各自串行，但中间允许其他任务介入。需要保证“结果仍对应当前请求”时，业务可使用自己的请求版本或状态检查；Runtime 不自动暂停源 Route，也不提供跨 Route 事务。

### 8.2 失败发生在哪一段

| 阶段 | 结果 | 源业务能够依赖的行为 |
| --- | --- | --- |
| 没有合法源上下文 | 调用本身被拒绝 | 不执行目标计算 |
| 目标 Route 非法或拒绝接纳 | 尝试回源失败回调 | 目标计算没有执行 |
| 目标计算抛异常 | 报执行错误，再尝试回源失败回调 | 不自动重试目标计算 |
| 目标计算返回 | 尝试回源成功回调 | 返回结果可能已经产生业务影响 |
| 回源接纳失败 | 报回调投递错误 | 不在目标 Route 代跑回调 |
| 回调自身抛异常 | 报执行错误 | 不递归调用 onFail |

前两种目标失败与“回源失败”是两层不同问题。回源失败可能发生在目标已经完成之后，不能据此推断目标未执行。若业务要求结果最终必达，需要由更高层设计持久化或补偿流程，V1 本地调用不承诺这一点。


<a id="demo-runtime-services"></a>

## 9. GameTime、Timer 与 Cron

[demo 服务示例](../../game-demo/README.zh-CN.md#demo-runtime-services) 将 HTTP 请求、一次性定时任务和重复 cron 接到同一套执行基础设施。Domain/Key 相同就选择同一条串行 Route，与入口类型无关；定时触发表示接纳，不保证立即执行。应用启动持有传输监听器和任务注册，关闭时先停止入口与待触发任务，再释放 Runtime 资源。[DemoServicesTest](../../game-demo/src/test/java/cn/managame/demo/DemoServicesTest.java) 验证真实请求、定时执行与取消/关闭，不代表生产定时精度或已实现鉴权。

### 9.1 业务时间

**RT-TIME-01**：GameTime 提供统一业务墙钟，支持读取 epoch 毫秒、按显式时区取得本地时间、替换和恢复时间源。当前 Java 绑定作用域为整个进程，默认 UTC 系统 Clock。

**RT-TIME-02**：修改业务时间不得自动通知、重排或补跑 Timer/Cron。动态 Timer 由业务明确取消，按新的 GameTime 计算 delay 后重新注册；静态 Cron 由 CronScheduler 显式重排。

**RT-TIME-03**：墙钟与经过时长必须区分。一次性 Timer 继续使用单调延迟，网络超时与耗时统计不得被 GameTime 改动影响。

### 9.2 一次性 Timer

**RT-TIMER-01**：Timer 的到期仅触发向目标 Route 提交任务；业务逻辑必须在 Route 执行语义下运行。延迟可以为零，不得为负。

**RT-TIMER-02**：取消成功表示取消先于触发取得执行资格。一旦触发取得资格，取消不得声称撤回了已经排队的任务。

**RT-TIMER-03**：Timer 和 Cron 使用新的 TimerContext，不继承创建者的 InvocationContext。

### 9.3 Cron

**RT-CRON-01**：Cron 的 Domain、Key 和表达式必须在构建时校验。日历计算时区必须明确；当前绑定默认 UTC。

**RT-CRON-02**：Cron 是“读取 GameTime → 计算下一次日历时刻 → 注册一次性 Timer → 在 Route 执行业务 → 重新计算”的循环。本轮方法完成后才计算下一周期；不提供实时 Tick 或补发所有错过时刻。

**RT-CRON-03**：Cron 或 Timer 的业务异常应报告；不得隐式重试业务操作。Cron 本次 Route 接纳失败或方法抛出异常后仍继续安排下一周期。

**RT-CRON-04**：CronScheduler 提供 cancel、reschedule、rescheduleAll。Java 绑定使用声明类与方法名作为 Key；重复 Key 必须在构建时拒绝。

**RT-CRON-05**：cancel 停止当前调度及后续周期；已认领执行的业务方法允许完成。取消返回值表示该 Cron 是否从活动状态变为停止状态，不承诺中断正在运行的方法。

**RT-CRON-06**：reschedule 取消旧调度，基于当前 GameTime 重新计算，可重新启用取消的 Cron。rescheduleAll 作用于所有注册项，包括已取消项；不要求跨项原子切换。

**RT-CRON-07**：旧调度代次不得覆盖重排后的新代次。已经排队但尚未认领执行的旧代业务动作必须跳过；已开始的旧代方法完成后不得重新注册旧周期。

**RT-CRON-08**：取消/重排不提供业务幂等性或日历触发去重。显式重排后可能再次调度同一个日历时刻，应用应按业务要求处理重复影响。

具体表达式支持范围见 Java 开发规范。不能以其他 Cron 产品的完整语法推断本实现接受的表达式。

### 9.4 时间变更示例

假设业务时间为 10:00，业务计算出一小时后到期并注册 Timer。五分钟后把 GameTime 调到 12:00，原 Timer 仍按最初的一小时经过时长触发；它不会自动立即执行。若到期逻辑要求跟随新墙钟，业务应取消旧 Timer，读取新的 GameTime，再决定立即处理或重新计算延迟。

Cron 同样不会因改钟自动调整已经安排的下一次触发。显式 reschedule 后才按新墙钟与配置时区重新计算；跳过的日历时刻不会全部补跑。向后改钟或重复重排可能再次遇到同一个日历时刻，业务结算应有自己的幂等依据。

### 9.5 取消与重排的竞争边界

| 发生时点 | 一次性 Timer.cancel | Cron.cancel / reschedule |
| --- | --- | --- |
| 尚未取得触发资格 | 可以取消本次触发 | 取消旧调度；重排可安排新调度 |
| 已触发并提交 Route，业务尚未执行 | 不能撤回该任务 | 旧代尚未被认领的业务动作会跳过 |
| 业务方法已开始 | 不打断业务 | 允许本次完成；旧代不得再安排后续周期 |
| 业务已完成 | 不撤销结果 | 只影响后续调度 |

Timer 的触发资格与 Cron 的业务执行资格属于不同层次，因此两者取消行为不能混为一谈。Cron 重排不是停止整个 Route；该 Route 上的普通请求照常遵循接纳与串行规则。


## 10. 错误与关闭

完整数值见 [共享错误码](OGBS-Core-1.0.zh-CN.md)。Runtime 使用 3001–3010：

| 名称 | 触发场景 |
| --- | --- |
| RUNTIME_CLOSED | Runtime 已关闭 |
| ROUTE_DOMAIN_MISMATCH | Domain 未注册或与 Handler Domain 不一致 |
| INVALID_ROUTE_KEY | Key 为零 |
| ROUTE_EXECUTOR_OVERLOADED | 目标执行器容量不足 |
| ROUTE_EXECUTOR_CLOSED | 目标执行器已关闭 |
| HANDLER_NOT_FOUND | 找不到消息 Handler |
| HANDLER_CONTEXT_MISMATCH | Context 不满足 Handler 要求 |
| ROUTE_CALL_EXECUTION_ERROR | 跨 Route 计算抛出异常 |
| RUNTIME_EXECUTION_ERROR | 业务执行、监听器、回调或执行器调用异常 |
| ROUTE_CALLBACK_DISPATCH_FAILED | 无法把完成回调提交回源 Route |

**RT-CLOSE-01**：关闭必须幂等，停止 Timer/Cron 调度并关闭 Runtime 拥有的执行器。同一个执行器对象被多个 Domain 共享时只关闭一次。

**RT-CLOSE-02**：关闭后拒绝新的普通任务提交。本绑定的官方执行器继续处理已接受任务，但 close 不等待全部业务任务执行完毕。

**RT-CLOSE-03**：关闭不是整个服务的优雅停机屏障。应用需要先控制网络入口、在途请求与业务完成，再安排 Runtime 关闭。

构建失败时调用方仍负责自己创建的 Executor；成功构建后其生命周期归 Runtime 管理。

### 10.1 服务停机的组合顺序

需要等待业务完成的服务先停止 Runtime 接纳，等待其已登记工作完成，再关闭 Runtime 和下游资源。下面的排空操作提供此屏障；立即 close 返回不证明所有 Handler 已完成。

Runtime、Network 与 Data 的 close 语义不同：Connection.close 发起异步断开；Runtime.close 不等待业务队列全部完成；Data.close 同步处理已接纳保存。集成代码必须分别理解这些边界，不能统一套用“close 返回即一切结束”。

### 10.2 已确认设计取舍

本表解释已有条款，不增加新的公共 API。后续局部需求应从相应条款继续，而不重新引入已排除的模型。

| 已确认选择 | 目的与影响 | 需要重新评估的触发条件 |
| --- | --- | --- |
| 完整 Route 决定串行边界 | 支持多个业务域和多个 Runtime；Key 单独不足以隔离 | 改变业务状态归属或路由身份 |
| 同 Route 直接执行 | 避免无意义的回投；允许同步完成和递归 | 要求所有调用统一异步或限制调用栈 |
| Context 仅在执行期绑定 | 避免跨请求残留，支持嵌套恢复 | 增加新的上下文传播机制 |
| Key 提取与分发独立 | 接入方显式决定路由，协议注册不夹带策略 | 自动路由需求 |
| 回调必须回源 | 保持源状态串行访问 | 要求回调必达，需要另行设计可靠交付 |
| Timer 不继承身份，改钟不自动重排 | 定时逻辑的时间与身份来源明确 | 新增业务调度或补跑语义 |
| 显式注册、构建后冻结 | 启动时发现冲突，运行路径固定 | 动态热注册能力 |

V1 未定义跨进程 Route 迁移、持久化任务、自动重试、回调必达、暂停 Route 等待结果或实时 Tick 调度。将来引入这些能力时，应补充独立的行为条款及与当前契约的兼容关系；不能把普通实现优化默认为这些承诺已经存在。


### 10.3 停止接纳、等待排空与观察统计

**RT-DRAIN-01**：停止接纳不可恢复且幂等。拒绝新的外部 Handler、HTTP 和 Event 工作，停止未来 Timer/Cron 触发。执行器仍供已接纳任务及本 Runtime 正在执行的任务明确发起的后续工作使用。接纳竞争要么拒绝，要么在排空完成前登记，不能漏掉已接纳工作。已取消的 Cron 世代按既有取消规则作为空操作结束。

**RT-DRAIN-02**：停止接纳后，外部管理调用方可以等待已登记工作。成功表示没有已登记的排队/执行任务或异步后续工作，不表示网络送达、持久化或未登记的应用回调已完成。超时不丢弃任务、不关闭资源。禁止在本 Runtime 的 Route 上等待。立即 close 保留不等待契约；平滑停机先停止接纳、等待，再关闭 Runtime 及依赖。

**RT-CALL-05**：明确捕获的异步后续回调登记未完成工作，首次成功/失败在原始 Context/Route 恢复后执行。重复完成忽略；回源接纳失败报告诊断，不在其他线程重试。接入方必须完成每个捕获回调，包括外部调用同步异常及超时；遗失回调可能导致排空一直等待。捕获不延长借用请求或连接的生命周期。

**RT-DIAG-01**：统计是近似观察，不作为接纳决策或业务/持久化/送达确认。排队/执行动作和异步登记含义不同；嵌套内联计时可能重叠。错误计数包括执行及回源错误；校验失败不一定计入执行器拒绝数量。

已接纳的玩家 Handler 可在停止接纳后发起公会工作，两者及回源回调都在等待范围内；网络的新请求拒绝。其他线程不能仅因数字 Key 相同而发起工作。[RuntimeDrainTest](../../game-runtime/src/test/java/cn/managame/runtime/RuntimeDrainTest.java) 验证停机竞争、拒绝清理、登记与恢复。

## 11. 实现与验证映射

| 契约 | 当前实现/测试 |
| --- | --- |
| 构建注册、Context、分发、事件、回调、Timer/Cron | [RuntimeTest](../../game-runtime/src/test/java/cn/managame/runtime/RuntimeTest.java) |
| Route 串行、容量与执行器关闭 | [RouteExecutorTest](../../game-runtime/src/test/java/cn/managame/runtime/executor/RouteExecutorTest.java) |
| 调度与生命周期实现 | [DefaultGameRuntime](../../game-runtime/src/main/java/cn/managame/runtime/internal/DefaultGameRuntime.java) |
| GameTime 替换、时区与恢复 | [GameTimeTest](../../game-runtime/src/test/java/cn/managame/runtime/time/GameTimeTest.java) |
| Cron 取消、重排代次、过载与关闭 | [CronSchedulerTest](../../game-runtime/src/test/java/cn/managame/runtime/timer/CronSchedulerTest.java) |
| 构建校验 | [GameRuntimeBuilder](../../game-runtime/src/main/java/cn/managame/runtime/GameRuntimeBuilder.java) |

这些测试是当前验证入口，并不表示每个条款的所有竞争条件都已穷举。扩展实现时应围绕接纳、上下文恢复、关闭竞争和回调提交失败增加针对性验证。
<a id="runtime-http-profile"></a>

## 12. HTTP 业务分发

HTTP 分发是 Runtime 的可选入口，与普通 Handler、Event、call 使用相同的完整 Route 标识和接纳边界。传输 framing、连接请求顺序和实际响应发送仍由 [Network HTTP Profile](OGBS-Network-1.0.zh-CN.md#http-server-profile) 负责；Java 注解及依赖见 [Java 绑定](OGBS-Runtime-Java-25-Specification-1.0.zh-CN.md#runtime-http-api)。

**RT-HTTP-01**：显式注册并冻结以 HTTP 方法和原始 path 标识的入口。支持的入口方法为 GET、POST、PUT、PATCH、DELETE、HEAD、OPTIONS、TRACE；未指定方法时默认 POST，GET 必须显式选择。匹配忽略 query，不解码百分号转义、不规范化路径、不去掉尾部斜杠、不匹配模板、不推导 HEAD/OPTIONS 行为。重复入口在构建时拒绝。未知路径返回 404，已知路径未注册的方法返回 405 及允许的方法。初始 Profile 接受 origin-form 请求目标，非法目标返回 400，不实现 CONNECT 隧道或自定义方法 token 注册。

**RT-HTTP-02**：注册选择已注册 Domain。接纳前按显式入口/Handler 规则（RT-HTTP-07）选择非零 Key；未配置规则时由应用上下文工厂确定。有规则而无工厂时创建默认 HTTP Context，包含 Route、请求及响应完成能力。提供工厂时将选定 Key 传给工厂，工厂必须保留该 Key，可增加会话数据等应用自定义 HTTP 字段。HTTP Context 不定义业务身份或 Metadata。不隐式推导玩家字段或提交线程身份。工厂可显式拒绝而不执行方法；非法输入或零 Key 返回 400。不兼容的工厂结果属于接入错误，报告 Runtime 错误处理并尝试以服务端失败完成响应。

**RT-HTTP-03**：已接纳方法与其他 Runtime 入口使用相同 RouteExecutor 和 Context 作用域。相同完整 Route 的动作不能重叠，同 Route 嵌套可内联。HTTP Context 使用基础 Context 模型，不属于调用身份模型。按普通 Context 继承规则，从它发起的 Event/call 使用默认身份及空 Metadata；call 回调恢复原始 HTTP Context 对象，包括自定义字段。HTTP 没有携带业务身份或 Metadata 的框架调用信封，路由 Key 也不是已认证身份。应用自定义字段不会自动复制到 Event/call Context。Key 提取和工厂都运行在进入 Route 之前，不得访问 Route 所有的可变业务状态。

**RT-HTTP-04**：方法可返回业务对象由 Runtime 自动完成，也可不返回结果并稍后显式完成业务对象。结果契约不携带传输响应封装或协议版本。首次响应/失败完成获胜，竞争失败的对象不编码。响应完成不代表实际发送或业务成功。方法异常或编码失败会报告错误并尝试以服务端失败完成；已完成结果不被替换。null 是有效对象结果，不是执行失败。不自动重试业务。

**RT-HTTP-05**：Runtime 从提交到方法返回/异常持有一个额外请求引用，拒绝时也释放。方法中的请求是借用的。后续响应完成或恢复相同 Context 不延长借用期；异步使用方必须自行 retain/copy 并释放自己的资源。业务结果仍是调用方所有的普通值；适配器拥有编码后的传输资源，并在失败/晚到发送时释放。延迟结果数据必须能在请求借用结束后独立使用。

**RT-HTTP-06**：Runtime/执行器关闭或过载时，新 HTTP 工作返回 503 且不执行业务。已接纳工作仍由执行器负责执行，包括释放请求引用。Runtime close 不拥有 HTTP Server，也不等待全部请求结束。断连不取消已接纳业务或撤销副作用。Runtime 不设置延迟响应截止时间；应用负责完成和停机，Network 负责传输空闲与关闭。外部回调仅持有 HTTP Context 不会获得 Route 状态访问资格。

例如，玩家 HTTP 方法发起公会 call 后返回。Runtime 释放请求引用，玩家 Route 可处理其他动作。公会结果回调稍后进入原始玩家 Route 并恢复原始 HTTP Context，可重新检查玩家状态并回复，但不能读取已结束借用的请求。应先复制不可变 body 数据，或持有独立 retain 引用。如果回调接纳失败，沿用普通 call 错误报告规则，不保证 HTTP 响应最终完成。

已确认取舍是为游戏服务普遍需要的 HTTP 入口提供统一业务执行模型，同时保持传输生命周期和鉴权显式。游戏服务常用 body 请求，因此默认 POST；GET 作为 query 入口需显式选择。修改原 GET 默认值不会注册别名：未指定方法的入口现在接收 POST，GET 在未另行注册时返回 405。既有 GET 入口需显式选择 GET 才能保留行为。语言绑定使用类型化方法选择，避免拼写/大小写错误；增加方法 token 需基于实际需求审查兼容性。原始路径精确匹配使入口选择可预测，避免另建一套路由框架。仅在出现具体模板、body 绑定或新增 HTTP Profile 需求时重新评估；自动请求 DTO 绑定、multipart/流式分发、HTTP 客户端、回调必达和生产容量认证不属于当前实现。

实现与验证入口：[RuntimeHttp](../../game-runtime/src/main/java/cn/managame/runtime/internal/RuntimeHttp.java)、[RuntimeHttpTest](../../game-runtime/src/test/java/cn/managame/runtime/http/RuntimeHttpTest.java)、[RuntimeHttpExample](../../game-demo/src/main/java/cn/managame/demo/examples/runtime/RuntimeHttpExample.java)。测试覆盖默认 POST、显式支持方法、405/Allow 边界、匹配、接纳、引用所有权、完成竞争、上下文传播、关闭及真实 HTTP 传输接入。

**RT-HTTP-07 — 显式 Key 选择**：入口可整体覆盖所属 Handler 的默认 Key 提取规则；入口未配置则继承 Handler。规则只能是精确字段名或显式提取方法之一，不能同时设置。字段规则在 GET 中读取一个解码后的 query 参数，其他方法读取 JSON body 的一个顶层属性，不跨来源回退。JSON 必须是完整有效的单个 UTF-8 对象，不允许重复属性名或额外尾部值。选定值必须是非零、有符号 64 位整数或十进制整数字符串，不截断小数、浮点/指数形式或溢出值。缺失、重复、非法值在工厂/接纳前返回 400，不执行业务。自定义提取在接纳前运行，返回非零 Key，可拒绝非法输入；其他异常报告诊断并尝试以服务端失败完成。配置规则后，失败不得回退到类规则或工厂 Key。提取保留请求可读 body 和所有权。JSON 嵌套/大小限制由语言绑定规定，不替代传输限制。Key 是路由输入，不是已认证业务身份。

例如，Handler 默认字段 playerId，一个入口覆盖为 guildId。GET `/guild?playerId=42&guildId=73` 选择 Key 73；POST `/guild` 的 body `{"playerId":42,"guildId":73}` 也选择 73。缺失 guildId 时拒绝，即使 playerId 存在。JSON 后半部分非法时拒绝，即使之前已读取 Key。[HttpRouteKeyTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpRouteKeyTest.java) 覆盖来源选择、覆盖/失败边界、完整 JSON 校验、整数边界、自定义提取与 body 所有权。显式字段配置减少逐入口样板，不引入自动 DTO 绑定或身份信任推导。

**RT-HTTP-08 — 业务结果与编码**：默认对象完成使用状态码 200、UTF-8 JSON，null 结果编码为 JSON null。异步显式完成可提供最终状态码整数，无需构造传输响应。可配置结果 codec 以使用其他媒体表示。在完成线程同步编码获胜结果；方法自动返回在当前 Route 编码，任意外部回调不因此获得 Route 状态访问资格。编码期间对象必须稳定，不得借用已过期的请求内容。编码成功后传输层发送独立所有权字节，不保留业务对象以延迟序列化。编码失败作为 Runtime 执行错误报告并尝试服务端失败，不重试、不撤销已发生业务副作用。后续传输 Profile 必须保持该业务结果契约；当前实现仍仅支持 Network HTTP/1.1 Profile。

例如，方法返回玩家快照 `{id, name}`，无需构造 HTTP/1.1 响应；跨 Route 完成通过结果回调提交相同快照。两者使用同一个配置 codec 和首次完成规则。对端收到响应不代表数据库已提交。[HttpResultTest](../../game-runtime/src/test/java/cn/managame/runtime/http/HttpResultTest.java) 覆盖 DTO/null 编码、延迟结果、完成竞争、编码失败、自定义媒体表示和传输结果拒绝。协议特定 framing 仍由 Network 负责。

**RT-HTTP-09 — 请求值**：方法可接收独立所有权的解码值代替借用原生请求，也可省略上下文参数。默认对象绑定在非 GET 使用 JSON body，在 GET 使用已解码 query 字段；重复字段表示数组。String 输入读取 UTF-8 body，包括 GET，不隐式选择 query 字段。绑定非法在接纳前返回 400；codec 接入异常作为服务端失败诊断。解码保留声明泛型。媒体校验由应用明确处理。解码值在原生请求释放后仍可使用；捕获上下文不延长请求生命周期。路由选择独立且保持 RT-HTTP-07 严格校验。
