# game-network

[English](README.md) | **[简体中文](README.zh-CN.md)**

## 规范文档

| 标准规范（语言无关） | Java 开发规范 |
| --- | --- |
| [OGBS Network Specification](../docs/ogbs/OGBS-Network-1.0.zh-CN.md) | [OGBS Network Java Development Specification](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.zh-CN.md) |

组件行为与 Java 实现分别维护在上述两份规范中，本文提供使用入口。

JDK 25 + Netty 的 TCP、TLS TCP、Binary WebSocket、WSS 连接组件，统一发布为 `cn.managame:game-network`。

Netty 的薄封装：通过 pipeline(...) 添加原生 handler，在自己的 ConnectionHandler 中接收解码后的消息、事件和生命周期回调。连接索引、会话、重连和批量关闭由应用负责。

语义见 [Network Specification](../docs/ogbs/OGBS-Network-1.0.zh-CN.md)，完整签名、默认值和生命周期见 [Java 开发规范](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.zh-CN.md)。

## 最小服务端

```java
import cn.managame.network.connection.*;
import cn.managame.network.netty.NetworkServer;
import io.netty.buffer.ByteBuf;
import java.net.InetSocketAddress;

ConnectionHandler handler = new ConnectionHandler() {
    public void onConnected(Connection c) {}
    public void onMessage(Connection c, Object message) {
        ByteBuf reply = ((ByteBuf) message).retain();
        if (c.write(reply) != WriteStatus.ACCEPTED) reply.release();
    }
    public void onDisconnected(Connection c) {}
    public void onException(Connection c, Throwable cause) {
        cause.printStackTrace();
        c.close();
    }
};
NetworkServer server = NetworkServer.builder()
        .bindAddress(new InetSocketAddress(9000))
        .handler(handler)
        .build();
server.start();
// 应用停服生命周期中调用 server.close()。
```

没有 decoder 时 TCP 收到的是字节流片段。业务 framing、codec、IdleStateHandler 通过 pipeline(...) 注册；ChannelOption 直接配置底层 Netty。

WebSocket 服务端添加 `.webSocket("/game")`，客户端用 `NetworkClient.builder().webSocket().handler(handler).build()`，然后 `client.connect(URI.create("ws://localhost:9000/game"))`。TLS 由两端通过 `pipeline(p -> p.addFirst("ssl", ...))` 显式添加原生 SslHandler；WSS 必须配置，不自动创建默认 TLS context。信任和主机名校验由调用方配置，见[原生 TLS 示例](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.zh-CN.md#native-tls-configuration)。

WS/WSS 握手直接使用 Netty 默认超时，当前 10 秒；正常握手成功立即通知 onConnected。服务端不协商子协议，不提供额外的 WS 超时或子协议 Builder 配置。可识别的接入断开和协议拒绝只输出 DEBUG 摘要，未知/配置错误保留 ERROR 堆栈；完整边界见 [Java 规范](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.zh-CN.md)。

## 包与所有权

- connection：Connection、ConnectionHandler、WriteStatus。
- connector：ConnectCallback、WebSocketConnectOptions。
- error：NetworkException。
- netty：Server/Client 与 Builder，以及包级内部实现。

NetworkChannelInitializer 装配管线；TLS 由调用方配置，WebSocketTransport 提供二进制 WS Profile。ConnectionHandlerAdapter 直接接收原生 TLS/WS 完成事件，检查入口状态并调用 onConnected，随后完成客户端结果；不增加中间就绪 Promise 或组合握手结果。末端 adapter 同时保证断开前的最后一次解码交付。Client 仅用一个原生 Promise 承接每次建连结果；两个入口均不保存连接或未完成尝试集合。以上均保持包级封装。原 sslContext(...) Builder 方法已移除，改用 pipeline(...)；详见 Java 规范第 6 章。

ConnectionHandler 的 onMessage 借用消息；框架最终 release。write 返回 ACCEPTED 后所有权转给 Netty；INACTIVE/NOT_WRITABLE 不接管。属性直接用 Netty AttributeKey，不再维护 attribute 包。

Server/Client 只关闭自己创建的 EventLoopGroup。外部 group 由应用关闭；成功连接交给业务持有。同步 start/connect/close 不可阻塞自己的 EventLoop；回调中关闭单条连接用 Connection.close()。

Server/Client close 停止入口并仅回收自有 EventLoopGroup，不枚举 Channel。外部 group 下，未完成握手自行完成或超时，届时若观察到入口关闭就拒绝交付；已有 Connection 由应用管理。已开始交付与 close 的竞争规则见规范。

## 运行与验证

```shell
mvn -pl game-network -am test
mvn clean verify
```

可运行示例及其执行测试统一维护在 [game-example](../game-example/README.zh-CN.md)，game-network 只发布框架代码。可在 IDE 运行 [NetworkEchoExample](../game-example/src/main/java/cn/managame/example/network/NetworkEchoExample.java)。示例使用随机端口、长度 framing 与普通字符串，并完整释放连接和网络资源。

测试覆盖 TCP/TLS/WS/WSS、握手失败、引用计数、背压、关闭与中断竞争。测试自行创建临时证书，不要求外部服务；未验证公网部署、native transport 或生产容量。
