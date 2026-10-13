# game-router

[English](README.md) | **[简体中文](README.zh-CN.md)**

基于进程现有唯一 RpcNode 的 Java 25 无状态 Router。依赖 game-rpc，通过不保存路由状态的 Router 提供精确 Node 寻址和服务广播。

| 规范 | Java 开发规范 |
| --- | --- |
| [OGBS Router](../docs/ogbs/OGBS-Router-1.0.zh-CN.md) | [Router Java 25](../docs/ogbs/OGBS-Router-Java-25-Specification-1.0.zh-CN.md) |

两个具体类就是两个角色。`new GameRouter(directHandler)` 是无状态中继；`new ServiceRouting(serviceId, nodeEpoch, routedHandler, directHandler)` 是服务，同一个对象直接提供 addRouter/removeRouter 和业务 notifyNode/broadcast/callNode/reply。先创建角色，作为普通 RpcHandler 传给 RpcNodeBuilder.handler，再调用 role.start(rpc) 和 rpc.start()；应用拥有两者生命周期，先 role.close()，再 rpc.close()。

每个服务用 rpc.addPeer(id,address,slots) 连接所有 Router，并用 addRouter(id) 声明它们；Router 之间互不连接。Router 只保存每个已连接 Node 声明的身份（epoch、服务），连接断开即忘记：没有路由表、注册、副本、线程或定时器。服务在每条新连接上及每秒声明一次自己，因此 Router 重启后无需恢复。目标是精确 Node ID；把玩家或房间解析为 Node 是发送方的业务逻辑。每次发送按目标与 routeKey 选择一个 Router，新连接的 Router 一秒后才使用，以便其他 Node 先在其上声明；在这一选择不变期间同一键保持顺序。Router 未能投递的调用会经另一个 Router 重发，调用的响应经承载该调用的 Router 返回。GameRouter.stats() 统计已转发和丢弃的消息。Router 只改写路由头，内层 RPC 帧不解码直接传递；Router 从不缓存业务数据，因此 Router Node 的 RPC 写缓冲水位应调得比 Netty 默认 64 KiB 更大。[Router Profile](../docs/rpc-wire.zh-CN.md#router-profile-v4) 定义不改变 RPC Wire 的 payload。不提供服务发现、可靠消息或键目录。

[RouterEchoExample](../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) 装配两个 Router 和两个服务。测试覆盖真实 TCP 的调用/通知/回复、大 body、目标缺失、广播、两个 Router 下的同键顺序、Router 丢失与重启、代际校验、Handler 异常、伪造来源及生命周期。生产容量、持续过载和跨语言互通未验证。

```shell
mvn -pl game-router -am test
mvn clean verify
```
