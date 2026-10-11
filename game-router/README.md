# game-router

**[English](README.md)** | [简体中文](README.zh-CN.md)

Java 25 routing on the process's existing single RpcNode. Depends on game-rpc and provides authoritative bindings, full-mesh snapshots/deltas, exact Node addressing, dynamic service/key addressing and service broadcast.

| Specification | Java development |
| --- | --- |
| [OGBS Router](../docs/ogbs/OGBS-Router-1.0.md) | [Router Java 25](../docs/ogbs/OGBS-Router-Java-25-Specification-1.0.md) |

[GameRouter](src/main/java/cn/managame/router/node/GameRouter.java) assembles routing. forRouterNode returns Router membership/query methods; forServiceNode returns the concrete [ServiceRouting](src/main/java/cn/managame/router/node/ServiceRouting.java), with register/bind/unbind/unregister and business call/notify/broadcast/reply directly on the same object. Control operations report zero or a positive framework error through RpcCallback<Integer>. Create routing first, pass it as the ordinary RpcHandler to RpcNodeBuilder.handler, then call routing.start(rpc) and rpc.start(). The application owns both lifecycles, calling routing.close() before rpc.close().

Connect Routers and ordinary services through the same rpc.addPeer(id,address,slots). Both Routers declare membership with registerRouter(id); Router-to-Router synchronization requires one Slot, while services may use multiple Slots. A connection event is transport availability, not service online/offline. Temporary connection loss retains authoritative registrations/bindings and the last committed remote bucket. Third-party discovery invokes removeNode(id,epoch) or unregisterRouter(id) to remove instance authority. Routing unregister preserves the shared RPC Peer.

Bind success means local acceptance, not cluster visibility. Each Router streams its own bucket to every other Router as one ordered, unacknowledged session per direction: a snapshot, then batched deltas with consecutive sequence numbers. A gap, loss or silence keeps the last committed replica queryable and requests a fresh snapshot; there is no per-change acknowledgement or replay log. The local bucket and all replicas feed one lock-free index, so forwarding is a local lookup that never waits for synchronization, and replies and routed errors return straight to the caller's Router. Service controls have 8,192 queued slots plus one in-flight call. Exact discovery removal fences are retained with a finite bound; discovery must reapply them after Router restart. [Router Profile](../docs/rpc-wire.md#router-profile-v3) defines an inner payload under unchanged RPC Wire. No consensus, reliable messaging or automatic discovery/Router chooser is provided.

Routing uses existing RPC call/notify/reply without Node mutation or lifecycle hooks. Each role owns a single maintenance task: the Router role keeps its streams moving and recovers them, and the service role verifies its Router incarnation and registration. Recovery never resets shared RPC Peers or ordinary calls. Router Profile v3 requires routing participants to upgrade together; RPC Wire is unchanged.

[RouterEchoExample](../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) demonstrates common connection methods and callbacks across two Routers. Tests cover real TCP addressing/replies, fan-out, snapshot/delta order, conflicts, multiple connections, discovery removal, callback failures, stream gaps, random frame loss, repeated disconnects under churn and a four-Router mesh with restart. Production capacity, sustained overload and cross-language interoperability remain unverified.

```shell
mvn -pl game-router -am test
mvn clean verify
```
