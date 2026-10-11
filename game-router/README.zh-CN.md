# game-router

[English](README.md) | **[简体中文](README.zh-CN.md)**

基于进程现有唯一 RpcNode 的 Java 25 路由。依赖 game-rpc，提供权威绑定、全连接快照/增量、精确 Node 寻址、服务/key 动态寻址和服务广播。

| 规范 | Java 开发规范 |
| --- | --- |
| [OGBS Router](../docs/ogbs/OGBS-Router-1.0.zh-CN.md) | [Router Java 25](../docs/ogbs/OGBS-Router-Java-25-Specification-1.0.zh-CN.md) |

[GameRouter](src/main/java/cn/managame/router/node/GameRouter.java) 负责装配。forRouterNode 返回 Router 成员/查询方法；forServiceNode 返回具体类 [ServiceRouting](src/main/java/cn/managame/router/node/ServiceRouting.java)，同一个对象直接提供 register/bind/unbind/unregister 和业务 call/notify/broadcast/reply。控制操作通过 RpcCallback<Integer> 返回 0 或正框架错误码。先创建路由，作为普通 RpcHandler 传给 RpcNodeBuilder.handler，再调用 routing.start(rpc) 和 rpc.start()；应用拥有两者生命周期，先 routing.close()，再 rpc.close()。

连接 Router 和普通服务都使用 rpc.addPeer(id,address,slots)。两端 Router 通过 registerRouter(id) 声明成员；Router 间控制使用单 Slot，服务可以多 Slot。连接事件只表示传输可用性，不是服务上线/下线。暂时断线保留权威注册/绑定及最后提交的远端桶。第三方发现通过 removeNode(id,epoch) 或 unregisterRouter(id) 清理实例权威；路由注销保留共享 RPC Peer。

bind 成功只表示本地接受，不保证集群可见。快照/控制 ACK 推进有限 Peer 本地流，revision 校验发现遗漏增量，恢复时重建快照。服务控制最多 8,192 个排队加一个在途调用；精确发现移除限制有容量上限，Router 重启后发现需重新提供这些事件。[Router Profile](../docs/rpc-wire.zh-CN.md#router-profile-v2) 定义不改变 RPC Wire 的内层 payload。不提供共识、可靠消息、自动发现或 Router 选择器。

路由复用现有 RPC call/notify/reply，不修改 Node Handler，也不使用生命周期钩子。每个角色拥有一个维护任务，校验 Router 代际/注册及同步 revision；协议恢复不重置共享 RPC Peer 或普通调用。Router Profile v2 要求路由参与方一起升级，RPC Wire 不变。

[RouterEchoExample](../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) 展示两 Router 的统一连接方法和回调。测试覆盖真实 TCP 寻址/回复、fan-out、快照/增量顺序、冲突、多连接、发现移除、回调异常及恢复。生产容量、持续过载和跨语言互通未验证。

```shell
mvn -pl game-router -am test
mvn clean verify
```
