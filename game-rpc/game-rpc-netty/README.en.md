[中文](README.md) | English

# game-rpc-netty

The Netty transport for `game-rpc`, including the wire codec, connection and peer management, client/server runtime, handshake, heartbeat, reconnect, backpressure, and in-flight call management.

This module depends on `game-rpc-core`, Netty, and game-serialization, and **does not depend on `game-network`**. See [game-rpc](../README.en.md) for the complete usage guide.

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-rpc-netty</artifactId>
    <version>1.0.0</version>
</dependency>
```
