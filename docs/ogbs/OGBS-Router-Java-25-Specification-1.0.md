# OGBS Router Java 25 Development Specification 1.0

**[English](OGBS-Router-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Router-Java-25-Specification-1.0.zh-CN.md)

Standard: [Router Specification](OGBS-Router-1.0.md). Wire: [Router Profile v3](../rpc-wire.md#router-profile-v3). Status: implemented with local TCP contract and recovery tests; production capacity and cross-language interoperability unverified.

## 1. Module, ownership and public boundaries

`cn.managame:game-router:1.0.0-SNAPSHOT` requires JDK 25 and game-rpc, transitively Network/Core/Netty. RPC never depends on Router. A process has one application-owned RpcNode. GameRouter and ServiceRouting are final concrete classes implementing the existing RpcHandler and AutoCloseable. Construct the routing role first, pass it to RpcNodeBuilder.handler, then call routing.start(rpc) before rpc.start(). start associates that Node once and starts routing maintenance; it never starts networking. The application must supply this role as the Node's Handler; start does not inspect or replace RPC's private Handler. Null arguments throw NullPointerException; repeated start or start after close throws IllegalStateException.

| Public type | Responsibility |
| --- | --- |
| node.GameRouter | Role assembly, forwarding Router membership, route inspection and ordinary RpcHandler dispatch |
| node.ServiceRouting | Service registration, bindings, addressing, replies and ordinary RpcHandler dispatch |
| call.RouterHandler, call.RoutedRequest | Borrowed business delivery and business/registration callbacks |
| route.BindingKey, RouteBinding, NodeRegistration | Validated immutable values |
| error.RouterErrorCodes | Core numeric error allocation |

GameRouter.forRouterNode(long,RpcHandler) creates the forwarding role; GameRouter.forServiceNode(int,RouterHandler,RpcHandler) creates the service role. The final argument handles unrelated direct RPC. A handler implementing both business interfaces can be supplied in both service factory positions. ServiceRouting has a package-private constructor; RouterEngine, RouterTable, RouteIndex, RouterWire and RoutingRpcHandler remain package-private. Private callback identity distinguishes routing completions from ordinary RPC callbacks. No additional interface/implementation pair, Handler mutation API, connection listener or RPC cleanup listener is needed.

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

Membership changes require routing.start first, a nonzero non-self ID and an open role. registerRouter is idempotent; it rejects a local service ID or an existing multi-Slot Peer. Both Routers explicitly declare each other. An absent/disconnected Peer remains configured only in routing membership; maintenance waits without creating a connection. A connected single-Slot Peer starts a session in each direction; declaring an already connected Router also sends SYNC at once. Stream frames from an undeclared Peer are ignored (a later declaration requests a session); ordinary RPC Peers are never promoted automatically.

All connections use rpc.addPeer(id,address,slots). Router pairs have one connection initiator and exactly one Slot, whose ordering the synchronization stream relies on; services may have multiple Slots. A multi-Slot Peer configured after declaration can connect, but routing stays unavailable until the application reconfigures RPC. unregisterRouter removes that Router's bucket from the index and drops its outbound stream without removing the RPC Peer. Connection address/count changes follow RPC's existing rules.

Synchronization requires exactly one Slot. A stream frame from a Peer with another Slot count is rejected, and maintenance resets the outbound session and marks the replica broken, while the last committed replica stays queryable. Business data is not gated by this state. After the application restores one Slot, fresh sessions run in both directions. Router leaves the shared RPC Peer untouched and direct RPC usable. `routerSlotReconfigurationStopsSynchronizationButKeepsReplicas` in [RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java) covers reconfiguration to two Slots, the retained replica, the absence of updates and recovery after returning to one Slot.

removeNode(nodeId,nodeEpoch) records an exact-incarnation discovery removal even when registration has not arrived. It removes/publishes current local authority only when the epoch matches; a stale event cannot remove a newer registration. Later REGISTER for the removed pair returns NOT_REGISTERED. Up to 100,000 distinct removal pairs are retained for this Router object's lifetime; adding another pair throws RejectedExecutionException before mutation. Repeated removal is idempotent. The fence is not persisted or replicated as discovery authority: discovery must reapply current removals after Router restart and send them to relevant owners. A new instance uses a fresh epoch. Capacity must be operationally planned; no silent eviction or discovery-provider implementation is supplied.

isRouterReady(id) reports the inbound direction only: the RPC Peer is connected over exactly one Slot and this Router holds a committed, gap-free replica of that Router's bucket. The peer's replica of this Router is reported by the peer. A replica being recovered remains queryable. Queries return immutable copies/Optional and are snapshots, not delivery guarantees. resolve requires a positive serviceId and accepts every long key. All queries read the lock-free RouteIndex and never wait for synchronization or service controls.

Business data is never gated by readiness (RT-SYNC-05). DATA from any declared Router is accepted; the destination Router checks its own registration by exact Node/epoch, and responses and routed errors go to the origin Router recorded in the envelope. `responsesAndRoutedErrorsReturnToTheOriginRouterWithoutItsReplica` in [RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java) keeps one Router without the other's stream and verifies a routed reply and a routed PEER_NOT_FOUND across it; `streamGapKeepsReplicaQueryableAndResynchronizes` verifies gap recovery with the replica still queryable.

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

A nontransient VERIFY failure invalidates registration, completes queued controls once with that error and reports it once through RouterHandler.onRegistration, after the state transition and outside the service monitor. Desired bindings remain intact; the already-completed initial registration callback is not invoked again. A stopped selection stays stopped across observed transport loss/reconnection. Explicit register with the same Router/epoch enables a new attempt and restores desired keys before success completion; a settled failed selection can also be cleared through the existing unregister operations. Transient VERIFY failure and NOT_REGISTERED retain automatic recovery. `nontransientVerificationFailureStopsRetriesAndPreservesExplicitRetry` covers missing, truncated, zero-epoch and trailing-byte ACKs plus remote nontransient rejection; `recoverableVerificationFailureStillRestoresBindingsWithoutResettingPeer` covers timeout and missing registration.

The maintenance tick is 100ms; after at least one second, an idle registered service sends VERIFY containing the last acknowledged routerEpoch and its nodeEpoch. A replacement Router or missing registration causes re-registration and desired-key restoration, even if multiple Slots kept the Peer continuously available. A quiet short reconnect with intact protocol state need not emit another registration event. A tick that observes transport loss invalidates service protocol state and fences superseded controls with UNAVAILABLE, retaining desired keys and an unfinished initial callback. Generation checks ignore late responses; RPC still owns the underlying pending calls and timeouts.

Control callbacks receive zero or a positive framework code. Validation rejects before admission; each admitted callback completes once. Clearing a lost selection reports PEER_REMOVED; routing closure reports NODE_CLOSED. Callback RuntimeException is logged and cannot stall the FIFO. Callbacks run outside the service monitor, may precede the initiating method's return and must be fast/nonblocking. They may reenter after the state transition. isRegistered additionally requires finished restoration and current transport; it is local protocol knowledge, can be stale until verification, and is not discovery presence.

unregister also accepts a settled failed selection on a healthy Peer, with no registration/restoration or initial callback active. DETACH acknowledgment NOT_REGISTERED means this exact incarnation is already absent and is normalized to successful local cleanup; it does not remove another incarnation. This allows clearing/reselection without resetting a shared Peer. Without transport use unregisterAfterLoss. Normal migration waits for successful unregister: old registration/bindings and desired keys are cleared before callback. unregister rejects new work while admitted controls finish. unregisterAfterLoss requires an existing selection with no usable Slot, clears selection and retains desired keys for explicit registration at a new Router/epoch. Neither removes RPC Peers or cancels ordinary/business pending calls. Old-Peer recovery cannot restore a cleared selection. No automatic Router chooser is provided.

## 4. Business addressing, completion and body ownership

ServiceRouting directly exposes notifyNode, notifyBinding, broadcast, callNode, callBinding and reply. callNode/callBinding have default and explicit timeoutMillis overloads and use RpcCallback<T>. Defaults use the supplied RpcNode's configured callTimeout (normally 5 seconds); explicit timeout must fit positive nanoseconds. Outbound requestId must be zero; the original RpcNode assigns the call ID. Notify/broadcast/reply return RpcSendStatus. Broadcast is Notify-only.

For player-to-player addressing, the receiver's service first calls routing.bind(playerId,callback). A sending service can use routing.notifyBinding(PLAYER_SERVICE,targetPlayerId,request) or callBinding for a response, without resolving a nodeId/address itself. Supply the destination player explicitly in RpcRequest.businessId (with the application's player businessIdType) or the business payload; the binding key alone is not exposed by RoutedRequest. Setting routeKey=targetPlayerId preserves RPC affinity but does not itself schedule serial player execution. onRoutedRequest dispatches to the local player/session and optionally its client connection through application code. Authenticate the sending player at ingress; sourceNodeId is only the sending service's identity. Notify ACCEPTED is first-hop admission, not player delivery; a missing binding drops Notify and returns ROUTE_NOT_FOUND for Call. No built-in chat/session/offline-message API is claimed.

RouterHandler.onRoutedRequest receives exact sourceNodeId/sourceNodeEpoch, the originRouterId of the caller's Router and a borrowed inner RpcRequest. reply takes that RoutedRequest and a matching nonzero RpcResponse ID, targeting the original source registration through its origin Router and preserving error/Metadata/body. Store the immutable identity, origin and ID fields for delayed replies (the 3-argument RoutedRequest constructor leaves origin 0, which makes the home Router locate the caller in its index); retain/copy and later release any delayed body. Ordinary direct traffic continues through the primary RpcHandler.

The original Node owns one outer RPC pending call. Intermediate Routers forward RPC Notify envelopes with the original ID inside, creating no business pending map. The response envelope travels to the origin Router, which sends the caller a native RpcResponse; RPC removes pending state, the private adapter unwraps it and calls RouterHandler.onResponse with the original command/callback. The application decodes business responses and invokes RpcCallback. Remote errors use onResponse; local timeout/removal/close/immediate failure use onFail. Control callbacks in section 3 already carry error codes and follow their direct completion contract.

Dynamic misses return ROUTE_NOT_FOUND; physical misses return RPC PEER_NOT_FOUND; unavailable next hops return RPC UNAVAILABLE. Handler RuntimeException attempts a routed HANDLER_ERROR response. Failed return paths can still time out. A matched native response with malformed inner data completes onFail(PROTOCOL_ERROR), preserving the original command/callback. Application exceptions after a valid onResponse are logged without second completion.

Inter-Router DATA_ERROR is a separate rpc.notify to the origin Router, outside the synchronization stream, without intermediary pending state or acknowledgement. Its private encoded body is released on success, rejection and send exception. Send exceptions are logged without affecting synchronization or retrying the error; failed admission leaves the original call to its existing timeout. The receiver accepts it only from a declared Router and delivers a native error response only when the exact original Node/epoch is still registered locally. The wire representation is in the Wire Profile.

| Boundary | Ownership |
| --- | --- |
| Null/invalid inputs or unavailable registration before encoding | Caller retains original body |
| Validated routed send/reply | Consumes one original body reference, including encoding/transport failure |
| Inbound routed request/response | Borrowed during callback; never directly release |
| Forwarding/broadcast copy | Retains inner body once for each encoding, which consumes that reference |
| Router synchronization stream | Owned by routing; frames are encoded from captured values when sent and released by RPC |

Routing envelopes have Request.requestId zero and use existing rpc.notify; the original business ID stays inside the envelope. Native return responses use rpc.reply(target,0,0,response), retaining their ID, preferring Slot 0 and falling back under ordinary no-affinity rules. Intermediate Routers create no business PendingCall and require no new RPC send primitive. Requests use the inner routeKey; business identity/Metadata remain unchanged. ACCEPTED means first-hop local admission, not end-to-end execution; no business retry or remote cancellation is added.

## 5. Synchronization, threads and bounds

RouterEngine has one small lock, `authority`, guarding the local bucket, discovery removal fences and the order in which local changes enter each peer's stream. Under it a service control updates the local bucket, mirrors the change into the RouteIndex and appends one operation to every active stream queue. Encoding and sending happen after it is released, and the service's control reply is sent before the streams are pumped. Each replica has its own lock and is written only by that peer's stream. RouteIndex maps Node IDs and binding keys to small immutable candidate arrays, one candidate per source bucket, in ConcurrentHashMaps; writers replace one key's array atomically and readers never block. route, resolve, serviceNodes and isRouterReady take no shared lock, so forwarding and lookups continue while a large snapshot is captured or applied. `lookupsNeverBlockWhileTheAuthorityIsBusy` in [RouterSyncTest](../../game-router/src/test/java/cn/managame/router/node/RouterSyncTest.java) holds the authority lock from another thread and verifies that resolve and serviceNodes still complete.

Per peer and direction the outbound stream is a FIFO of one snapshot, delta operations and heartbeat markers. The snapshot is captured as arrays under `authority` and encoded lazily in chunks of up to 1,024 entries; consecutive operations share one DELTA frame of up to 1,024 operations. A single pumper sends the head with rpc.notify and consumes it only after the Slot accepted the frame. A refused frame is retried after 1 ms, so backpressure stalls the stream instead of creating a gap. Up to 100,000 operations wait per stream; one more restarts the session with a snapshot.

The receiver applies each frame under the replica lock. BEGIN starts invisible staging; NODES/BINDINGS fill it; END validates the counts and publishes only the difference between the old and new bucket into the index, so unchanged entries never disappear during a resynchronization. DELTA updates the committed bucket and the index directly. A gap or invalid frame marks the replica broken and sends SYNC (at most one per 100 ms). The authority ignores a SYNC that names an older session when its current session started less than one second ago, so a burst of requests causes one restart.

One daemon maintenance thread per role runs every 20 ms. For each declared Router it resets the outbound session and breaks the replica when the single-Slot connection is gone; breaks a live replica that heard nothing for 3 s and a staging snapshot stalled for 1 s; repeats SYNC every second while not live; starts a session when the Slot is usable and none exists; queues a STATE heartbeat after 1 s without traffic; and pumps. The service role keeps its 100 ms tick for registration verification. Intervals are not readiness leases or recovery deadlines.

Defaults: 100,000 Nodes and 1,000,000 bindings per bucket; 1,024 entries per chunk or operations per DELTA; 100,000 pending operations per stream; 8,192 queued service controls plus one in-flight; 100,000 local removal fences. Inner frames are capped at 4 MiB minus 80 bytes, also subject to the Node's outer frame limit. These V1 component defaults have no configuration API.

Service sending captures validated routing identity under its monitor, then encodes and sends outside it. Immediate RPC failure invokes application handlers outside that monitor, so permitted Peer management cannot form the former topology/service lock cycle. A send admitted before routing.close may still run afterward and keeps the captured identity; close does not cancel business execution. Application callbacks are never dispatched under the Router's `authority` or replica locks.

The application calls routing.close then rpc.close in a management context. Routing close is idempotent, fences new state mutation, clears tables/desired keys/buffers, stops its maintenance and completes outstanding service controls once. Already-captured or dispatched application callbacks can finish; routing close is not RPC's worker/resource barrier. Even a receive Handler admitted by RPC before routing close cannot recreate Router state afterward. RPC close remains the networking/pending-call completion barrier. Startup failure must also close the routing role. Routing close does not announce remote service offline; discovery owns that event.

## 6. Exceptions, compatibility and validation

Null required inputs throw NullPointerException. Invalid IDs/services/epochs/timeouts/reply IDs throw IllegalArgumentException. Missing registration, wrong lifecycle, repeated start or Router multi-Slot conflict throws IllegalStateException. Service queue/fence overflow throws RejectedExecutionException. RPC validates addresses/Slot counts. Router Profile v3 replaces the acknowledged Router-to-Router controls with the session stream (SYNC/BEGIN/NODES/BINDINGS/END/DELTA/STATE), removes Router-to-Router VERIFY and adds the origin Router to the data envelope; v1 and v2 are rejected, with no mixed-version fallback. Service controls (REGISTER/BIND/UNBIND/DETACH/VERIFY) keep their v2 fields. RPC Wire framing, types and handshake stay unchanged. Upgrade routing participants together; ordinary RPC participants remain compatible. Removed factories/hooks have no compatibility aliases.

Sources: [GameRouter](../../game-router/src/main/java/cn/managame/router/node/GameRouter.java), [ServiceRouting](../../game-router/src/main/java/cn/managame/router/node/ServiceRouting.java), [RouterEngine](../../game-router/src/main/java/cn/managame/router/node/RouterEngine.java). Validation: [RouterIntegrationTest](../../game-router/src/test/java/cn/managame/router/node/RouterIntegrationTest.java), [RouterRecoveryTest](../../game-router/src/test/java/cn/managame/router/node/RouterRecoveryTest.java), [RouterWireTest](../../game-router/src/test/java/cn/managame/router/node/RouterWireTest.java), [RouterTableTest](../../game-router/src/test/java/cn/managame/router/node/RouterTableTest.java), [RouterSyncTest](../../game-router/src/test/java/cn/managame/router/node/RouterSyncTest.java). [RouterEchoExample](../../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) demonstrates the complete assembly and resource order.

Run mvn -pl game-router -am test and root mvn clean verify for integration. Local TCP tests cover multiple connections, lost ACK on healthy transport, exact removal before/after registration, callback reentry, delayed handler after closure, FIFO capacity, stream gaps, abandoned sessions, random frame loss, repeated connection loss under churn, a four-Router mesh with restart, routing identity and retained ordinary calls. These do not establish production capacity, sustained overload or cross-language interoperability.

<a id="7-known-limits"></a>

## 7. Known limits

This section lists only boundaries that still hold. The 2026-10-07 review process and evidence are kept in the [review record](../reviews/2026-10-07-router-rpc-review.md), which is not normative.

- **Call admission**: business Call pending has no finite admission; it depends on RPC R-SEND-04 (unimplemented).
- **Generations and discovery**: `locate` picks the numerically largest nodeEpoch. Exact retirement, event replay after Router restart and generation filtering of member removal belong to discovery integration; the example provides none.
- **Migration isolation**: a successful bind does not prove the old owner stopped executing. Business ownership generations and conditional writes belong to the application (RT-BIND-03).
- **Recovery cost**: every recovery sends the full bucket; there is no incremental catch-up. Capturing a snapshot copies the bucket into arrays while holding `authority`, linear in bucket size; this delays local service controls for that time but not lookups or forwarding. A change rate above the drain rate keeps restarting sessions.
- **Resource budget**: members, committed/staged buckets and stream queues have no process-wide limit; during a snapshot a replica holds both its committed and staging tables.
- **Multi-Slot Router Peers**: synchronization requires exactly one Slot. A Router Peer configured with more Slots keeps its last replica but receives no updates until it is reconfigured.
- **Spring composition**: managed RPC composes GameRouter/ServiceRouting through `GameRpcConfigurer.decorate/attach/detach` ([Spring Java specification](OGBS-Spring-Java-25-Specification-1.0.md#managed-rpc)). Dispatching routed requests into Runtime and exact routed reply identity (source nodeEpoch, origin Router) remain application code in RouterHandler.
- Production throughput, memory and sustained partition recovery are unverified.
