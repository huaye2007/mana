# game-router

**[English](README.md)** | [简体中文](README.zh-CN.md)

Java 25 Routers on the process's existing single RpcNode. Depends on game-rpc and provides exact Node addressing and service broadcast through a two-tier mesh: a service attaches to one Router, and the Routers tell each other which Nodes are attached to them.

| Specification | Java development |
| --- | --- |
| [OGBS Router](../docs/ogbs/OGBS-Router-1.0.md) | [Router Java 25](../docs/ogbs/OGBS-Router-Java-25-Specification-1.0.md) |

Two concrete classes are the two roles. `new GameRouter(directHandler)` is a forwarder, with addRouter/removeRouter for the mesh; `new ServiceRouting(serviceId, nodeEpoch, routedHandler, directHandler)` is a service, with addRouter/removeRouter and business notifyNode/broadcast/callNode/reply directly on the same object. Create the role first, pass it as the ordinary RpcHandler to RpcNodeBuilder.handler, then call role.start(rpc) and rpc.start(). The application owns both lifecycles, calling role.close() before rpc.close().

A service declares the Routers it may use with addRouter(id), and the application connects it to **one** of them with rpc.addPeer(id,address,slots); the role attaches to the connected declared Router with the lowest ID, so moving a service means moving its Peer. Routers declare each other the same way, and the larger ID opens the one connection of each pair. A Router keeps two tables, both bounded by Node count: the identity (epoch, service) each connected Node announced, and the locations other Routers reported. A service announces itself on each new connection and every second; a Router reports its complete set of attachments to the mesh every second, plus an immediate entry when a Node attaches or leaves, so nothing has to be acknowledged, retried or restored. Targets are exact Node IDs; resolving a player or room to a Node is the sender's business logic. A message is delivered when its target is attached here and otherwise passed once to the Router that reported it, never through a third Router; a frame from a Router is only ever delivered locally, which makes the mesh loop-free without a hop count. A response is routed by the caller's nodeId like any other message, so no frame carries where it came from. PEER_NOT_FOUND means the mesh this Router reaches has no location for the target, so it is reported rather than resent. GameRouter.stats() counts delivered, passed and dropped messages. A Router rewrites only the routing header and passes the inner RPC frame on undecoded. It never buffers business data, so give Router Nodes a larger RPC write-buffer watermark than Netty's 64 KiB default. [Router Profile](../docs/rpc-wire.md#router-profile-v5) defines the payload under unchanged RPC Wire. No discovery, reliable messaging, key directory or automatic failover is provided: choosing a service's Router, and moving it when that Router is gone, is the application's decision.

[RouterEchoExample](../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) assembles a two-Router mesh with one service on each, so its echo crosses two hops. Tests cover real TCP calls/notifies/replies, large bodies, missing targets, two-hop forwarding, location reports and removal, broadcast fan-out across the mesh, per-key ordering, a mesh link down, moving a service to another Router, Router loss and restart, incarnation checks, handler errors, spoofed sources, unauthorised location reports and lifecycle. Production capacity, sustained overload and cross-language interoperability remain unverified.

```shell
mvn -pl game-router -am test
mvn clean verify
```
