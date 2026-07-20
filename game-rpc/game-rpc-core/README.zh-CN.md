[English](README.md) | 中文

# game-rpc-core

`game-rpc` 的传输无关 API 模块，包含请求/响应、future、callback、异常和指标模型。RPC 与任务上下文统一使用 `game-common` 提供的 `Metadata`。

该模块不依赖 Netty、`game-network` 或 `game-runtime`。只需要构造、传递或解析 RPC 消息模型的模块应直接依赖它：

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-rpc-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

需要可运行的客户端/服务端时，其他项目统一依赖 `game-rpc`。
