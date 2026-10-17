# game-rpc

[English](README.md) | **[简体中文](README.zh-CN.md)**

OGBS RPC 的 Java 25 实现，Maven cn.managame:game-rpc，依赖 game-core、game-network。提供内部 TCP、多 Slot、主动/被动 Peer、call/notify/reply、握手、心跳和带额外随机延迟的重连。一对节点只能有一方调用 addPeer；可配置共享密钥做 HMAC 握手鉴权，并通过 writeBufferWaterMark、channelOption、transport 配置写缓冲上限、socket 选项和 TLS 等传输 handler（合并 flush 已内置）。Peer 失去最后一条连接时，其未完成调用立即以 UNAVAILABLE 失败。Node 级 ID、通知隔离、恢复交接及验证边界见 [Java 规范 9.1](../docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md#91-审阅确认的缺陷与规模风险)。RPC 核心不依赖 Runtime/业务 codec，可选 Spring 适配由 game-spring 提供。

## 规范

| 标准规范 | Java 开发规范 |
| --- | --- |
| [OGBS RPC Specification](../docs/ogbs/OGBS-RPC-1.0.zh-CN.md) | [RPC Java 25 Development Specification](../docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md) |

字节布局：[RPC Wire Profile](../docs/rpc-wire.zh-CN.md)。共享 Metadata/错误码：[Core](../docs/ogbs/OGBS-Core-1.0.zh-CN.md)。

## 使用

通过 Builder 配置普通 RpcHandler，请求/响应/失败分派在 Node 构造时固定。Peer 可用性/数量快照支持上层校验，不增加生命周期监听器。[game-router](../game-router/README.zh-CN.md) 使用同一 Handler 及 call/notify/reply；应用显式关闭路由与 RPC。路由从不重置共享 Peer 或普通 pending。服务存在性归发现，见 [Java Handler 组合](../docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md#direct-forwarding-and-handler-composition)。

```java
try (RpcNode node = RpcNode.builder()
        .nodeId(1)
        .bindAddress(new InetSocketAddress("127.0.0.1", 9000))
        .handler(handler)
        .build()) {
    node.start();
    node.addPeer(2, new InetSocketAddress("127.0.0.1", 9001), 2);
    node.call(2, new RpcRequest(1001, encodedBody), (MyResponse response) -> {
        // 应用 RpcHandler 负责解码后调用本 callback。
    });
}
```

handler、encodedBody、MyResponse 均由应用提供。addPeer 异步维护连接，刚注册时 call 可能立即 onFail(UNAVAILABLE)，不会等待或自动重试。有限 Call 接纳暂缓；超时通知在 Node 的计时器线程上执行，较长的处理应交给自己的执行器。重连基础默认 1000ms，额外随机上限默认基础值的 1/4；reconnectRandomDelay(Duration.ZERO) 使用固定延迟。该配置分散重试，恢复权竞争另由 CAS 交接修复。

公开入口位于 cn.managame.rpc.node；消息在 message，Handler/Callback 在 call，状态在 transport，错误在 error，Wire 在 netty。Peer/Slot/PendingCall 均保持内部封装。

参数及生命周期校验通过后，call/notify/reply/forward 消费 body 的一个引用，即使发送失败也会释放。入站 body 仅在 Handler 中借用；异步使用或转交 reply 必须 retain/copy。远端所有错误由 RpcHandler.onResponse 解释，本地失败走 onFail。

start/close 是同步管理操作，不能在本节点 Handler、EventLoop 或时间轮内调用。close 会等待自有资源释放，不等待应用另行投递的业务任务。

## 示例与验证

可运行示例及其执行测试位于 [game-demo](../game-demo/README.zh-CN.md)，RPC artifact 只发布框架。[RpcEchoExample](../game-demo/src/main/java/cn/managame/demo/examples/rpc/RpcEchoExample.java) 在真实本地 TCP/动态端口演示 body retain 与 callback 解码。对象分发/回复及 Route 回调用法见 [Spring 规范](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#managed-rpc)。

```shell
mvn -pl game-rpc -am test
mvn clean verify
```

Wire、引用计数、完成竞争、真实 TCP、Peer 重建、恢复权竞争和超时隔离具有本地契约测试。RouterIntegrationTest 覆盖与普通 RPC 共用 Node 的路由、Router 丢失与重启。集成变更要求根 clean verify。有限调用准入暂缓，生产容量/跨语言互通未验证。可选 Runtime 接入在 game-spring；核心无发现或 RPC TLS/WS 配置。

`RpcNode.stats()` 返回一份 `RpcStats` 快照。四个 gauge 由 Peer 表遍历得出——Peer 数、已连接 Peer 数、连接数、待完成调用数——另有八个累计 counter：已发起、因 Peer 级上限被拒、超时的调用数，已发送帧数，被拒发送数，已接收请求数，已接纳握手数，以及重连次数。其中三条回答的是任何错误码都不回答的问题：待完成调用数对照 `maxPendingCalls` 说明 Node 距离拒绝还有多远，`sends.refused` 解释 Notify 为何消失，而已连接 Peer 数低于 Peer 数说明某个邻居不在了——这一点 RPC 从不宣告。按 Peer 的细分与延迟都不上报。见 [RPC §12](../docs/ogbs/OGBS-RPC-1.0.zh-CN.md#12-可观测性) 与 [Java §10](../docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md#10-stats)。
