# OGBS Router Java 25 Development Specification 1.0

**[English](OGBS-Router-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Router-Java-25-Specification-1.0.zh-CN.md)

Standard: [Router Specification](OGBS-Router-1.0.md). Wire: [Router Profile v2](../rpc-wire.md#router-profile-v2). Status: implemented with local TCP contract and recovery tests; production capacity and cross-language interoperability unverified.

## 1. Module, ownership and public boundaries

`cn.managame:game-router:1.0.0-SNAPSHOT` requires JDK 25 and game-rpc, transitively Network/Core/Netty. RPC never depends on Router. A process has one application-owned RpcNode. GameRouter and ServiceRouting are final concrete classes implementing the existing RpcHandler and AutoCloseable. Construct the routing role first, pass it to RpcNodeBuilder.handler, then call routing.start(rpc) before rpc.start(). start associates that Node once and starts routing maintenance; it never starts networking. The application must supply this role as the Node's Handler; start does not inspect or replace RPC's private Handler. Null arguments throw NullPointerException; repeated start or start after close throws IllegalStateException.

| Public type | Responsibility |
| --- | --- |
| node.GameRouter | Role assembly, forwarding Router membership, route inspection and ordinary RpcHandler dispatch |
| node.ServiceRouting | Service registration, bindings, addressing, replies and ordinary RpcHandler dispatch |
| call.RouterHandler, call.RoutedRequest | Borrowed business delivery and business/registration callbacks |
| route.BindingKey, RouteBinding, NodeRegistration | Validated immutable values |
| error.RouterErrorCodes | Core numeric error allocation |

GameRouter.forRouterNode(long,RpcHandler) creates the forwarding role; GameRouter.forServiceNode(int,RouterHandler,RpcHandler) creates the service role. The final argument handles unrelated direct RPC. A handler implementing both business interfaces can be supplied in both service factory positions. ServiceRouting has a package-private constructor; RouterEngine, RouterTable, RouterWire and RoutingRpcHandler remain package-private. Private callback identity distinguishes routing completions from ordinary RPC callbacks. No additional interface/implementation pair, Handler mutation API, connection listener or RPC cleanup listener is needed.

Follow the [component responsibility boundaries](OGBS-Router-1.0.md#1-responsibility-and-composition). New public types and extension points require a concrete caller/integration need; change RPC only for a missing reusable RPC capability.

## 2. Router membership and the common RPC connection API

```java
GameRouter router = GameRouter.forRouterNode(routerEpoch, directHandler);
RpcNode rpc = RpcNode.builder().nodeId(101).bindAddress(address).handler(router).build();
router.start(rpc);
router.registerRouter(102); // the remote Router declares 101
rpc.start();
rpc.addPeer(102, otherRouterAddress, 1);
// Application management context; normally in finally:
router.close();
rpc.close();
```

Membership changes require routing.start first, a nonzero non-self ID and an open role. registerRouter is idempotent; it rejects a local service ID or an existing multi-Slot Peer. Both Routers explicitly declare each other. An absent/disconnected Peer remains configured only in routing membership; maintenance waits without creating a connection. A connected single-Slot Peer begins snapshot synchronization. Undeclared protocol identities are rejected; ordinary RPC Peers are never promoted automatically.

All connections use rpc.addPeer(id,address,slots). Router pairs have one connection initiator and exactly one Slot to preserve control ordering; services may have multiple Slots. A multi-Slot Peer configured after declaration can connect, but routing stays unavailable until the application reconfigures RPC. unregisterRouter removes the visible bucket and queued controls without removing the RPC Peer. Connection address/count changes follow RPC's existing rules.

removeNode(nodeId,nodeEpoch) records an exact-incarnation discovery removal even when registration has not arrived. It removes/publishes current local authority only when the epoch matches; a stale event cannot remove a newer registration. Later REGISTER for the removed pair returns NOT_REGISTERED. Up to 100,000 distinct removal pairs are retained for this Router object's lifetime; adding another pair throws RejectedExecutionException before mutation. Repeated removal is idempotent. The fence is not persisted or replicated as discovery authority: discovery must reapply current removals after Router restart and send them to relevant owners. A new instance uses a fresh epoch. Capacity must be operationally planned; no silent eviction or discovery-provider implementation is supplied.

isRouterReady requires current RPC availability, committed inbound state and acknowledgment of outbound snapshot end. Retained committed state remains queryable during repair, but does not substitute for forwarding readiness. Queries return immutable copies/Optional and are snapshots, not delivery guarantees. resolve requires a positive serviceId and accepts every long key.

## 3. Service registration and callbacks

```java
ServiceRouting routing = GameRouter.forServiceNode(MATCH, routedHandler, directHandler);
RpcNode rpc = RpcNode.builder().nodeId(1).bindAddress(address).handler(routing).build();
routing.start(rpc);
routing.register(101, nodeEpoch, error -> reportRegistration(error));
rpc.start();
rpc.addPeer(101, routerAddress, 3);
// After successful registration: routing.bind(playerId, callback).
// Application shutdown: routing.close(); rpc.close().
```

Public operations are register(int,long,RpcCallback<Integer>), isRegistered(), bindings(), bind(long,RpcCallback<Integer>), unbind(long,RpcCallback<Integer>), unregister(RpcCallback<Integer>) and unregisterAfterLoss(). serviceId is positive and fixed. register requires routing.start, a nonzero non-self Router ID, nonzero epoch and nonnull callback. A new selection requires a different epoch. An existing selection can be explicitly retried with the same Router/epoch only after its attempt failed, with no registration/restoration or initial callback still active. Other selections require clearing/migration first. Selection never creates a Peer.

If connected, REGISTER begins immediately; otherwise the routing maintenance tick waits for transport. Waiting has no separate connection deadline. Each sent control uses RPC's configured timeout. Success ACK carries routerEpoch and starts incremental restoration of accepted desired keys. The initial callback receives the first attempt's result after restoration, once; a transient failed first attempt may report failure while background recovery subsequently succeeds. RouterHandler.onRegistration additionally reports completed registration/restoration attempts, after the initial callback when present. It does not report every Slot change or successful periodic verification.

One service control is in flight, with at most 8,192 queued controls. bind/unbind/unregister admission is FIFO; overflow throws RejectedExecutionException before admission and does not invoke the rejected callback. Restoration materializes one control at a time from the desired set rather than filling this queue with all keys. Successful bind/unbind updates the desired set before callback. Timeout does not establish non-execution or update accepted desired state. Restoration stops at the first failure, may leave partial remote bindings, reports the error and keeps readiness false until reconciliation succeeds.

Registration retries on UNAVAILABLE, PEER_NOT_FOUND, PEER_REMOVED and TIMEOUT while the selection remains valid. Remote registration rejection, malformed ACK and other nontransient errors stop automatic retry; callers may inspect and explicitly retry the same failed selection. A NOT_REGISTERED result from verification or a binding control triggers re-registration first. Removed incarnations remain rejected by the Router, even after transport reconnect. Business calls are never retried.

The maintenance tick is 100ms; after at least one second, an idle registered service sends VERIFY containing the last acknowledged routerEpoch and its nodeEpoch. A replacement Router or missing registration causes re-registration and desired-key restoration, even if multiple Slots kept the Peer continuously available. A quiet short reconnect with intact protocol state need not emit another registration event. A tick that observes transport loss invalidates service protocol state and fences superseded controls with UNAVAILABLE, retaining desired keys and an unfinished initial callback. Generation checks ignore late responses; RPC still owns the underlying pending calls and timeouts.

Control callbacks receive zero or a positive framework code. Validation rejects before admission; each admitted callback completes once. Clearing a lost selection reports PEER_REMOVED; routing closure reports NODE_CLOSED. Callback RuntimeException is logged and cannot stall the FIFO. Callbacks run outside the service monitor, may precede the initiating method's return and must be fast/nonblocking. They may reenter after the state transition. isRegistered additionally requires finished restoration and current transport; it is local protocol knowledge, can be stale until verification, and is not discovery presence.

unregister also accepts a settled failed selection on a healthy Peer, with no registration/restoration or initial callback active. DETACH acknowledgment NOT_REGISTERED means this exact incarnation is already absent and is normalized to successful local cleanup; it does not remove another incarnation. This allows clearing/reselection without resetting a shared Peer. Without transport use unregisterAfterLoss. Normal migration waits for successful unregister: old registration/bindings and desired keys are cleared before callback. unregister rejects new work while admitted controls finish. unregisterAfterLoss requires an existing selection with no usable Slot, clears selection and retains desired keys for explicit registration at a new Router/epoch. Neither removes RPC Peers or cancels ordinary/business pending calls. Old-Peer recovery cannot restore a cleared selection. No automatic Router chooser is provided.

## 4. Business addressing, completion and body ownership

ServiceRouting directly exposes notifyNode, notifyBinding, broadcast, callNode, callBinding and reply. callNode/callBinding have default and explicit timeoutMillis overloads and use RpcCallback<T>. Defaults use the supplied RpcNode's configured callTimeout (normally 5 seconds); explicit timeout must fit positive nanoseconds. Outbound requestId must be zero; the original RpcNode assigns the call ID. Notify/broadcast/reply return RpcSendStatus. Broadcast is Notify-only.

For player-to-player addressing, the receiver's service first calls routing.bind(playerId,callback). A sending service can use routing.notifyBinding(PLAYER_SERVICE,targetPlayerId,request) or callBinding for a response, without resolving a nodeId/address itself. Supply the destination player explicitly in RpcRequest.businessId (with the application's player businessIdType) or the business payload; the binding key alone is not exposed by RoutedRequest. Setting routeKey=targetPlayerId preserves RPC affinity but does not itself schedule serial player execution. onRoutedRequest dispatches to the local player/session and optionally its client connection through application code. Authenticate the sending player at ingress; sourceNodeId is only the sending service's identity. Notify ACCEPTED is first-hop admission, not player delivery; a missing binding drops Notify and returns ROUTE_NOT_FOUND for Call. No built-in chat/session/offline-message API is claimed.

RouterHandler.onRoutedRequest receives exact sourceNodeId/sourceNodeEpoch and a borrowed inner RpcRequest. reply takes that RoutedRequest and a matching nonzero RpcResponse ID, targeting the original source registration and preserving error/Metadata/body. Store immutable identity/ID fields for delayed replies; retain/copy and later release any delayed body. Ordinary direct traffic continues through the primary RpcHandler.

The original Node owns one outer RPC pending call. Intermediate Routers forward RPC Notify envelopes with the original ID inside, creating no business pending map. The final Router forwards a native RpcResponse through the original Router Peer; RPC removes pending state, the private adapter unwraps it and calls RouterHandler.onResponse with the original command/callback. The application decodes business responses and invokes RpcCallback. Remote errors use onResponse; local timeout/removal/close/immediate failure use onFail. Control callbacks in section 3 already carry error codes and follow their direct completion contract.

Dynamic misses return ROUTE_NOT_FOUND; physical misses return RPC PEER_NOT_FOUND; unavailable next hops return RPC UNAVAILABLE. Handler RuntimeException attempts a routed HANDLER_ERROR response. Failed return paths can still time out. A matched native response with malformed inner data completes onFail(PROTOCOL_ERROR), preserving the original command/callback. Application exceptions after a valid onResponse are logged without second completion.

| Boundary | Ownership |
| --- | --- |
| Null/invalid inputs or unavailable registration before encoding | Caller retains original body |
| Validated routed send/reply | Consumes one original body reference, including encoding/transport failure |
| Inbound routed request/response | Borrowed during callback; never directly release |
| Forwarding/broadcast copy | Retains inner body once for each encoding, which consumes that reference |
| Queued Router synchronization controls | Owned by routing; release on sending, invalidation or closure |

Routing envelopes have Request.requestId zero and use existing rpc.notify; the original business ID stays inside the envelope. Native return responses use rpc.reply(target,0,0,response), retaining their ID, preferring Slot 0 and falling back under ordinary no-affinity rules. Intermediate Routers create no business PendingCall and require no new RPC send primitive. Requests use the inner routeKey; business identity/Metadata remain unchanged. ACCEPTED means first-hop local admission, not end-to-end execution; no business retry or remote cancellation is added.

## 5. Synchronization, threads and bounds

RouterEngine serializes state, snapshot/delta enqueue and forwarding under one monitor. Capture the complete snapshot before subsequent local changes. Inbound staging stays invisible until counts/owners and END validate. Profile v2 carries a snapshot revision, consecutive delta revisions and VERIFY. A revision gap, remote Router epoch change, incomplete synchronization or failed control resets only routing synchronization, retains the last committed bucket and exchanges a fresh snapshot. A brief connection loss missed by a tick still converges when revision verification detects an omitted delta; there is no replay log.

Each routing role owns one daemon ScheduledExecutorService started by start, with one 100ms fixed-delay maintenance task. The forwarding role services declared Peers and sends idle VERIFY after at least one second; the service role maintains only its selected Router. Scheduler delays and in-flight RPC timeouts can extend recovery; one second is not a readiness lease or recovery deadline. Control retries occur on a later tick, avoiding recursive immediate failures. VERIFY shares the existing FIFO and never overlaps its in-flight control; sustained controls delay idle verification, while NOT_REGISTERED/gap responses trigger repair directly. No Node transport callback or topology-lock callback is used.

Defaults: 100,000 Nodes and 1,000,000 bindings per authoritative bucket; 256 entries per chunk; 8,192 queued controls plus one in-flight per Router Peer; 8,192 queued service controls plus one in-flight; 100,000 local removal fences. Inner frames are capped at 4 MiB minus 80 bytes, also subject to the Node's outer frame limit. These V1 component defaults have no configuration API.

Service sending captures validated routing identity under its monitor, then encodes and sends outside it. Immediate RPC failure invokes application handlers outside that monitor, so permitted Peer management cannot form the former topology/service lock cycle. A send admitted before routing.close may still run afterward and keeps the captured identity; close does not cancel business execution. Router synchronization callbacks are internal and application callbacks are never dispatched under the forwarding monitor.

The application calls routing.close then rpc.close in a management context. Routing close is idempotent, fences new state mutation, clears tables/desired keys/buffers, stops its maintenance and completes outstanding service controls once. Already-captured or dispatched application callbacks can finish; routing close is not RPC's worker/resource barrier. Even a receive Handler admitted by RPC before routing close cannot recreate Router state afterward. RPC close remains the networking/pending-call completion barrier. Startup failure must also close the routing role. Routing close does not announce remote service offline; discovery owns that event.

## 6. Exceptions, compatibility and validation

Null required inputs throw NullPointerException. Invalid IDs/services/epochs/timeouts/reply IDs throw IllegalArgumentException. Missing registration, wrong lifecycle, repeated start or Router multi-Slot conflict throws IllegalStateException. Service queue/fence overflow throws RejectedExecutionException. RPC validates addresses/Slot counts. Router Profile v2 changes snapshot/delta fields, adds VERIFY and epoch-bearing registration ACK; v1 is rejected, with no mixed-version fallback. RPC Wire v1 framing, types and handshake stay unchanged. Upgrade routing participants together; ordinary RPC participants remain compatible. Removed factories/hooks have no compatibility aliases.

Sources: [GameRouter](../../game-router/src/main/java/cn/managame/router/node/GameRouter.java), [ServiceRouting](../../game-router/src/main/java/cn/managame/router/node/ServiceRouting.java), [RouterEngine](../../game-router/src/main/java/cn/managame/router/node/RouterEngine.java). Validation: [RouterIntegrationTest](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java), [RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java), [RouterWireTest](../../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java), [RouterTableTest](../../game-router/src/test/java/cn/managame/router/node/RouterTableTest.java). [RouterEchoExample](../../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) demonstrates the complete assembly and resource order.

Run mvn -pl game-router -am test and root mvn clean verify for integration. Local TCP tests cover multiple connections, lost ACK on healthy transport, exact removal before/after registration, callback reentry, delayed handler after closure, FIFO capacity, synchronization gaps, routing identity and retained ordinary calls. These do not establish production capacity, sustained overload or cross-language interoperability.

<a id="7-open-review-findings-2026-10-07"></a>

## 7. Review resolution and remaining limits (2026-10-07)

The Handler mutation, readiness and cleanup hooks have been removed from RPC. Ordinary Handler assembly, component-owned protocol maintenance and explicit application closure replace them. The reproduced callback lock cycle, multi-Slot Router restart, missing-register-ACK recovery, exact removed-incarnation resurrection and late post-close state mutation have code changes and permanent regression coverage. Service FIFO admission is now bounded; node-binding removal uses an owner index rather than scanning every binding.

RPC's deferred finite pending admission remains unimplemented. The forwarding monitor still covers encoding/sending and snapshot/conflict scans; resolveKey can scan Router buckets repeatedly. Production memory/throughput and sustained partition/overflow recovery are unverified. Removal fences are finite and not persisted across Router epochs; discovery replay and capacity planning are required. Further indexing, streaming snapshot generation or configured budgets need measured requirements; no new general-purpose extension framework is introduced.
