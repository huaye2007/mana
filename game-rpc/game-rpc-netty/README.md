[English](README.en.md) | 中文

# game-rpc-netty

`game-rpc` 的 Netty 传输实现，包含线路编解码、连接与 peer 管理、客户端/服务端、握手、心跳、重连、背压和在途调用管理。

该模块依赖 `game-rpc-core`、Netty 和 game-serialization，**不依赖 `game-network`**。完整使用说明见 [game-rpc](../README.md)。

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-rpc-netty</artifactId>
    <version>1.0.0</version>
</dependency>
```
