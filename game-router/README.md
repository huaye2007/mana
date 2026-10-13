# game-router

**[English](README.md)** | [简体中文](README.zh-CN.md)

Java 25 stateless Routers on the process's existing single RpcNode. Depends on game-rpc and provides exact Node addressing and service broadcast through Routers that keep no routing state.

| Specification | Java development |
| --- | --- |
| [OGBS Router](../docs/ogbs/OGBS-Router-1.0.md) | [Router Java 25](../docs/ogbs/OGBS-Router-Java-25-Specification-1.0.md) |

Two concrete classes are the two roles. `new GameRouter(directHandler)` is a stateless relay; `new ServiceRouting(serviceId, nodeEpoch, routedHandler, directHandler)` is a service, with addRouter/removeRouter and business notifyNode/broadcast/callNode/reply directly on the same object. Create the role first, pass it as the ordinary RpcHandler to RpcNodeBuilder.handler, then call role.start(rpc) and rpc.start(). The application owns both lifecycles, calling role.close() before rpc.close().

Every service connects to every Router with rpc.addPeer(id,address,slots) and declares it with addRouter(id); Routers never connect to each other. A Router keeps only the identity (epoch, service) each connected Node announces, and forgets it with the connection: no routing table, registration, replica, thread or timer. A service announces itself on each new connection and every second, so a restarted Router needs no recovery. Targets are exact Node IDs; resolving a player or room to a Node is the sender's business logic. Each send picks one Router from the target and routeKey, using a newly connected Router only after one second so other Nodes have announced themselves there; one key keeps its order while that choice stays the same. A call that a Router could not deliver is resent through another Router, and a call's response returns through the Router that carried it. GameRouter.stats() counts forwarded and dropped messages. A Router rewrites only the routing header and passes the inner RPC frame on undecoded. It never buffers business data, so give Router Nodes a larger RPC write-buffer watermark than Netty's 64 KiB default. [Router Profile](../docs/rpc-wire.md#router-profile-v4) defines the payload under unchanged RPC Wire. No discovery, reliable messaging or key directory is provided.

[RouterEchoExample](../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) assembles two Routers and two services. Tests cover real TCP calls/notifies/replies, large bodies, missing targets, broadcast, per-key ordering over two Routers, Router loss and restart, incarnation checks, handler errors, spoofed sources and lifecycle. Production capacity, sustained overload and cross-language interoperability remain unverified.

```shell
mvn -pl game-router -am test
mvn clean verify
```
