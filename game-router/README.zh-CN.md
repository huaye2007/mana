# game-router

[English](README.md) | **[简体中文](README.zh-CN.md)**

基于进程现有唯一 RpcNode 的 Java 25 Router。依赖 game-rpc，通过两级网格提供精确 Node 寻址和服务广播：服务只附着一个 Router，各 Router 之间互相告知"哪些 Node 附着在我这里"。

| 规范 | Java 开发规范 |
| --- | --- |
| [OGBS Router](../docs/ogbs/OGBS-Router-1.0.zh-CN.md) | [Router Java 25](../docs/ogbs/OGBS-Router-Java-25-Specification-1.0.zh-CN.md) |

两个具体类就是两个角色。`new GameRouter(directHandler)` 是转发器，带用于网格的 addRouter/removeRouter；`new ServiceRouting(serviceId, nodeEpoch, routedHandler, directHandler)` 是服务，同一个对象直接提供 addRouter/removeRouter 和业务 notifyNode/broadcast/callNode/reply。先创建角色，作为普通 RpcHandler 传给 RpcNodeBuilder.handler，再调用 role.start(rpc) 和 rpc.start()；应用拥有两者生命周期，先 role.close()，再 rpc.close()。

服务用 addRouter(id) 声明它可以使用的 Router，由应用用 rpc.addPeer(id,address,slots) 连接其中**一个**；该角色只要当前 Router 仍连着就留在它上面，否则附着到已连接的已声明 Router 中 ID 最小的那个，因此迁移一个服务就是迁移它的 Peer，多开的 Router Peer 只作备用、不会把已连着的 Node 拉走。Router 之间同样用 addRouter 互相声明，每一对由 ID 较大的一方开那一条连接。Router 保存两张表，规模都由 Node 数量决定：每个已连接 Node 声明的身份（epoch、服务），以及其他 Router 上报的位置。服务在每条新连接上及每秒宣告一次自己；Router 每秒向网格上报自己附着的完整集合，并在某个 Node 附着或离开时立即补一条，因此没有任何东西需要确认、重试或恢复。目标是精确 Node ID；把玩家或房间解析为 Node 是发送方的业务逻辑。目标附着在本地时直接投递，否则一次性交给上报它的那个 Router，绝不经第三个 Router；来自 Router 的帧只会被本地投递，这使网格无需跳数即无环。响应与其他消息一样按调用方 nodeId 路由，因此没有任何帧携带"来自哪里"。PEER_NOT_FOUND 的含义是本 Router 所能触及的网格里没有该目标的位置，因此它被上报而不是重发。GameRouter.stats() 统计已投递、已转交与已丢弃的消息。Router 只改写路由头，内层 RPC 帧不解码直接传递；Router 从不缓存业务数据，因此 Router Node 的 RPC 写缓冲水位应调得比 Netty 默认 64 KiB 更大。[Router Profile](../docs/rpc-wire.zh-CN.md#router-profile-v5) 定义不改变 RPC Wire 的 payload。不提供服务发现、可靠消息、键目录或自动故障转移：为服务选择 Router、以及该 Router 消失后迁移它，都是应用的决定。

[RouterEchoExample](../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) 装配一个两 Router 网格，两侧各一个服务，因此它的回显要跨两跳。测试覆盖真实 TCP 的调用/通知/回复、大 body、目标缺失、两跳转发、位置上报与删除、跨网格的广播扇出、同键顺序、网格链路断开、把服务迁到另一个 Router、Router 丢失与重启、代际校验、Handler 异常、伪造来源、未授权的位置上报及生命周期。生产容量、持续过载和跨语言互通未验证。

```shell
mvn -pl game-router -am test
mvn clean verify
```
