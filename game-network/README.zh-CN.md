# game-network

[English](README.md) | **[简体中文](README.zh-CN.md)**

## 规范文档

| 标准规范（语言无关） | Java 开发规范 |
| --- | --- |
| [OGBS Network Specification](../docs/ogbs/OGBS-Network-1.0.zh-CN.md) | [OGBS Network Java Development Specification](../docs/ogbs/OGBS-Network-Java-25-Specification-1.0.zh-CN.md) |

组件行为与 Java 实现分别维护在上述两份规范中，本文提供使用入口。

JDK 25 + Netty 的 TCP、TLS TCP、Binary WebSocket、WSS 连接组件，统一发布为 `cn.managame:game-network`。

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

WebSocket 服务端添加 `.webSocket("/game")`，客户端用 `NetworkClient.builder().webSocket().handler(handler).build()`，然后 `client.connect(URI.create("ws://localhost:9000/game"))`。TLS 服务端提供 sslContext；WSS 客户端可以使用默认 JVM 信任库。

## 包与所有权

- connection：Connection、ConnectionHandler、WriteStatus。
- connector：ConnectCallback、WebSocketConnectOptions。
- error：NetworkException。
- netty：Server/Client 与 Builder，以及包级内部实现。

ConnectionHandler 的 onMessage 借用消息；框架最终 release。write 返回 ACCEPTED 后所有权转给 Netty；INACTIVE/NOT_WRITABLE 不接管。属性直接用 Netty AttributeKey，不再维护 attribute 包。

Server/Client 只关闭自己创建的 EventLoopGroup。外部 group 由应用关闭；成功连接交给业务持有。同步 start/connect/close 不可阻塞自己的 EventLoop；回调中关闭单条连接用 Connection.close()。

## 运行与验证

```shell
mvn -pl game-network -am test
mvn clean verify
```

可在 IDE 运行 [NetworkEchoExample](src/main/java/cn/managame/network/example/NetworkEchoExample.java)。示例使用随机端口、长度 framing 与普通字符串，并完整释放连接和网络资源。

测试覆盖 TCP/TLS/WS/WSS、握手失败、引用计数、背压、关闭与中断竞争。测试自行创建临时证书，不要求外部服务；未验证公网部署、native transport 或生产容量。
