[English](README.md) | 中文

# managame

基于 Java 25 和 Netty 4.2 的游戏服务器框架。所有业务逻辑（玩家请求、事件、定时器、HTTP 调用、其它服务器的请求）都经同一个入口执行，domain 和路由键相同的任务按顺序逐个执行。

| 项目 | 内容 |
| --- | --- |
| [managame-common](managame-common/README.zh-CN.md) | `Metadata`、`MetadataKey` 与框架错误码 |
| [managame-network](managame-network/README.zh-CN.md) | TCP / TLS / ws / wss 服务端、HTTP/1.1 服务端与自动重连客户端，可接入任意 Netty handler |
| [managame-runtime](managame-runtime/README.zh-CN.md) | `GameRuntime`、按 domain 路由、注解处理器（`Dispatcher`、`EventBus`、`HttpRoutes`）、拦截器与定时器 |
| [managame-rpc](managame-rpc/README.zh-CN.md) | 服务器进程之间的调用：`RpcNode` 与 `RpcPeer` |

## 构建

每个项目单独构建，按依赖顺序安装：

```bash
cd managame-common && mvn install
cd ../managame-network && mvn install
cd ../managame-runtime && mvn install
cd ../managame-rpc && mvn install
```

测试会打开真实的回环连接。贡献者与编码代理的约定见 [AGENTS.md](AGENTS.md)。
