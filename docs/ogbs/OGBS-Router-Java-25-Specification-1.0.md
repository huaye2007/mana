# OGBS Router Java 25 Development Specification 1.0

**[English](OGBS-Router-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Router-Java-25-Specification-1.0.zh-CN.md)

Standard: [Router Specification](OGBS-Router-1.0.md). Wire: [Router Profile v5](../rpc-wire.md#router-profile-v5). Status: implemented with local TCP contract tests; production capacity and cross-language interoperability unverified.

## 1. Module, ownership and public boundaries

`cn.managame:game-router:1.0.0-SNAPSHOT` requires JDK 25 and game-rpc, transitively Network/Core/Netty. RPC never depends on Router. A process has one application-owned RpcNode. Both roles are final concrete classes implementing the existing RpcHandler and AutoCloseable: construct the role, pass it to RpcNodeBuilder.handler, call `role.start(rpc)` before `rpc.start()`, and close the role before the Node. start associates the Node once and never starts networking. Null arguments throw NullPointerException; a repeated start or a start after close throws IllegalStateException.

| Public type | Responsibility |
| --- | --- |
| node.GameRouter | Router role: mesh declarations, attachments, location reports and forwarding |
| node.ServiceRouting | Service role: Router declarations, announcements, sending, replies and delivery |
| call.RouterHandler, call.RoutedRequest | Borrowed routed deliveries and routed call completions |

`new GameRouter(RpcHandler directHandler)` creates a Router; `new ServiceRouting(int serviceId, long nodeEpoch, RouterHandler handler, RpcHandler directHandler)` creates a service. The direct handler receives every request that is not Router Profile traffic and every response and failure that is not a routed call's. A handler implementing both interfaces can be passed in both service positions. RouterWire is the package-private codec. There is no other interface/implementation pair, Handler mutation API, connection listener or RPC cleanup hook. **The application owns every RPC Peer**, including the mesh connections and the one connection a service has to its Router: neither role ever calls addPeer or removePeer. Follow the [component responsibility boundaries](OGBS-Router-1.0.md#1-responsibility-and-composition).

## 2. Router role

```java
GameRouter router = new GameRouter(directHandler);
RpcNode rpc = RpcNode.builder().nodeId(102).bindAddress(address).handler(router)
        .writeBufferWaterMark(512 * 1024, 1024 * 1024).build();
router.start(rpc);
rpc.start();
router.addRouter(101);                       // declare the other Routers of the mesh
rpc.addPeer(101, addressOf101, 2);           // this Node has the larger ID, so it opens the pair
// Application management context; normally in finally:
router.close();
rpc.close();
```

`addRouter(id)` declares another Router of the mesh and `removeRouter(id)` stops using it and forgets everything it reported; both require start and an open role (else IllegalStateException) and reject 0 or this Node's own ID (IllegalArgumentException). Only a declared Router may report locations here, and only its reports are trusted: Locations or Unlocate from any other Peer is rejected, and a Call carrying one fails with HANDLER_ERROR. The application opens one connection per pair, from the larger ID to the smaller one, so the smaller side learns its peer from the inbound handshake.

A Router keeps two tables, both bounded by the number of Nodes:

- **Attachments**: one entry per service Node announced on a connected Peer, holding that Node's epoch and service, plus a per-service index so a broadcast and `serviceNodes` visit only that service's Nodes. An announcement (ATTACH, or any routed message the Node sends) adds or replaces the entry; DETACH with the same epoch removes it. RPC reports no disconnects, so the entry of a closed connection is dropped when a send to it fails or capacity runs out.
- **Locations**: one entry per Node another Router reported, holding that Node's epoch, service and the reporting Router. An attachment here always wins: a Node that attaches locally drops any location for it, and a report for a locally attached Node is ignored. A location is usable only while the reporting Router's connection is up, so a mesh link that is down makes the Nodes behind it report PEER_NOT_FOUND instead of disappearing silently. A Data frame from a Router also locates its own sender there (RT-STATE-03), which is what lets the first call across a fresh mesh be answered.

`isAttached(nodeId)` reports an attachment whose Peer is connected; `isReachable(nodeId)` reports an attachment or a usable location; `serviceNodes(serviceId)` returns an immutable snapshot of the Nodes of a service **attached here**. `stats()` returns `GameRouter.Stats(forwarded, passed, targetNotFound, sendRefused, responsesDropped)`, monotonic counts since construction: messages delivered to a Node attached here (each broadcast copy and each response included), messages handed to another Router, messages with no usable location, messages RPC refused for the next hop, and responses whose caller had left or was unreachable. close is idempotent, stops relaying (later Router requests fail with HANDLER_ERROR), reports the withdrawal of its own attachments to the mesh while the connections are still up, and forgets both tables.

Forwarding resolves the target in one order: attached here, then reported by a Router, then failure. A frame that arrived from a Router is only ever delivered locally, never passed on again, which makes the mesh loop-free without a hop count. Only the caller's own Router stamps its inbound call ID into the forwarded request; the second hop passes the ID it received. At most 100,000 attachments are kept: when full, entries of disconnected Peers are dropped first; if it is still full the announcement is rejected and logged (a Call carrying it fails with HANDLER_ERROR).

## 3. Service role

```java
ServiceRouting routing = new ServiceRouting(MATCH, nodeEpoch, routedHandler, directHandler);
RpcNode rpc = RpcNode.builder().nodeId(1).bindAddress(address).handler(routing).build();
routing.start(rpc);
rpc.start();
for (Router r : candidates) routing.addRouter(r.id());   // every Router this Node may use
rpc.addPeer(chosen.id(), chosen.address(), 2);           // the application connects to exactly one
// ... routing.callNode(targetNodeId, request, callback);
// Application shutdown: routing.close(); rpc.close().
```

serviceId is positive and nodeEpoch nonzero; both are fixed for the object, and a restarted process uses a new nodeEpoch. `addRouter(id)` declares a Router this Node may use and `removeRouter(id)` withdraws this Node from it; neither creates, removes or reconfigures the RPC Peer. Both require start and an open role, else IllegalStateException; an invalid ID throws IllegalArgumentException.

This role attaches to **exactly one** Router: the one it is attached to, for as long as that Router is declared and connected, and otherwise the connected declared Router with the lowest ID. Which Router that is therefore follows the Peer the application opened; moving a service means closing one Peer and opening another, and routing follows within one check. An application may keep several Router Peers open as standbys: when the current Router disconnects, the role attaches to another within one check, while a Router with a lower ID coming up or recovering does **not** pull it away. Rationale: a routed Call's outstanding entry lives on the Router connection it left through, but its response follows the caller's current location; switching while the old Router is still connected brings in-flight responses back through the new Router, where no call waits for them, and those calls time out even though the target already ran. Staying also keeps one recovering or flapping Router from pulling every Node of the mesh across at once. Verified by `aNodeStaysOnItsConnectedRouterWhenALowerOneComesUp` in [RouterIntegrationTest](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java). A change withdraws from the previous Router first, so the Node is never announced in two places. `removeRouter` stops sends to that Router at once rather than at the next check.

start begins announcements on one daemon thread named `service-routing`. Every 100 ms it recomputes the current Router and sends ATTACH (epoch, service) as a Notify when the connection is new to it or the last accepted announcement is a second old; a refused ATTACH is retried on the next check. There is no acknowledgement and nothing to restore: a restarted Router learns this Node within a second. Every control operation uses one routeKey, so ATTACH and DETACH travel on one Slot in order, and removeRouter sends DETACH on the announcing thread, so no announcement already under way can follow it. A delivery from a Router that is not declared, or that arrives after close, is rejected and answered with another DETACH, which also repairs an announcement that overtook the withdrawal. close stops the thread, waits for a check in progress, sends DETACH to the current Router and is idempotent; afterwards every operation except close throws IllegalStateException.

## 4. Sending, completion and body ownership

ServiceRouting exposes `notifyNode(nodeId, request)`, `broadcast(serviceId, request)`, `callNode(nodeId, request, callback)`, `callNode(nodeId, request, timeoutMillis, callback)` and `reply(routedRequest, response)`. Outbound requestId must be zero; nodeId nonzero; serviceId positive; timeoutMillis positive and within nanosecond range; otherwise IllegalArgumentException before the body is touched. The default timeout is the RpcNode's callTimeout (normally 5 seconds). Sends run on the caller and only enter RPC.

Every send goes to the one Router this Node is attached to. There is no Router choice, no hash and no warm-up, so one key to one target keeps one path, and its RPC Slot affinity, while that connection and the target's location are unchanged. With no declared Router a send throws IllegalStateException; with none connected it uses the first declared Router, and RPC reports UNAVAILABLE (Notify status or onFail). A Notify's ACCEPTED is admission by that Router's connection, not delivery, and says nothing about the second hop.

A Router answers a Call with PEER_NOT_FOUND only when it can see no location for the target anywhere in the mesh it reaches, so the service **reports that error rather than resending it**: with one connection there is no second path, and a resend would ask the same Router the same question. A Notify in that case is dropped and counted. A call that failed locally (onFail) may have been delivered and is never resent either.

RouterHandler.onRoutedRequest receives a RoutedRequest: the source nodeId and nodeEpoch, the borrowed inner RpcRequest and routerId, the Router that delivered it — which is the Router this Node is attached to, not necessarily the caller's. Deliveries are accepted only from declared Routers and only for this Node's ID and epoch; others are rejected without reaching the handler. reply sends the response back through routerId, which routes it by the caller's nodeId like any other message, to the exact calling incarnation; it requires a nonzero request ID equal to the response ID. A RoutedRequest is immutable and needs a nonzero routerId; keep it to reply later, and retain/copy and later release a body used after the callback. A RuntimeException from onRoutedRequest is rethrown to RPC after a HANDLER_ERROR reply is attempted for a call.

Routed call completion uses the original callback through RouterHandler. `onResponse(targetNodeId, command, response, callback)` receives the target's unwrapped response with its own error and Metadata (HANDLER_ERROR when the target's handler threw), or a positive error a Router returned: PEER_NOT_FOUND when no usable location existed, UNAVAILABLE when a Router could not send to the target or to the next Router, HANDLER_ERROR when it rejected the message as invalid. `onFail(targetNodeId, command, error, callback)` receives local failures: TIMEOUT, UNAVAILABLE on connection loss, NODE_CLOSED, and PROTOCOL_ERROR for a response that is malformed or does not come from the target for this incarnation. Ordinary direct traffic goes through the direct handler.

| Boundary | Ownership |
| --- | --- |
| Invalid arguments or unusable role, before encoding | Caller keeps the body |
| Accepted send, reply or call | One body reference is consumed, including encoding and transport failure; RPC keeps a large body referenced after a successful return |
| Routed delivery and routed response | Borrowed during the callback; never released by the handler |
| Router relay | The inner frame is not decoded: each copy gets a new header and, at the caller's Router, the caller's call ID; inner frames up to 2 KiB are copied, larger ones referenced from the received frame and released by RPC |
| Location report to several Routers | Each send gets its own retained duplicate and the original reference is released once |

## 5. Threads and bounds

The data path takes no locks: forwarding only reads the two lock-free tables. Changes to the attachment table (a new announcement or a changed epoch/service, DETACH, removal after a failed send, capacity cleanup, close) update the table and the per-service index together under one short lock, and changes to the location table take the same lock; a repeated identical announcement or report returns without taking it. A Router forwards on the receiving Netty thread and runs one daemon thread named `game-router-locations`, which every 100 ms reports the complete set of local attachments to a Router whose connection is new and every second to the rest, and forgets what a Router reported once its connection is gone. A service sends on the caller's thread, runs routed callbacks on Netty threads, and announces itself on one daemon thread.

Defaults, without configuration API: announcement and location checks every 100 ms, both repeating every second; 100,000 attachments per Router; 65,535 entries per location report, which is the uint16 count and not the frame limit, so a Router with more attachments than that logs the overflow instead of reporting a truncated set; inner frames up to the RpcNode's `maxFrameSize()` minus 67 bytes, so that the outer RPC frame with the Router header fits the Node's frame limit; the limit is configured once, by RpcNodeBuilder.maxFrameSize, and the Router keeps no constant of its own. Routing-header offsets come from the frame layout constants RpcWire publishes.

Routers never buffer business data: a next hop whose Slots are all above their write-buffer high watermark refuses a routed message, which drops a Notify and fails a Call. Every service's traffic passes through the Routers and all cross-Router traffic of a pair shares one mesh connection, so configure `RpcNodeBuilder.writeBufferWaterMark` on Router Nodes, and on heavily sending services, for the bytes expected in flight, for example 512 KiB / 1 MiB instead of Netty's 32 KiB / 64 KiB; measurements are in the [2026-10-13 review record](../reviews/2026-10-13-router-forwarding-review.md), taken on the earlier design but with the same per-connection behavior.

## 6. Exceptions, compatibility and validation

Null required inputs throw NullPointerException. Invalid IDs, services, epochs, timeouts or reply IDs throw IllegalArgumentException. Use before start, after close or without a declared Router throws IllegalStateException. Router Profile v5 replaces v1–v4 entirely: the Data envelope is unchanged from v4 but Data now also flows between Routers, Locations and Unlocate are added, and a service attaches to one Router instead of all of them. Other versions are rejected, with no mixed-version fallback. RPC Wire framing, types and handshake are unchanged. Upgrade routing participants together; ordinary RPC participants remain compatible.

Sources: [GameRouter](../../game-router/src/main/java/cn/managame/router/node/GameRouter.java), [ServiceRouting](../../game-router/src/main/java/cn/managame/router/node/ServiceRouting.java), [RouterWire](../../game-router/src/main/java/cn/managame/router/node/RouterWire.java). Validation: [RouterIntegrationTest](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java), [RouterWireTest](../../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java). [RouterEchoExample](../../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) demonstrates a two-hop echo across a two-Router mesh and the resource order. Run `mvn -pl game-router -am test` and the root `mvn clean verify` for integration.

<a id="7-known-limits"></a>

## 7. Known limits

This section lists only boundaries that still hold. Review processes and evidence are kept in the non-normative records of [2026-10-07](../reviews/2026-10-07-router-rpc-review.md) and [2026-10-13](../reviews/2026-10-13-router-forwarding-review.md).

- **Convergence gap**: a Node that attaches is reported to the mesh at once, but a report lost in flight is only repaired by the next complete refresh, so a location can be up to a second stale. A Notify to a target whose location has not arrived is dropped and counted as targetNotFound.
- **Failover is the application's**: a service whose Router is gone is unreachable until the application opens a Peer to another Router and that Router's locations propagate. Neither role chooses or changes a Peer.
- **A mesh link down isolates a pair**: with both Routers up but the connection between them gone, the Nodes behind it report PEER_NOT_FOUND. There is no relay through a third Router, no hop count and no path selection.
- **Late announcements**: a message still in flight from an old incarnation can re-announce it until the next announcement of the current incarnation or until its connection closes; one sent just before close or removeRouter can re-announce the Node until its next delivery from that Router, which it answers with DETACH.
- **Ordering**: one key to one target keeps its order only while the sender's connection and the target's location are unchanged; a target that moves to another Router does not keep order across the move.
- **Two hops**: a target on another Router costs a second hop and a second Router's CPU. A deployment where most traffic is cross-Router pays that on most messages; the connection count is what it buys.
- **Call admission**: a routed Call is registered on the service Node as an RPC pending call to its Router, so RPC R-SEND-04's per-Peer limit (RpcNodeBuilder.maxPendingCalls) applies; no Router ever buffers business messages or keeps call state.
- **Connections**: `services + Router pairs`, each with the configured Slots.
- **Mesh trust**: a declared Router is trusted for the locations it reports, and the RPC handshake authenticates cluster membership rather than a specific identity, so a cluster member configured as a Router can report any location. Per-node keys are outside V1.
- **Spring composition**: managed RPC composes the roles through `GameRpcConfigurer.decorate/attach/detach` ([Spring Java specification](OGBS-Spring-Java-25-Specification-1.0.md#managed-rpc)); dispatching routed requests into Runtime and keeping the RoutedRequest for replies remain application code.
- Production throughput, memory and partition behavior are unverified.
