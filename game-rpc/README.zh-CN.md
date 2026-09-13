# game-rpc

[包结构、职责与 import 迁移](../docs/package-layout.md)

[English](README.md) | 简体中文

基于 game-network 的 Java RPC 实现，使用 JDK 25 / Netty 4.2.15.Final。管理 NodeId → RpcPeer、TCP 连接、Call/Send、显式回复、响应匹配、超时、Metadata 和消息编解码。

game-rpc 不识别 Router 服务，不查询业务路由，不自动转发或解包消息。业务服务发现、消息分发及业务对象编解码由使用方处理。

**实现状态（2026-09-12）：** Java Binding Draft 0.14。286 项测试通过（Core 251、Netty/TCP 35）。支持错误参数、逐 Slot 重连退避和可选的接收端读空闲关闭。

RpcNode 负责对外 API 和组件生命周期；RpcCalls 管理调用，RpcMessages 统一处理消息，RpcConnections 管理连接及主动建连状态。RpcPeer 只保存逻辑节点身份、已发布连接、PendingCall 和生命周期，不保存地址、重连计时器或连接回调。每条物理连接的 Handler 同时维护握手身份，不再有独立 Binding；正常收发不按 connectionId 查表。RpcFuture 继续只保存单次调用状态。 通用消息规则对自定义 Codec 同样生效。握手准入与编码在连接管理锁外执行，恢复后重新检查候选是否有效。

自定义 RpcTransport 使用 `start(Listener)`、`connect(InetSocketAddress, ConnectCallback)` 和 `write(Connection, ByteBuf)`；同步启停前使用 `checkLifecycleThread()` 检查线程。默认 Netty Provider 已同步更新。

## 文档

- [Game RPC 标准](docs/OGBS%20Game%20RPC%20Specification%20v1.md)：语言无关的行为和默认 Wire。
- [Java 实现文档](docs/java/Java%20Implementation%20Specification.md)：本项目 API、状态归属、并发、引用管理和验收。
- [Wire 样例](docs/OGBS%20Game%20RPC%20v1%20wire%20vectors.json)：逐字节测试使用的共享数据。

## 创建和连接

引入 game-rpc-netty 后自动加载默认 Provider 和 Codec。

```java
var rpc = RpcNode.builder()
        .nodeId(10)
        .listen("127.0.0.1", 7000)
        .handler(handler)
        .defaultTimeout(Duration.ofSeconds(3))
        .build();
rpc.start(); // 同步初始化 TCP 客户端并绑定监听
rpc.connect(20, "127.0.0.1", 7001); // 后台建立直接连接
```

心跳默认启用：每条物理连接由主动连接方在握手后 5 秒发送首次 PING，收到 PONG 后再等 5 秒；等待回复最多 15 秒。接收方自动在原连接回复，不进入业务 handler、不占用 RpcFuture。超时只关闭并重连该 Slot。可在 Builder 配置 `.heartbeatInterval(Duration.ofSeconds(5))`、`.heartbeatTimeout(Duration.ofSeconds(15))`，最少 100ms。自定义 Codec 需要支持 RpcHeartbeat 的 PING/PONG，旧节点升级后再互通。

默认不因 idle 事件关闭连接。若需要接收端通过网络读空闲清理失活连接，可在 Builder 显式设置 `.readIdleTimeout(Duration.ofSeconds(30))`；设置为 `Duration.ZERO` 保持关闭。该功能复用 game-network 的 READ_IDLE/onIdle，只关闭失活的原物理连接，保留 Peer。超时应覆盖对端心跳间隔、心跳等待时间及调度余量。

首次建连立即调度。后续每 Slot 独立指数退避并加入随机偏移，握手成功后重置；默认重试等待依次为 0.5～1 秒、1～2 秒、2～4 秒，最大 15～30 秒。可通过 `.reconnectDelay(Duration.ofSeconds(1))` 和 `.maxReconnectDelay(Duration.ofSeconds(30))` 配置初始及最大上限，最大上限不能小于初始值。重复 connect 不绕过待执行的重试。

需要指定连接数和就绪通知时，使用以下重载代替上面的 connect 调用：

```java
rpc.connect(20, "127.0.0.1", 7001, 2, new ConnectCallback() {
    public void onSuccess(Connection connection) {
        // 此节点对的全部 Slot 首次就绪。
    }
    public void onFailure(Throwable failure) {
        // 移除、关闭或明确的方向冲突；普通网络失败会延迟重试。
    }
});
```

同一个不同节点对只能有一方主动连接，物理连接可双向收发；接收端不需要 acceptPeer/addPeer。peer(nodeId) 查询已登记相邻节点，未知目标不由发送接口隐式创建。rpc.removePeer(nodeId) 结束该 Peer，rpc.close() 释放节点自有资源。

## Call、Send 和回复

```java
ByteBuf body = Unpooled.wrappedBuffer(new byte[] {1, 2, 3});
try {
    var options = RpcOptions.builder()
            .routeKey(123L)
            .putLong((short) 1024, 456L)
            .timeout(Duration.ofSeconds(1))
            .build();
    rpc.call(20, 100, body, options, result -> {
        if (result.isSuccess()) {
            RpcResponse response = result.value();
            // 在回调内使用完整响应头、Metadata 和原始 body。
        } else {
            RpcError error = result.error(); // 框架和业务错误统一为非 null RpcError
            var errorArgs = result.errorArgs(); // Immutable, empty for local failures.
            Throwable cause = result.cause(); // Optional local cause; never sent on the wire.
        }
    });
} finally {
    body.release();
}
```

```java
ByteBuf noticeBody = Unpooled.wrappedBuffer(new byte[] {4, 5});
try {
    boolean accepted = rpc.send(20, 101, noticeBody, RpcOptions.DEFAULT);
} finally {
    noticeBody.release();
}

// 在接收处理逻辑中显式回复；responseBody 由调用者编码并管理。
rpc.reply(connection, new RpcResponse(
        request.requestId(), 0, RpcMetadata.EMPTY, responseBody));

// 或按业务处理结果回复错误，参数是有序字符串数组。
rpc.reply(connection, RpcResponse.error(request.requestId(), GameErrors.NOT_ENOUGH_GOLD, "1000", "300"));
```

错误参数由默认 Codec 编码在 body 区域，不增加消息头或 Metadata 字段；无参数默认空数组。框架保留错误码 1～1000，业务从 1001 开始。所有失败均有非 null result.error()，只有成功时为 null。带参数错误响应需要双方 Codec 支持新格式，成功和无参数错误帧不变。

业务错误使用不可变 RpcError 常量：

```java
public final class GameErrors {
    public static final RpcError NOT_ENOUGH_GOLD = new RpcError(1001, "金币不足");
    private GameErrors() {}
}
```

RpcError 按 code 比较，可用 `GameErrors.NOT_ENOUGH_GOLD.equals(result.error())` 判断。message 是本地说明，不上网传输；接收端未知错误码的 message 为空，code 保留。不需要向 RpcNode 注册业务错误。

同一 Peer 最多保留 64 个等待连接就绪的回调；额外的带回调 connect 同步抛出 OVERLOADED。普通网络失败会自动重连，不必反复注册回调；不带回调的重复 connect 不增加等待者。

结果、连接和诊断回调都在产生事件的线程直接通知，game-rpc 不通过内部线程池切换回调线程。业务层负责投递自己的业务线程；回调应及时返回。超时通知可能来自时间轮线程，移除/关闭通知在资源清理、退出生命周期锁后由调用线程执行。节点自有时间轮与网络 EventLoop 上调用同步 start/close 会在状态修改前拒绝，业务层需自行投递关闭操作。首次取得关闭权的调用同步清理资源，并发或重入 close 幂等返回。

示例中的 body/responseBody 应由调用者编码并管理自己的引用。Call 自动分配正 int requestId；Send 通知使用 0。targetNodeId 必须是登记的直接相邻节点，未知目标回调 UNAVAILABLE 或返回 false。routeKey 只选择该 Peer 内的连接 Slot，不查询下一跳。

用户入口保持统一：

```java
public interface RpcHandler {
    void handleUserMsg(Connection connection, Object msg);
}
```

普通请求是完整 RpcRequest，未知 command 也会交付。command 为非零有符号 int32，支持负数协议号；0 非法，requestId 仍为调用正数、单向消息 0。响应由相邻 Peer 的 RpcFuture 匹配后通知结果。Handler 返回不自动回复，用户自行决定回复时机和前后消息顺序。

RpcRouteMessage 只是原始信封，仍只有 sourceNodeId、targetNodeId、ByteBuf inner。收到后原样交给 Handler，不自动解码 inner、不判断目标、不自动转发、不完成内层 Response。业务可以通过 `send(connectedNodeId, message, options)` 显式发送完整消息；此入口不登记调用，仅使用 options.routeKey 选连接，其余消息头由 message 自身提供。

## 分阶段停止调用

rpc.stopCalls() 不可恢复地停止新出站 Call 和 Send 通知，认领 pending 并以 UNAVAILABLE 通知调用方，保留连接和对已接受入站请求的 reply 能力。可以重复调用，回调在调用线程、内部锁外执行。并发响应或超时可能已认领某个结果；stopCalls 不等待该回调返回，不取消远端业务，也不停止入站请求分发。

游戏服的关闭顺序为：停止应用准入、stopCalls、排空业务 Runtime、最后关闭 RPC 和客户端传输。一个节点由一个适配器协调；停止会影响该节点的全部出站调用。直接 close 仍可用于立即关闭。

RpcResult.errorArgs() 返回不可修改的远端错误参数列表，本地失败和成功结果返回空列表，无需检查 value 是否为空。cause() 保留可获得的本地提交/调度异常，不编码到网络，也不能附加到远端响应。两参数 RpcResult 构造器保留源码兼容；record 结构变更后需要重新编译调用方。

## 内存与配置

发送使用连接的 active/writable 状态，不维护额外的待发送字节预算。`RpcLimits` 为四个参数：帧、Metadata、body 和 Pending 上限；旧配置移除最后的 `maxOutboundBytes` 参数。

- RpcRequest.body、成功 RpcResponse.body、RpcRouteMessage.inner 在同步 Handler/结果回调内借用。异步持有必须 copy 或 retain，并最终 release。
- encode/send/reply 不消费调用者输入引用或改变输入索引。信封输出可能引用 inner，发送完成前不能改写底层字节。
- RpcMetadata 使用独立 GC 管理的堆存储，用户无需释放；提供 putInt/putLong/putString/putBoolean 及 get，内部实现位于 RpcMetadataUtil。
- Metadata 单值长度为 0～127 字节；入站校验不使用 ThreadLocal。前 8 个 Key 用局部变量查重，更多字段按需分配本次校验专用位图。
- 请求编号在同一 RpcNode 内连续推进，Peer 重建不重置；完整回绕或跨运行实例的旧消息隔离仍需外部保证。
- maxPendingHandshakes 默认 1024，限制同时进行的入站/出站握手；maxPeers 默认 4096。本地 start 完成前拒绝提前到达的握手，发起方重试；首次入站握手全部失败时自动回收临时 Peer 名额；曾有任意 Slot 建立成功的 Peer 在断线后仍保留，离开集群时由业务调用 removePeer。
- 默认每次即时刷新。消息量较大时可配置 `consolidateFlush(true)` 合并刷新，不启用时保持即时发送行为。

## 构建

在工作区根目录执行：

```text
mvn -pl game-rpc/game-rpc-netty -am verify
```

只执行 RPC 用例及依赖构建：

```text
mvn -pl game-rpc/game-rpc-netty -am verify "-Dtest=Rpc*Test" "-Dsurefire.failIfNoSpecifiedTests=false"
```

独立构建前，需要安装或发布同版本 game-network 依赖。当前验证覆盖功能、并发和 ByteBuf 引用管理，尚无生产吞吐/延迟压测结论。


## 性能基线

完成上面的 RPC verify 后运行：

```text
python game-rpc/benchmarks/run.py --requests 200000 --warmup 50000 --repeats 3
```

覆盖小包/大包、单连接/多连接、合并刷新和诊断开关，输出吞吐、延迟分位数、进程分配量及 GC 数据到 `game-rpc/target/benchmark-时间戳.json`。客户端、服务器及测量代码在同一 JVM；这是有界并发的本机 TCP 基线，指标定义和限制见 Java 实现文档。


## Linux Docker 持续与故障测试

本地 Docker 切换为 Linux 引擎，准备 JDK 25 Maven 镜像及工作区 `game-network/.m2` 依赖缓存后，在工作区根目录运行：

```text
python game-rpc/benchmarks/docker_stress.py --prepare --work game-rpc/target/docker-run/work --output game-rpc/target/docker-run/results
```

`--prepare` 要求 work 目录尚不存在。运行器复制源码和缓存，在容器内执行 RPC verify；客户端与服务端使用两个独立 Linux 容器，通过私有 Docker 网络通信。只向本机回环地址发布测试控制端口，结束时清理本次创建的容器和网络。已有 Linux 构建可省略 `--prepare`。

默认约 25 分钟，覆盖即时/合并刷新小包、大包两档在途量、业务错误字符串数组、批量断连、暂停读取、进程暂停和强制重启，以及 paranoid 泄漏检测。`--seconds` 控制主要场景时长，`--business-seconds` 控制低并发大包和业务错误时长，`--leak-seconds` 控制泄漏检测时长；`--only` 可选择场景。故障场景建议不少于 120 秒，留出恢复观察时间。

结果目录包含 `summary.json`、各场景双端日志和 Docker 资源采样。正常场景出现框架错误、内容错误、重复回调、未清理 Pending、未恢复连接或泄漏报告均判失败；故障场景允许 UNAVAILABLE/OVERLOADED/TIMEOUT，但必须恢复并清空 Pending。进程退出码反映总体判定，失败场景仍保留证据。指标与生产容量的区别见 Java 实现文档。
