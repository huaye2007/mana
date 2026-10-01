# game-rpc

[English](README.md) | **[简体中文](README.zh-CN.md)**

OGBS RPC 的 Java 25 实现，Maven 坐标 cn.managame:game-rpc。依赖 game-core 和 game-network；内部 TCP、多 Slot、主动/被动 Peer、call/notify/reply、握手、心跳、固定延迟重连。当前源码缺陷及验证缺口见 [Java 规范 9.1](../docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md#91-审阅确认的缺陷与规模风险)。RPC 不依赖 Runtime 或业务 codec。

## 规范

| 标准规范 | Java 开发规范 |
| --- | --- |
| [OGBS RPC Specification](../docs/ogbs/OGBS-RPC-1.0.zh-CN.md) | [RPC Java 25 Development Specification](../docs/ogbs/OGBS-RPC-Java-25-Specification-1.0.zh-CN.md) |

字节布局：[RPC Wire Profile](../docs/rpc-wire.zh-CN.md)。共享 Metadata/错误码：[Core](../docs/ogbs/OGBS-Core-1.0.zh-CN.md)。

## 使用

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

以上 handler、encodedBody、MyResponse 是应用提供的对象/类型。addPeer 异步维护连接，刚注册时 call 可能立即 onFail(UNAVAILABLE)，不会等待连接或自动重试。当前 Builder 没有 maxPendingCalls 或 reconnectJitter，在途调用没有配置接纳上限；超时失败直接在共享维护时间轮执行，慢处理会延迟其他期限和恢复。重连默认固定 1000ms。这些源码限制记录在 Java 规范中，本次审阅不改变生产行为。

公开入口位于 cn.managame.rpc.node；消息在 message，Handler/Callback 在 call，状态在 transport，错误在 error，Wire 在 netty。Peer/Slot/PendingCall 均保持内部封装。

参数及生命周期校验通过后，call/notify/reply 消费 body 的一个引用，即使发送失败也会释放。入站 body 仅在 Handler 中借用；异步使用或转交 reply 必须 retain/copy。远端所有错误由 RpcHandler.onResponse 解释，本地失败走 onFail。

start/close 是同步管理操作，不能在本节点 Handler、EventLoop 或时间轮内调用。close 会等待自有资源释放，不等待应用另行投递的业务任务。

## 示例与验证

可运行示例及其执行测试统一维护在 [game-example](../game-example/README.zh-CN.md)，game-rpc 只发布框架代码。[RpcEchoExample](../game-example/src/main/java/cn/managame/example/rpc/RpcEchoExample.java) 旨在通过真实本机 TCP 和两个随机端口演示 body retain 与泛型 callback 解码；当前 maxPendingCalls(1024) 无法针对还原后的 Builder 编译，可运行验证待恢复。

```shell
mvn -pl game-rpc -am test
mvn clean verify
```

保留的测试描述 wire、路由、引用计数、完成竞争、真实 TCP 与重连覆盖，但当前 RPC 套件仍引用已移除 API，编译失败；示例也引用不存在的 API。这些入口不代表已通过验证。生产容量、跨语言互操作未验证；自动 Runtime 接入、服务发现与 RPC TLS/WS 配置未提供。
