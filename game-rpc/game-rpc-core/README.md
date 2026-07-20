[中文](README.zh-CN.md) | English

# game-rpc-core

Transport-neutral APIs for `game-rpc`, including requests/responses, futures, callbacks, exceptions, and metrics. RPC and task contexts share the `Metadata` type provided by `game-common`.

This module depends on neither Netty, `game-network`, nor `game-runtime`. Modules that only construct, pass, or inspect RPC message models should depend on it directly:

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-rpc-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

Other projects should depend on `game-rpc` for the runnable client/server implementation.
