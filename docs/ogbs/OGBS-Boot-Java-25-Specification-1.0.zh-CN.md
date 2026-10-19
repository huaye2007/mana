# OGBS Boot Java 25 开发规范 1.0

[English](OGBS-Boot-Java-25-Specification-1.0.md) | **[简体中文](OGBS-Boot-Java-25-Specification-1.0.zh-CN.md)**

配套文档：[服务器组合语义](OGBS-Boot-1.0.zh-CN.md)。已实现构件 `cn.managame:game-boot:1.0.0-SNAPSHOT`，Java 25，依赖 game-runtime 与 game-data，game-rpc 为可选依赖；Netty 经 game-runtime 传递而来。不依赖任何容器，这正是 [B-COMPOSE-01](OGBS-Boot-1.0.zh-CN.md#1-这一层是什么) 可被检查的原因：此处 classpath 上出现容器类型就是缺陷。

## 1. 模块布局

| 包 | 内容 | 所需依赖 |
| --- | --- | --- |
| `cn.managame.boot.rpc` | `GameRpc`、`GameRpcCodec`、`GameRpcConfigurer` | game-rpc（可选依赖；使用本包需显式声明） |
| `cn.managame.boot.metrics` | `ObservabilityEndpoints`、`ReadinessCheck`、`FrameworkMetrics` | game-core 读数、game-runtime HTTP，以及各自适配所需的 game-data 与 game-rpc |
| `cn.managame.boot.lifecycle` | `ShutdownBudget` | 除 JDK 外无其他 |
| `cn.managame.boot.http` | `HttpContextPath` | game-runtime HTTP、game-network |

应用可直接依赖 game-boot，也可从容器集成（如 game-spring）继承而来。上表中每个类型都是 public 且可构造的；它们都不读取配置，因此属性名归提供取值的那个集成所有。

<a id="2-rpc-adapter"></a>

## 2. RPC 适配器

`new GameRpc(GameRuntime, GameRpcCodec, RpcNodeBuilder, List<GameRpcConfigurer>)` 就是这处拼装。按 [B-COMPOSE-03](OGBS-Boot-1.0.zh-CN.md#1-这一层是什么)，它自己按列表顺序应用全部 configurer 钩子：对 builder 调用 `configure`，用 `decorate` 包裹自己的 `RpcHandler`，`build`，然后 `attach`。调用方提供的 builder 已带有 node id、绑定地址与可选的 call 超时，且不得在其上设置 handler——适配器最后安装自己的那个。`GameRpc` 实现 `AutoCloseable`；`close()` 按逆序 detach 后关闭 Node，幂等。`node()` 暴露受管 `RpcNode`，用于拓扑管理（`addPeer`）、`localAddress()` 与 `start()`。

`attach` 失败时，按逆序 detach 已经 attach 的那些 configurer，把任何 detach 失败作为 suppressed 异常附加，关闭这个尚未启动的 Node 并重新抛出。`decorate` 返回 null 时抛出指名该钩子的 `NullPointerException`。不要通过 `node()` 发送裸的 `call`/`notify`/`reply`：回调关联是本适配器的私有机制。需要裸 API 的应用使用另一个独立的 `RpcNode`。

| 成员 | 契约 |
| --- | --- |
| `<T> void call(targetNodeId, routeKey, businessIdType, businessId, metadata, request, RouteCallback<T>)` | 要求处于本 Runtime 当前 Route 上，且存在 REQUEST 到响应的绑定；`T` 必须与该绑定一致。注册 `runtime.callback`，并为成功与失败恢复确切的来源 Context/Route，包括 Peer 缺失导致的立即拒绝。远端错误码与本地 RPC 失败进入 `onFail`；无法解码的响应变为 `PROTOCOL_ERROR` |
| `RpcSendStatus notify(targetNodeId, routeKey, businessIdType, businessId, metadata, request)` | 不要求当前 Route，且不可被回复 |
| `RpcSendStatus reply(Object response)` | 使用 `Contexts.current(RpcHandlerContext.class)` |
| `RpcSendStatus reply(RpcHandlerContext, Object response)` | 保存的不可变信封允许延迟回复，而无需保留传输体 |
| `RpcSendStatus replyError(RpcHandlerContext, int positiveCode)` | 空体错误响应；非正错误码抛 `IllegalArgumentException` |

每次回复都必须匹配原请求已注册的响应类，并要求 `requestId` 非零；Notify 的零 `requestId` 抛 `IllegalArgumentException`。回复携带空响应 Metadata，并保留来源 Slot 偏好与替换连接回退。`ACCEPTED` 表示传输接受，不表示远端完成。

接缝处的所有权。回调注册**之前**的序列化或绑定失败只抛异常，不占用任何资源。当 `RpcNode` 在注册**之后**同步抛出时，适配器用 `CapturedRouteCallback.cancel()` 释放占用并重新抛出，因此调用方只被告知一次，也不会遗留被占用的续体。这个只传对象的回调不暴露响应 Metadata；需要传输信封的应用使用裸 `RpcNode`。

入站。Call 与 Notify 的命令查找都使用 `ProtocolDescriptor.Type.REQUEST`；Notify 即 `requestId` 为零，而不是另行注册。解码后的请求把来源 Node/Slot、命令、`requestId`、Route Key、业务身份与收到的确切 Metadata 传给 `runtime.dispatchRpc`，由它按 Handler 声明选择 Domain。未知命令、编解码异常以及同步的准入、签名或 Key 失败，对 Call 走 `RpcHandler` 的 `HANDLER_ERROR` 策略，对 Notify 走仅记录日志的策略。

`GameRpcCodec` 暴露 `byte[] encode(Object)` 与 `<T> T decode(byte[], Class<T>)`，外加默认方法 `ByteBuf encode(Object, ByteBufAllocator)` 与 `<T> T decode(ByteBuf, Class<T>)`。适配器只调用 ByteBuf 形式，其默认实现包装或拷贝后委托给数组形式；可以覆写它们以直接编码进（池化）缓冲，并从收到的帧解码而省去中间拷贝。传给 ByteBuf 解码的 body 是**借用**的，结果不得引用它。编解码器必须支持传输线程与 Route 线程并发调用，返回确切注册类型的非 null 值，并返回与传输缓冲生命周期无关的数组与对象。出站数组在返回后归适配器所有，不得再修改或复用。解码在准入之前同步进行，因此慢解码会拖慢传输 EventLoop。业务序列化是应用策略；框架不定义格式。

`GameRpcConfigurer` 是 `@FunctionalInterface`，唯一抽象方法为 `configure(RpcNodeBuilder)`；`decorate`、`attach`、`detach` 默认为空实现。`decorate` 正是 `GameRouter` 或 `ServiceRouting` 获得受管 handler 作为其直连 handler 的途径——列表中最后一个 configurer 位于最外层。被路由的请求于是到达路由 handler 而不是 `runtime.dispatchRpc`，其解码、分发与精确回复（用保存的 `RoutedRequest` 调用 `ServiceRouting.reply`）仍属应用代码。

```java
var runtime = GameRuntimeBuilder.builder()./* domains、executors、protocols、handlers */build();
var builder = RpcNode.builder().nodeId(1).bindAddress(new InetSocketAddress("127.0.0.1", 9100));
try (runtime; var rpc = new GameRpc(runtime, codec, builder, List.of(routingConfigurer))) {
    rpc.node().start();
    rpc.node().addPeer(2, peerAddress, 1);
    // Handler 通过自己的字段、提供者，或应用已有的任何方式拿到 rpc。
}
```

## 3. 关闭预算

实现 [B-CLOSE-01 与 B-CLOSE-02](OGBS-Boot-1.0.zh-CN.md#3-关闭预算)。`ShutdownBudget` 是由三个被配置阶段组成的记录 `(Duration total, Duration lead, Duration flushReserve)`，`drain()` 是由它们算出的访问器，不是第四个分量：记录的规范构造器无论作者是否愿意都是 public 的，若把排空时间存成分量，调用方就能传入与总和矛盾的值，而这个类型存在的唯一保证也就没了；改为计算使这种状态无法构造。`ShutdownBudget.of(totalMillis, leadMillis, flushReserveMillis)` 是读取三个配置值的集成所用的毫秒形式。

| 阶段 | 常量 | 默认值 | 含义 |
| --- | --- | --- | --- |
| 总预算 | `DEFAULT_TOTAL_MILLIS` | 25 000 | 部署需让编排器宽限期高于它 |
| 前导 | `DEFAULT_LEAD_MILLIS` | 0 | 就绪报告失败而准入仍开放 |
| 刷写保留 | `DEFAULT_FLUSH_RESERVE_MILLIS` | 10 000 | 为 Data 最后一次持久化保留 |
| 排空 | *推导* | 15 000 | `总预算 − 前导 − 刷写保留`，供已准入工作使用 |

25 000 取在 Kubernetes 宽限期默认的 30 秒之下；`docker stop` 只给 10 秒，使用它的部署应相应调低总预算。规范构造器与 `of` 都会在总预算或保留为 null、非正、前导为负，以及任何使排空小于等于零的切分时抛出 `IllegalArgumentException`——于是超额的预算在配置处就失败，而不是很久以后截短一次关闭。排空为 1 毫秒是合法切分。消息指名记录分量；读取这些取值的集成自己说明它们来自哪个配置项。

运行该序列的一方拥有它需要的唯一一点可变状态：一个表示"本实例已开始退出轮转"的标志，在准入关闭之前置位，并可从回答就绪探针的线程读取。该标志留在运行方那里，不在本模块里另立类型，因为本模块没有别的东西读它，另设一个持有者只会给同一个布尔值再起一个名字。§4 是它变成 `shutdown.rotation` 条件的地方。

## 4. 可观测性

实现 [B-OBS-01 至 B-OBS-05](OGBS-Boot-1.0.zh-CN.md#4-observability-composition)。

| 类型 | 角色 |
| --- | --- |
| `FrameworkMetrics` | 针对 `GameRuntime`、`GameData`、`RpcNode`、`NetworkServer` 的 `public static MetricsSource of(...)`；各取一次快照并报告其字段 |
| `ReadinessCheck` | `String name()`、`boolean ready()`。必须用已持有的状态作答；不得阻塞或分发 |
| `ObservabilityEndpoints` | `public HttpDispatcher wrap(HttpDispatcher business)`，安装在业务分发之前 |

适配代码放在 `FrameworkMetrics` 而不是各组件中，于是组件永不了解读数契约，game-network 也保持不依赖任何其他框架模块。名字是记录自身字段名的点分形式，置于注册表给定的前缀之下，所以 `runtime` 加 `route.queued` 读作 `runtime.route.queued`。按 Domain 的读数把 Domain 并入名字，因为 Core 的 sink 不携带维度：`domain1.queued`、`domain1.executor.capacity` 等。Route Key 以 gauge 的**取值**发布，绝不进入名字——`running.oldest.domain`、`running.oldest.key` 与 `domain1.running.oldest.key`——因此无论存在多少条 Route，"观察哪条 Route 卡住"都只占一条序列；这正是 Key 在这里安全、而作为维度则不安全的原因。名字分段必须以字母开头，这正是 Domain id 跟在 `domain` 之后而不单独成段的原因，其基数在 build 时固定。用 `registry.publish(prefix, source)` 把每个存在的组件发布在固定前缀 `runtime`、`data`、`rpc`、`network` 之下；应用自建的组件由它自己以同样方式发布。

`new ObservabilityEndpoints(MetricsRegistry, List<ReadinessCheck>, metricsPath, readinessPath)` 校验两个路径：绝对、至少两个字符、不含 `?` 与 `#`、彼此不同，否则抛出指名该参数的 `IllegalArgumentException`。`wrap` 返回的 dispatcher 对这两个路径回答 `GET`，其余一切（包括这两个路径上的其他方法）都传给被包裹的 dispatcher。匹配时忽略查询串。两个回答都在调用它的 HTTP 线程上产生，不进入任何 Route 执行器。

就绪返回 200 与 `ready\n`，或 503 与 `not ready: ` 加上按列表顺序逗号分隔的未成立条件名。抛出 `RuntimeException` 的条件计为不就绪。先用 §3 的轮转标志贡献 `shutdown.rotation`，使退出轮转的实例报告的是这件事本身而不是它的后果，然后是来自 `stats().accepting()` 的 `runtime.accepting` 与 `data.accepting`，最后是应用自己的条件。

`/metrics` 把每条读数按注册顺序渲染为单独一行 `name value`，内容类型 `text/plain; charset=UTF-8`。这刻意不是某种具名暴露格式：需要特定格式的应用实现 `MetricSink` 并映射名字，这正是 Core 契约存在的意义。

## 5. 业务路径前缀

`new HttpContextPath(String value, HttpDispatcher delegate)` 实现 `HttpDispatcher` 与 [B-HTTP-01](OGBS-Boot-1.0.zh-CN.md#5-business-path-prefix)。空值或 `/` 表示根前缀，请求原样穿过；`/game/` 归一化为 `/game`。取值若不是由字母、数字、`.`、`_`、`~`、`-` 构成的原始绝对 ASCII 路径段，或含有空段、`.`、`..` 段、百分号转义、查询串或片段，则在构造时抛出 `IllegalArgumentException`。

对非根前缀：`/game` 与 `/game/` 映射到 `/`；`/game/echo?routeKey=7` 映射到 `/echo?routeKey=7`；`/echo`、`/games/echo`、`/game%2Fecho` 返回 404 且不执行业务。非法 origin-form 或带片段的目标返回 400。分发使用 `request.retainedDuplicate()` 并设置相对 URI，在 `finally` 中释放自己的那次引用（包括委托方抛出时），传输所拥有的原请求及其引用计数保持不变。Runtime 会独立地把已准入请求保留到方法返回；延迟访问仍需自己的所有权。

## 6. 验证

| 入口 | 覆盖 |
| --- | --- |
| [BootRpcCompositionTest](../../game-boot/src/test/java/cn/managame/boot/rpc/BootRpcCompositionTest.java) | §2 在没有容器的情况下经真实 TCP 拼装：入站 Notify 与 Call 的信封与 Domain 选择、经适配器回复、出站 Call 在来源 Route 上完成（成功与 Peer 缺失失败两种）、钩子顺序且 attach 看到未启动的 Node、关闭时 detach，以及 attach 失败后的清理 |
| [ObservabilityEndpointsTest](../../game-boot/src/test/java/cn/managame/boot/metrics/ObservabilityEndpointsTest.java) | §4 路径校验、按列表顺序指名未成立与抛异常的条件、带执行器上界的按 Domain 读数、排空中的 Runtime 返回 503 而读数仍作答、前缀无关性以及非 GET 的穿透 |
| [HttpContextPathTest](../../game-boot/src/test/java/cn/managame/boot/http/HttpContextPathTest.java) | §5 相对目标、委托方抛出时原请求与引用计数不变、被拒绝的前缀 |
| [ShutdownBudgetTest](../../game-boot/src/test/java/cn/managame/boot/lifecycle/ShutdownBudgetTest.java) | §3 推导、默认值落在 30 秒宽限期之下、应被拒绝的切分、阶段只置位一次 |

改动模块组成或 Maven 依赖后运行根目录 `mvn clean verify`，使陈旧 class 文件无法掩盖被破坏的边界。未验证：生产环境资源耗尽、卡死的存储驱动、TLS 与原生安全扩展、部署安全、负载下的 RPC 容量。B-CLOSE-02 的前导时间需要触发点，因此在拥有触发点的容器集成中验证。
