# game-rpc

[English](README.md) | **[简体中文](README.zh-CN.md)**

OGBS RPC 的 Java 25 实现，Maven cn.managame:game-rpc，依赖 game-core、game-network。提供内部 TCP、多 Slot、主动/被动 Peer、call/notify/reply、握手、心跳和带额外随机延迟的重连。Node 级 ID、通知隔离、恢复交接及验证边界见 [Java 规范 9.1](../docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md#91-审阅确认的缺陷与规模风险)。RPC 核心不依赖 Runtime/业务 codec，可选 Spring 适配由 game-spring 提供。

## 规范

| 标准规范 | Java 开发规范 |
| --- | --- |
| [OGBS RPC Specification](../docs/ogbs/OGBS-RPC-1.0.zh-CN.md) | [RPC Java 25 Development Specification](../docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md) |

字节布局：[RPC Wire Profile](../docs/rpc-wire.zh-CN.md)。共享 Metadata/错误码：[Core](../docs/ogbs/OGBS-Core-1.0.zh-CN.md)。

## 使用

通过 Builder 配置普通 RpcHandler，请求/响应/失败分派在 Node 构造时固定。Peer 可用性/数量快照支持上层校验，不增加生命周期监听器。[game-router](../game-router/README.zh-CN.md) 使用同一 Handler 及 call/notify/reply；应用显式关闭路由与 RPC。Router 自有协议校验/恢复保留共享 Peer 和普通 pending。服务存在性归发现，见 [Java Handler 组合](../docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md#direct-forwarding-and-handler-composition)。

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

handler、encodedBody、MyResponse 均由应用提供。addPeer 异步维护连接，刚注册时 call 可能立即 onFail(UNAVAILABLE)，不会等待或自动重试。有限 Call 接纳暂缓；超时通知使用自有、关闭时等待的虚拟线程，不阻塞维护时间轮。重连基础默认 1000ms，额外随机上限默认基础值的 1/4；reconnectRandomDelay(Duration.ZERO) 使用固定延迟。该配置分散重试，恢复权竞争另由 CAS 交接修复。

公开入口位于 cn.managame.rpc.node；消息在 message，Handler/Callback 在 call，状态在 transport，错误在 error，Wire 在 netty。Peer/Slot/PendingCall 均保持内部封装。

参数及生命周期校验通过后，call/notify/reply/forward 消费 body 的一个引用，即使发送失败也会释放。入站 body 仅在 Handler 中借用；异步使用或转交 reply 必须 retain/copy。远端所有错误由 RpcHandler.onResponse 解释，本地失败走 onFail。

start/close 是同步管理操作，不能在本节点 Handler、EventLoop 或时间轮内调用。close 会等待自有资源释放，不等待应用另行投递的业务任务。

## 示例与验证

可运行示例及其执行测试位于 [game-demo](../game-demo/README.zh-CN.md)，RPC artifact 只发布框架。[RpcEchoExample](../game-demo/src/main/java/cn/managame/demo/examples/rpc/RpcEchoExample.java) 在真实本地 TCP/动态端口演示 body retain 与 callback 解码。对象分发/回复及 Route 回调用法见 [Spring 规范](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#managed-rpc)。

```shell
mvn -pl game-rpc -am test
mvn clean verify
```

Wire、引用计数、完成竞争、真实 TCP、Peer 重建、恢复权竞争和超时隔离具有本地契约测试。RouterRecoveryTest 覆盖多 Slot 远端重启、回调重入和显式关闭；RouterIntegrationTest 覆盖重新同步保留普通在途调用。集成变更要求根 clean verify。有限调用准入暂缓，生产容量/跨语言互通未验证。可选 Runtime 接入在 game-spring；核心无发现或 RPC TLS/WS 配置。
