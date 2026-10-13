# OGBS Router Java 25 Development Specification 1.0

**[English](OGBS-Router-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Router-Java-25-Specification-1.0.zh-CN.md)

Standard: [Router Specification](OGBS-Router-1.0.md). Wire: [Router Profile v4](../rpc-wire.md#router-profile-v4). Status: implemented with local TCP contract tests; production capacity and cross-language interoperability unverified.

## 1. Module, ownership and public boundaries

`cn.managame:game-router:1.0.0-SNAPSHOT` requires JDK 25 and game-rpc, transitively Network/Core/Netty. RPC never depends on Router. A process has one application-owned RpcNode. Both roles are final concrete classes implementing the existing RpcHandler and AutoCloseable: construct the role, pass it to RpcNodeBuilder.handler, call `role.start(rpc)` before `rpc.start()`, and close the role before the Node. start associates the Node once and never starts networking. Null arguments throw NullPointerException; a repeated start or a start after close throws IllegalStateException.

| Public type | Responsibility |
| --- | --- |
| node.GameRouter | Router role: relays routed messages between Nodes connected to it |
| node.ServiceRouting | Service role: Router declarations, announcements, sending, replies and delivery |
| call.RouterHandler, call.RoutedRequest | Borrowed routed deliveries and routed call completions |

`new GameRouter(RpcHandler directHandler)` creates a Router; `new ServiceRouting(int serviceId, long nodeEpoch, RouterHandler handler, RpcHandler directHandler)` creates a service. The direct handler receives every request that is not Router Profile traffic and every response and failure that is not a routed call's. A handler implementing both interfaces can be passed in both service positions. RouterWire is the package-private codec. There is no other interface/implementation pair, Handler mutation API, connection listener or RPC cleanup hook. Follow the [component responsibility boundaries](OGBS-Router-1.0.md#1-responsibility-and-composition).

## 2. Router role

```java
GameRouter router = new GameRouter(directHandler);
RpcNode rpc = RpcNode.builder().nodeId(101).bindAddress(address).handler(router)
        .writeBufferWaterMark(512 * 1024, 1024 * 1024).build();
router.start(rpc);
rpc.start();
// Application management context; normally in finally:
router.close();
rpc.close();
```

A Router needs no membership, peer list or registration: services connect to it. It keeps one entry per Node announced on a connected Peer, holding that Node's epoch and service. An announcement (ATTACH, or any routed message the Node sends) adds or replaces the entry; DETACH with the same epoch removes it. RPC reports no disconnects, so the entry of a closed connection is dropped when a send to it fails or capacity runs out. `isAttached(nodeId)` reports an entry whose Peer is connected; `serviceNodes(serviceId)` returns an immutable snapshot of such Nodes of a service. `stats()` returns `GameRouter.Stats(forwarded, targetNotFound, sendRefused, responsesDropped)`, monotonic counts since construction: messages RPC accepted for the next hop (each broadcast copy and response included), requests whose target was not attached, messages to an attached Node that RPC refused, and responses whose caller had left or could not be reached. close is idempotent, stops relaying (later Router requests fail with HANDLER_ERROR) and forgets every entry.

Everything runs on the Netty thread that received the message; the Router has no thread, timer or lock. At most 100,000 entries are kept: when full, entries of disconnected Peers are dropped first; if it is still full the announcement is rejected and logged (a Call carrying it fails with HANDLER_ERROR).

## 3. Service role

```java
ServiceRouting routing = new ServiceRouting(MATCH, nodeEpoch, routedHandler, directHandler);
RpcNode rpc = RpcNode.builder().nodeId(1).bindAddress(address).handler(routing).build();
routing.start(rpc);
rpc.start();
for (Router r : routers) { routing.addRouter(r.id()); rpc.addPeer(r.id(), r.address(), 2); }
// ... routing.callNode(targetNodeId, request, callback);
// Application shutdown: routing.close(); rpc.close().
```

serviceId is positive and nodeEpoch nonzero; both are fixed for the object, and a restarted process uses a new nodeEpoch. `addRouter(id)` declares a Router (nonzero, not this Node; repeated declarations are ignored) and `removeRouter(id)` withdraws this Node from it; neither creates, removes or reconfigures the RPC Peer. Both require start and an open role, else IllegalStateException; an invalid ID throws IllegalArgumentException.

start begins announcements on one daemon thread named `service-routing`. Every 100 ms it checks each declared Router and sends ATTACH (epoch, service) as a Notify when the connection is new to it or the last accepted announcement is a second old; a refused ATTACH is retried on the next check. There is no acknowledgement and nothing to restore: a restarted Router learns this Node within a second. ATTACH and DETACH use one routeKey, so they travel on one Slot in order, and removeRouter sends DETACH on the announcing thread, so no announcement already under way can follow it. A delivery from a Router that is not declared, or that arrives after close, is rejected and answered with another DETACH, which also repairs an announcement that overtook the withdrawal. close stops the thread, waits for a check in progress, sends DETACH to every connected declared Router and is idempotent; afterwards every operation except close throws IllegalStateException.

## 4. Sending, completion and body ownership

ServiceRouting exposes `notifyNode(nodeId, request)`, `broadcast(serviceId, request)`, `callNode(nodeId, request, callback)`, `callNode(nodeId, request, timeoutMillis, callback)` and `reply(routedRequest, response)`. Outbound requestId must be zero; nodeId nonzero; serviceId positive; timeoutMillis positive and within nanosecond range; otherwise IllegalArgumentException before the body is touched. The default timeout is the RpcNode's callTimeout (normally 5 seconds). Sends run on the caller and only enter RPC.

Each send picks one usable declared Router from a hash of the target (Node, or service for broadcast) and routeKey, so one key to one target keeps one Router, and its RPC Slot affinity, while the usable set is unchanged. A Router is usable once the announcing thread has seen its current connection for one second, so other Nodes have had time to announce themselves there after it restarted; while none is usable, any connected Router is used. With no declared Router a send throws IllegalStateException; with none connected it uses the first declared Router, and RPC reports UNAVAILABLE (Notify status or onFail). A Notify's ACCEPTED is admission by that Router's connection, not delivery.

A Router answers a Call with PEER_NOT_FOUND or UNAVAILABLE only when it did not deliver it, so the service resends such a call, unchanged, through another connected Router not yet tried for it; the RPC call ID changes, the business identity does not, and each attempt gets the full timeout. Only when every connected Router has been tried does the handler see that error. A Notify gets no such answer and is never resent; neither is a call that failed locally (onFail), since it may have been delivered. A resent call may overtake later messages of the same key.

RouterHandler.onRoutedRequest receives a RoutedRequest: the source nodeId and nodeEpoch, the borrowed inner RpcRequest and routerId, the Router that delivered it. Deliveries are accepted only from declared Routers and only for this Node's ID and epoch; others are rejected without reaching the handler. reply sends the response back through routerId to the exact calling incarnation; it requires a nonzero request ID equal to the response ID. A RoutedRequest is immutable and needs a nonzero routerId; keep it to reply later, and retain/copy and later release a body used after the callback. A RuntimeException from onRoutedRequest is rethrown to RPC after a HANDLER_ERROR reply is attempted for a call.

Routed call completion uses the original callback through RouterHandler. `onResponse(targetNodeId, command, response, callback)` receives the target's unwrapped response with its own error and Metadata (HANDLER_ERROR when the target's handler threw), or a positive error returned by the last Router tried: PEER_NOT_FOUND when the target was not attached there, UNAVAILABLE when the Router could not send to it, HANDLER_ERROR when it rejected the message as invalid. `onFail(targetNodeId, command, error, callback)` receives local failures: TIMEOUT, UNAVAILABLE on connection loss, NODE_CLOSED, and PROTOCOL_ERROR for a response that is malformed or does not come from the target for this incarnation. Ordinary direct traffic goes through the direct handler.

| Boundary | Ownership |
| --- | --- |
| Invalid arguments or unusable role, before encoding | Caller keeps the body |
| Accepted send, reply or call | One body reference is consumed, including encoding and transport failure; RPC keeps a large body referenced after a successful return. A call also keeps its encoded frame until it completes, for a resend |
| Routed delivery and routed response | Borrowed during the callback; never released by the handler |
| Router relay | The inner frame is not decoded: each copy gets a new header and, for a Call, the caller's call ID; inner frames up to 2 KiB are copied, larger ones referenced from the received frame and released by RPC |

## 5. Threads and bounds

The routing package uses no locks. A Router does all work on the receiving Netty thread. A service sends on the caller's thread and runs routed callbacks on Netty threads; its only own thread announces it. Defaults, without configuration API: announcement check every 100 ms and repeat every second; a Router usable one second after its connection is seen; 100,000 attachments per Router; inner frames up to 4 MiB minus 67 bytes, so that the outer RPC frame with the Router header fits 4 MiB, also subject to the Node's own frame limit.

Routers never buffer business data: a next hop whose Slots are all above their write-buffer high watermark refuses a routed message, which drops a Notify and fails a Call. Every service's traffic passes through the Routers, so configure `RpcNodeBuilder.writeBufferWaterMark` on Router Nodes, and on heavily sending services, for the bytes expected in flight, for example 512 KiB / 1 MiB instead of Netty's 32 KiB / 64 KiB; measurements are in the [2026-10-13 review record](../reviews/2026-10-13-router-forwarding-review.md).

## 6. Exceptions, compatibility and validation

Null required inputs throw NullPointerException. Invalid IDs, services, epochs, timeouts or reply IDs throw IllegalArgumentException. Use before start, after close or without a declared Router throws IllegalStateException. Router Profile v4 replaces v1–v3 entirely: no Router synchronization, registration, binding or routed-error operations remain, and other versions are rejected, with no mixed-version fallback. RPC Wire framing, types and handshake are unchanged. Upgrade routing participants together; ordinary RPC participants remain compatible.

Sources: [GameRouter](../../game-router/src/main/java/cn/managame/router/node/GameRouter.java), [ServiceRouting](../../game-router/src/main/java/cn/managame/router/node/ServiceRouting.java), [RouterWire](../../game-router/src/main/java/cn/managame/router/node/RouterWire.java). Validation: [RouterIntegrationTest](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java), [RouterWireTest](../../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java). [RouterEchoExample](../../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) demonstrates the assembly and resource order. Run `mvn -pl game-router -am test` and the root `mvn clean verify` for integration.

<a id="7-known-limits"></a>

## 7. Known limits

This section lists only boundaries that still hold. Review processes and evidence are kept in the non-normative records of [2026-10-07](../reviews/2026-10-07-router-rpc-review.md) and [2026-10-13](../reviews/2026-10-13-router-forwarding-review.md).

- **Announcement gap**: a service that misses a quick Router restart stays unknown there for up to a second. Senders avoid a newly connected Router for that second and resend calls a Router could not deliver, but a Notify sent through a Router where its target is not yet announced is dropped.
- **Late announcements**: a message still in flight from an old incarnation can re-announce it until the next announcement of the current incarnation or until its connection closes; one sent just before close or removeRouter can re-announce the Node until its next delivery from that Router, which it answers with DETACH.
- **Partial connectivity**: a call to a target not connected to the chosen Router is resent through the others; a Notify is dropped (counted as targetNotFound).
- **Ordering**: one key to one target keeps its order only while the set of usable Routers is unchanged and no call of that key is resent.
- **Call admission**: business Call pending has no finite admission; it depends on RPC R-SEND-04 (unimplemented).
- **Connections**: services × Routers connections, each with the configured Slots.
- **Spring composition**: managed RPC composes the roles through `GameRpcConfigurer.decorate/attach/detach` ([Spring Java specification](OGBS-Spring-Java-25-Specification-1.0.md#managed-rpc)); dispatching routed requests into Runtime and keeping the RoutedRequest for replies remain application code.
- Production throughput, memory and partition behavior are unverified.
