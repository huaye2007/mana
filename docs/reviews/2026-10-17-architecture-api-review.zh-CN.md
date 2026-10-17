# 架构与 API 设计评审（2026-10-17）

[English](2026-10-17-architecture-api-review.md) | **[简体中文](2026-10-17-architecture-api-review.zh-CN.md)**

> 非规范记录。当前契约由各组件规范持有；本页只问两个关于"已经建成的东西"的问题——模块架构是否正确，公共 API
> 设计是否合理——并记录每个回答的证据。本文不改变任何契约。配套：
> [2026-10-17 缺口分析](2026-10-17-framework-gap-analysis.zh-CN.md)，那份讲"缺什么"，本份讲"形状对不对"。

## 1. 结论

架构在最难做对的地方做对了，在最便宜修的地方放松了。依赖图、缓冲区所有权约定与背压词汇确实造得好（§2）。
两个归置决定值得重新考虑：HTTP/JSON 住在业务执行模块里（§3.1，按所有者意见暂缓——HTTP 暂不在范围内），
以及 RPC↔Runtime 的接缝一半在 Runtime 的 API 里、一半在 Spring 模块里（§3.2）。

公共 API 弱于架构。有一个类型主动谎报自己的契约（§4.1），分发门面有九个入口、靠位置化基本类型区分（§4.3），
同一个概念在不同模块里有三种拼法（§4.2、§4.5、§4.7）。这些都不是正确性缺陷；但它们是每个应用都要反复付出的
摩擦成本，而且大多数现在改很便宜，等应用照着写完之后再改就很贵。

## 2. 做对的部分

这些不是客套。每一条都是框架常常做错的决定。

**2.1 依赖图干净，而且现实与图一致。** 逐模块收集全部 `import cn.managame.*`，得到的正是声明的那张图，无环：

| 模块 | 依赖 |
| --- | --- |
| game-core | *无*（也没有任何第三方依赖） |
| game-network | *无* |
| game-rpc | core、network |
| game-router | rpc |
| game-runtime | core、network |
| game-data | core |
| game-spring | core、network、rpc、runtime、data |

game-rpc 不知道 Runtime；game-runtime 不知道 RPC；game-data 只知道 Core。只有集成模块什么都知道——而那正是
这些知识该待的地方。

**2.2 Router 是组合进去的，没有另起一套基础设施。** `GameRouter` 是一个装在应用自有 `RpcNode` 上的
`RpcHandler`：没有第二个监听器，没有第二个线程池，它自己的 Javadoc 写明"在收到消息的那个 Netty 线程上处理；
没有后台线程"。*（当天即被取代：两级网格改造让 Router 有了一个用于周期性位置上报的守护线程，转发仍在收到消息的
那个 Netty 线程上——见 [2026-10-17 Router 网格改造](2026-10-17-router-mesh-redesign.zh-CN.md)。）* 一个需要
自带传输层与执行器的中继会把运维面翻一倍。它没有。

**2.3 缓冲区所有权只有一套约定，而且到处都用同样的话说。** `RpcHandler.onRequest`："Body 是借用的，保存它或
转交给 reply() 之前要 retain/copy。" `ConnectionHandler.onMessage`："引用计数消息是借用的，回调返回后释放。"
`RouterHandler`："请求与响应在回调期间是借用的。"跨三个模块的引用计数缓冲区，一条规则、零例外——这是基于 Netty
的框架里最容易出错的一件事，而它是一致的。

**2.4 背压有共享词汇。** 三个准入枚举，三个模块，一个形状：

```java
WriteStatus       { ACCEPTED, INACTIVE, NOT_WRITABLE }        // game-network
RpcSendStatus     { ACCEPTED, PEER_NOT_FOUND, UNAVAILABLE }   // game-rpc
RouteExecuteStatus{ ACCEPTED, OVERLOADED, CLOSED }            // game-runtime
```

每一个都以 `ACCEPTED` 开头，每一个都表示"本地已接纳，不代表已投递或已执行"，而 `RpcSendStatus` 在 Javadoc 里
把这点写明了。读懂一个就读懂了三个。

**2.5 Builder 是同一套模式。** 七个 builder，全部是"流式 setter → `build()` 返回产物"，全部在 `build()` 里
校验。偏差见 §4.7，都很小。

**2.6 错误码分配有单一来源。** Core 持有数值与区间；`RpcErrorCodes` 的常量派生自 `FrameworkErrorCodes` 而不是
重新抄一遍，所以两者不会漂移。（剩下的问题是只有 RPC 有这层别名——见 §4.5。）

## 3. 架构：两个值得重新考虑的归置决定

### 3.1 业务执行模块拥有 HTTP 与 JSON，而且不管你用不用都会加载 Jackson

> **2026-10-17 按所有者意见暂缓：HTTP 暂时不看。** 本条保留作为记录，在 HTTP 重新进入范围之前不再作为行动项
> 提出。下文没有任何已知错误，只是暂不处理。

game-runtime 的定位是"Route 执行、Context、Handler、Event、GameTime、Timer/Cron"——以及 HTTP。HTTP 面是
15 个文件、约 530 行，占该模块 2,598 行的五分之一左右；而 jackson-databind 是 game-runtime 唯一的第三方依赖，
只被三个文件使用，且全部是 HTTP 相关。

仅凭这点还只是取舍。让它成为缺陷的是：这个依赖连延迟加载都没有做到：

```java
// GameRuntimeBuilder
private HttpResultCodec  httpResults  = HttpResultCodec.json();
private HttpRequestCodec httpRequests = HttpRequestCodec.json();
```

`HttpResultCodec.json()` 的方法体会执行 `JsonSupport.MAPPER.writer()`，而 `JsonSupport.MAPPER` 是一个静态的
`new ObjectMapper()`。`HttpRequestCodec.json()` 返回 `JsonHttpRequestCodec.INSTANCE`，它的实例字段就是同一个
mapper。于是**构造任何一个 `GameRuntimeBuilder` 都会加载 jackson-databind 并创建一个 ObjectMapper**，包括在
一个纯 RPC 服务节点或一个永远不会处理 HTTP 请求的纯 TCP 游戏节点上。

后果，按代价排序：

1. jackson-databind 无法标记为 `<optional>true</optional>`：那个急切的字段初始化会在 builder 构造时就抛
   `NoClassDefFoundError`，而不是等到第一次用 HTTP。
2. 每个 Runtime 使用方的依赖树里都多一个 JSON 库，启动路径里都多一次 ObjectMapper 构造，而这个特性它可能
   根本不用。
3. 这正是 2026-10-14 审阅把拆出 `game-runtime-http` 模块一事搁置的机械原因（"去掉 Caffeine 之后，剩下的依赖
   是 Jackson 和 network"）。看起来是依赖挡住了拆分，但这个依赖之所以无法避免，只是因为默认值是急切的。

便宜的修法不是拆模块：把这两个默认值改成按需求值（或者在 `RuntimeHttp` 里真正配置了 HTTP 时再解析），Jackson
就真的可选了，而拆模块随后变成一个随时可做的自由选择，而不是前置条件。

### 3.2 RPC↔Runtime 的接缝散在三处，而且只有 Spring 这一条路是现成的

game-runtime 不声明对 game-rpc 的依赖——这是对的，它保持了两者独立。但 Runtime 的公共 API 里已经有 RPC 了：

- `GameRuntime.dispatchRpc(int sourceNodeId, int sourceSlotId, int command, int requestId, long routeKey, int businessIdType, long businessId, Metadata metadata, Object message)`
- `RpcHandlerContext`，带 `sourceNodeId()`、`sourceSlotId()`、`command()`、`requestId()`
- `DefaultRpcHandlerContext`，public，并校验这几个字段

所以 Runtime 在建模 RPC 入站，却一个 RPC 类型都叫不出来：信封以七个散落的基本类型抵达。而真正的适配器——把一次
`RpcHandler` 回调变成一次 Runtime 分发再变成一次回复——是 `game-spring` 里的 208 行（`GameRpc`、
`GameRpcCodec`）。

由此有两件事。第一，这份知识散在三处（RPC 的形状在 Runtime 的签名里、接线在 Spring 里、契约在两份规范里），
而它本可以集中在一个适配器里。第二，更实际：**不使用 Spring 的应用没有任何现成办法把 RPC 接到 Runtime 上**，
它必须自己照着一个九参数方法写适配器。game-spring 在文档里是可选的；这一块在实践中不可选。

接缝本身是对的——RPC 与 Runtime 不该互相依赖。要问的是 `dispatchRpc` 与 `RpcHandlerContext` 究竟该不该待在
Runtime 的 API 里，还是该待在一个同时依赖两者、且非 Spring 应用也能用的小适配模块里。附注：尽管
`DefaultRpcHandlerContext` 是 public 的，`dispatchRpc` 并不与 `dispatch(HandlerContext)` 重复——Context 入口
要求调用方已知 Domain，而 `dispatchRpc` 从注册的 Handler 解析 Domain。这个不对称本身就是 §4.3 的一部分。

### 3.3 三种不同的两阶段初始化写法

无法一步构造完成的对象出现在三个模块里，各自用不同办法解决：

| 类型 | 写法 | 无效状态表现为 |
| --- | --- | --- |
| `RpcNode` | `build()` 然后 `start()` | `IllegalStateException("RPC node is not running")` |
| `GameRouter`、`ServiceRouting` | 构造器收 handler，`start(RpcNode)` 收 node | `volatile RpcNode rpc` 为 null + 每条数据路径上的 `IllegalStateException("Router not running")` |
| `SingleRepository` 等 | 反射构造后由框架调用包私有的 `initialize(...)` | `DataOperationException("Repository is not initialized")` |

Router 这一例代价最高，因为那个环是真实的：Node 需要 Router 作为它的 handler，Router 需要 Node 才能发送。当前
解法在每次中继时都留下一次可空 volatile 字段读取，并在每个公共方法里留下一次状态检查。在 `RpcNodeBuilder` 上
提供一个 handler 工厂（`handler(node -> …)`）可以在 build 时就闭环，并消去整整一类"not running"失败。这是设计
观察，不是缺陷：现有代码是小心的，它的竞态都处理了。

### 3.4 Data 的仓储由框架实例化，这断掉了构造器注入

`SingleRepository<K, E>` 是一个抽象类，应用继承它且不加任何成员；`GameData` 用反射构造它（无参构造器、直接的
参数化基类），再通过包私有的 `initialize` 注入 `meta`、`mapper`、`writer` 与 `cache`。所有公共方法都是
`final`，这是对的。

代价在 game-demo 的 README 里已有记录："不支持构造器依赖注入，因为这些实例由 GameData 构造。"还有一个代价没有
记录：没有 `GameData` 就无法在单元测试里构造一个仓储，所以仓储层的业务逻辑只能穿过完整的 Data 栈来测。Spring
Data 解决同一问题的办法是接口加生成实现，从而把构造权留在容器里。这个取舍值不值得重新考虑由所有者决定；但它
至少该是一个被记录下来的取舍，而不是实现的副产品。

## 4. API 设计：具体缺陷

### 4.1 `RpcCallback<T>` 宣称自己是回调，实际被当成不透明令牌用

```java
@FunctionalInterface
public interface RpcCallback<T> { void onResponse(T response); }
```

RPC 核心从不调用 `onResponse`。`RpcNode` 只是把这个对象递回去：

```java
invoke(() -> handler.onResponse(peer.nodeId, pending.command, response, pending.callback));
```

而 `RpcHandler` 收到的类型是 `RpcCallback<?>`，所以不强转就拿不回类型参数。仓库里每一个实现都印证了这个形状
是错的：

| 实现 | `onResponse` 方法体 |
| --- | --- |
| `ServiceRouting.Call` | `public void onResponse(Object ignored) {}` |
| `RpcNodeTest`、`RpcResilienceTest` | `value -> {}` |
| `RouterIntegrationTest` | `@SuppressWarnings("unchecked") RpcCallback<Integer> completion = (RpcCallback<Integer>) callback;` |
| `GameRpc.Completion<T>` | 唯一真被调用的一个——由 game-spring 在它自己的对象上调用 |

所以在 RPC 核心里这是一个附件：调用方提供、处理器识别的一个令牌。这个设计本身没问题——2026-10-14 审阅考虑过
带类型的附件并出于正当理由否决了。错的是这个类型被塑造成了回调的样子。它声明了一个框架从不调用的方法，带了一个
框架会擦除的类型参数，在唯一想要类型的地方逼出 `@SuppressWarnings("unchecked")`，还教每个实现者去写一个空方法
体。附件就该长得像附件：一个按其本质命名的标记接口（或 `Object`），而 `onResponse` 该待在 game-spring 真正会
完成的那个类型上。

### 4.2 失败回调有三个名字，成功有两个

| 接口 | 成功 | 失败 |
| --- | --- | --- |
| `RouteCallback<T>` | `onSuccess(T)` | `onFail(int)`、`onFail(int, Throwable)` |
| `ConnectCallback` | `onSuccess(Connection)` | `onFailure(Throwable)` |
| `RpcHandler` | `onResponse(...)` | `onFail(...)` |
| `RouterHandler` | `onResponse(...)` | `onFail(...)` |
| `ConnectionHandler` | `onMessage(...)` | `onException(...)` |
| `RpcCallback<T>` | `onResponse(T)` | ——（见 §4.1） |

同一个角色在同一个框架里叫 `onFail` / `onFailure` / `onException`，这是用户第一天就会撞上并记住的那种不一致。
`onException` 还算可以区分（传输错误，不是结果），但 `onFail` 与 `onFailure` 之间没有任何辩护余地。趁 API 还
没发布，定一个，其余改名。

### 4.3 `GameRuntime` 有九个分发入口，靠位置化基本类型区分

接口自己的 Javadoc 不得不去消歧："分发有三个主要客户端入口（显式 Key、已配置的 Context 工厂或消息抽取、带显式
身份的抽取），再加显式 Context 入口和 RPC 入口；其余重载是简写。"当一组重载需要一段文字来解释时，这组重载本身
就是问题。

两个具体隐患，不只是啰嗦：

**调用点无法区分。** 下面两行是不同的操作：

```java
runtime.dispatch(connection, 99L, message);              // 99 是 routeKey
runtime.dispatch(connection, 1, 10001L, message);        // 1 是 businessIdType，10001 是 businessId
```

两行读起来都是"连接、数字、消息"。game-demo 的 README 不得不逐一说明哪个是哪个。一个 `RouteKey` / `BusinessId`
包装类型，或者一个小的入站 record，能让调用点自己说清自己。附注：框架在一个相关问题上做了相反的选择，那是可以
辩护的，但代价留在了这里。

**行为随 builder 配置变化，且不可见。** `dispatch(Connection, Metadata, Object)` 会根据是否配置了工厂，去用
已配置的 `HandlerContextFactory` *或者* 消息 Key 抽取；而 `dispatch(Connection, int, long, Object)` 则"绕过
工厂"。于是别人在另一个文件里改了 builder，这行源码的含义就变了。

`dispatchRpc` 是极端情形：九个参数，其中七个是连排的基本类型（`int, int, int, int, long, int, long`）。把
`sourceSlotId` 与 `command`、或 `businessIdType` 与 `businessId` 写颠倒，照样编译，然后错路由。框架自己的测试
就显示了它在调用点长什么样：

```java
// TransportContextTest
runtime.dispatchRpc(10, 2, 2, 33, 100, 1, 100, metadata, new RpcMessage(…));
```

七个连排的数字，其中两个数值相同而含义不同，而调用点没有任何东西说明哪个是 slot、哪个是 command、哪个是
requestId、哪个是 routeKey、哪个是 businessId。整个仓库里这个方法只有两个调用方——这个测试和 `GameRpc`——而它在
两处都已经不好读。

### 4.4 三个仓储互相不一致，而这个不一致是承重的

| | `SingleRepository` | `GroupRepository` | `LogRepository` |
| --- | --- | --- | --- |
| 读 | `get(K)` | `getGroup(GroupKeys)`、`get(GroupKeys, K)` | —— |
| insert | `void insert(E)` | `void insert(E)` | **`boolean insert(E)`** |
| update | `void update(E)` | `void update(E)` | —— |
| delete | **`void delete(K key)`** | **`void delete(E entity)`** | —— |

两个问题。`delete` 在一个兄弟类型里收 key，在另一个里收实体——而这恰恰是写错就会删错东西的地方，属于静默误用
隐患。以及 `insert` 在三个兄弟里有一个返回 `boolean`。

那个 `boolean` 不是疏忽——它是三者中唯一能够拒绝的，因为 `LogRepository` 是唯一有容量的那个
（`logQueueCapacity`，默认 100,000）。另外两个之所以无法拒绝，是因为它们背后的脏集合无界。这直接接上了
[缺口分析 §3.1](2026-10-17-framework-gap-analysis.zh-CN.md)：**给脏集合加界会迫使 `insert`/`update` 长出一个
拒绝信号，而那是对 Data API 中最常用方法的破坏性变更。**现在就把返回约定定下来——趁还没有应用照着 `void` 写——
不花什么成本；以后再定，成本摊给每一个调用方。

### 4.5 只有 RPC 有带命名空间的错误码别名

`RpcErrorCodes.TIMEOUT` 是 `FrameworkErrorCodes.RPC_TIMEOUT` 的别名，是派生而非重抄，所以数值不会漂移。但
Runtime 与 Data 没有对应物：`DefaultGameRuntime`、`RuntimeTimers`、`WriteBehindManager` 都直接 import
`FrameworkErrorCodes`。于是 RPC 的码有两种公共拼法，其他组件的只有一种；而 `FrameworkErrorCodes.describe(2003)`
打到日志里是 `RPC_TIMEOUT(2003)`，读者手里用来比对的常量却写作 `RpcErrorCodes.TIMEOUT`。要么每个组件都给这层
别名，要么把现有这一个去掉。

### 4.6 `HttpResultCodec.encode` 抛裸 `Exception`

> **2026-10-17 按所有者意见随 §3.1 一并暂缓：HTTP 暂时不看。**

```java
@FunctionalInterface
public interface HttpResultCodec { byte[] encode(Object result) throws Exception; }
```

一个公共函数式接口声明 `throws Exception`，会把每个调用方逼进 `catch (Exception)`，从而把
`InterruptedException` 和任何无关的受检异常和编码失败一起吞掉。一个专用的非受检 codec 异常，或者 `IOException`，
能让失败保持可读。

### 4.7 两个 builder 把 `builder()` 放在了 builder 上而不是产物上

`RpcNode.builder()`、`NetworkServer.builder()`、`NetworkClient.builder()`、`HttpServer.builder()` 与
`Metadata.builder()` 在产物上。`GameDataBuilder.builder()` 与 `GameRuntimeBuilder.builder()` 在 builder 类上，
于是读者必须先知道 builder 的名字才能开始。`GameRuntime` 是接口，但 Java 从 8 起就允许接口静态方法，所以并没有
什么迫使它成为例外。

### 4.8 一个只在散文里说明可空的访问器，而它的兄弟校验了每个字段

`ClientHandlerContext.connection()` 的文档写着"连接是借用的，**可能不存在**"，而
`DefaultClientHandlerContext` 存它时没有任何 null 检查。它的兄弟 `DefaultRpcHandlerContext` 在构造器里校验了
`sourceNodeId != 0`、`sourceSlotId` 在 0..254、`command != 0`。于是同一个包里，一个 Context 拒绝每个非法字段，
另一个在业务代码最常伸手去取的那个字段上接受 null。业务代码得凭一句 Javadoc 去做 null 检查。

### 4.9 次要：Context 家族用 15 个类型表达六种形状

八个接口（`Context`、`InvocationContext`、`HandlerContext`、`ClientHandlerContext`、`RpcHandlerContext`、
`EventContext`、`RouteCallContext`、`TimerContext`）加七个 `Default*` 类。大部分分层是称职的，而
`TimerContext extends Context` 而不是继承 `InvocationContext` 是一次*有意义*的收窄——定时器确实没有业务身份和
Metadata，类型把这点说出来了。`RouteCallContext extends InvocationContext {}` 什么都没加，是纯标记；框架里没有
任何地方对它做分派，不过应用代码可以用 `instanceof` 区分跨 Route 调用帧与 handler 帧。这条只为完整性记录，不是
要改的东西。

## 5. 建议顺序

按"代价是否随时间上升"分组。

**在应用照着写之前定下来**——下面每一条都是改名或改签名，现在免费，以后破坏兼容：

1. 把 `RpcCallback` 塑造成它实际的样子：一个附件类型，完成方法放到真正会完成它的那个类型上（§4.1）。
2. 定下失败回调的名字，全部接口统一一种拼法（§4.2）。
3. 定下 Data 写入结果的约定——须知给脏集合加界会需要一个拒绝信号；并让 `SingleRepository` 与 `GroupRepository`
   的 `delete` 在"收 key 还是收实体"上对齐（§4.4）。
4. 决定 `dispatchRpc` 与 `RpcHandlerContext` 是留在 Runtime 的 API 里，还是搬进一个非 Spring 应用也能用的
   适配模块（§3.2）。
5. 收窄或包装分发重载集，至少是 `dispatchRpc`（§4.3）。

**便宜且不破坏兼容，随时可做：**

6. 把 `builder()` 搬到 `GameData` 与 `GameRuntime` 上（§4.7）。
7. 给 `ClientHandlerContext.connection()` 加校验或 `Optional` 包装，或者在 Runtime Java 规范里写明那条保护
   约定（§4.8）。
8. 给每个组件都配错误码别名，或者去掉 `RpcErrorCodes`（§4.5）。

**设计决策，不隐含任何行动：**

9. `RpcNodeBuilder` 是否该接受一个 handler 工厂，从而闭合 Router 的构造环（§3.3）。
10. Data 仓储是否该由容器构造而不是由框架构造（§3.4）。

**2026-10-17 按所有者意见暂缓——HTTP 暂不在范围内：**

- 把 Runtime 的 HTTP codec 默认值改惰性、把 jackson-databind 标为 optional（§3.1），以及它本可解开的
  `game-runtime-http` 拆分。
- 替换 `HttpResultCodec.encode` 上的 `throws Exception`（§4.6）。

两条连证据一并保留。等 HTTP 重新进入范围时再议；若有使用方需要不带 JSON 库的 game-runtime，可提前再议。

## 6. 本记录的验证状态

§2.1 的依赖表、§3.1 的文件与行数、§4.1 的实现表、§4.2 的回调命名表、§4.4 的仓储表与 §4.7 的 builder 普查，
都是通读本工作树的源码得出的，所有引用的签名与 Javadoc 原句都抄自其中。本文没有任何运行期测量：§3.1 关于
"构造 `GameRuntimeBuilder` 会初始化一个 ObjectMapper"的结论，是从字段初始化式及其触达的静态字段读出来的，
不是来自性能剖析或类加载跟踪。没有任何 API 变更被当作紧急事项提出，所有发现都不是正确性缺陷——§5 的排序依据是
"推迟是否会让它更贵"，不是严重程度。
