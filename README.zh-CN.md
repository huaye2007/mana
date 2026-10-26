[English](README.md) | 中文

# managame

基于 Java 25 和 Netty 4.2 的游戏服务器组件。每个组件都是独立的项目，业务按需选用：

| 项目 | 依赖 | 内容 |
| --- | --- | --- |
| [managame-common](managame-common/README.zh-CN.md) | 无 | `Metadata`、`MetadataKey` 与共用的错误码 |
| [managame-network](managame-network/README.zh-CN.md) | Netty | TCP / TLS / ws / wss 服务端、HTTP/1.1 服务端与自动重连客户端，可接入任意 Netty handler |
| [managame-runtime](managame-runtime/README.zh-CN.md) | common、network | 所有业务逻辑经同一个入口执行，同一 domain 与路由键的任务逐个执行：按 domain 路由、注解处理器（`Dispatcher`、`EventBus`、`HttpRoutes`）、拦截器与定时器 |
| [managame-rpc](managame-rpc/README.zh-CN.md) | common、network | 服务器进程之间的调用：`RpcNode`、`RpcPeer`、心跳与重连 |

runtime 和 rpc 互不知道对方。同时使用两者的业务在自己的代码里把它们连起来：自己的 `RpcHandler` 把对端请求交给自己的 `Dispatcher`，自己的 `RpcCallback` 把后续工作交给自己的 `GameRuntime` 执行。

## 构建

每个项目单独构建，按依赖顺序安装：

```bash
cd managame-common && mvn install
cd ../managame-network && mvn install
cd ../managame-runtime && mvn install
cd ../managame-rpc && mvn install
```

测试会打开真实的回环连接。贡献者与编码代理的约定见 [AGENTS.md](AGENTS.md)。
