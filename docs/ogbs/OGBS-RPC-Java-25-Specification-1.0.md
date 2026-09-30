# OGBS RPC Java 25 Development Specification 1.0

**[English](OGBS-RPC-Java-25-Specification-1.0.md)** | [简体中文](OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)

Document type: **Java Development Specification**. Standard: [RPC Specification](OGBS-RPC-1.0.md). Byte layout: [Wire Profile](../rpc-wire.md).

Status: game-rpc implemented. JDK 25 without preview; Maven cn.managame:game-rpc:1.0.0-SNAPSHOT depends on game-core and game-network, transitively using Netty. No Runtime/Data, Spring, business protocol codec, or discovery dependency.

<a id="1-包与封装"></a>

## 1. Packages and encapsulation

| Package | Types and responsibilities |
| --- | --- |
| cn.managame.rpc.node | RpcNode, RpcNodeBuilder; package-private RpcPeer, ConnectionSlot, PendingCall, RpcConnectionContext, RpcConnectionHandler |
| cn.managame.rpc.message | Read-only RpcRequest, RpcResponse, RpcHandshake wire value objects |
| cn.managame.rpc.call | RpcHandler, generic RpcCallback |
| cn.managame.rpc.transport | RpcSendStatus |
| cn.managame.rpc.error | RpcErrorCodes, RpcException, RpcEncodeException |
| cn.managame.rpc.netty | Public RpcWire binding; package-private RpcEncoder, RpcProtocol |

Runnable examples and their execution tests live in game-example (cn.managame.example.rpc), outside the game-rpc artifact. Example users must update their imports and module dependency; no old-package alias is retained. Component contract tests remain in game-rpc. Types use responsibility-based packages with package-private internals. Read-only records avoid exposing requestId setters or internal cross-package bridges. Node passes its assigned ID to the encoder and **does not mutate the caller's Request**. Public full constructors can represent decoded messages, but call/notify reject nonzero requestId. This encapsulation preserves Wire layout and call semantics.

RpcWire is an independently usable Wire Profile API, exposing no mutable Peer/Slot/timer. Core RPC, Network adaptation, and codecs share one artifact; no separate netty artifact. Old RpcNodeConfig, RpcDialer, EstablishmentOwnership, RpcRequestHandler, and RpcErrors drafts are not provided.

<a id="2-rpcnode-与-builder"></a>

## 2. RpcNode and Builder

```java
RpcNode.builder()
    .nodeId(1)
    .bindAddress(new InetSocketAddress("127.0.0.1", 9000))
    .handler(handler)
    .build();

int nodeId();
void start();
SocketAddress localAddress();
void addPeer(int nodeId, SocketAddress address, int slotCount);
void removePeer(int nodeId);
RpcSendStatus notify(int nodeId, RpcRequest request);
<T> void call(int nodeId, RpcRequest request, RpcCallback<T> callback);
<T> void call(int nodeId, RpcRequest request, long timeoutMillis, RpcCallback<T> callback);
RpcSendStatus reply(int nodeId, int sourceSlotId, long routeKey, RpcResponse response);
void close();
```

Builder requires nodeId/address/handler. nodeId is nonzero; int holds raw uint32 bits, so negatives are valid. Builder is not thread-safe; repeated build snapshots configuration without creating threads, binding ports, or establishing Peers.

| Builder method | Default | Boundary |
| --- | --- | --- |
| callTimeout(Duration) | 5 seconds | Converted value >=1ms and representable as positive long nanoseconds |
| handshakeTimeout(Duration) | 5 seconds | Same |
| reconnectDelay(Duration) | 1 second | Positive base delay; same duration range |
| reconnectJitter(Duration) | floor(reconnectDelay in ms / 4) | Nonnegative ms; base + jitter must fit positive long nanoseconds |
| maxPendingCalls(int) | 16384 | Positive Node-wide admission bound; DEFAULT_MAX_PENDING_CALLS |
| heartbeatInterval(Duration) | 10 seconds | Same positive duration range as callTimeout |
| heartbeatTimeout(Duration) | 30 seconds | Greater than interval |
| maxFrameSize(int) | 4 MiB | >=32, including length prefix |

Duration.toMillis overflow synchronously throws ArithmeticException. Values below 1ms or beyond Long.MAX_VALUE nanoseconds throw IllegalArgumentException. Per-call timeoutMillis has the same validation. reconnectJitter additionally accepts zero and positive sub-millisecond durations (truncated to zero), rejects all negative durations, and validates the sum at build. Its implicit 25% default is clamped to the remaining nanosecond range and follows the snapshotted base delay; explicit jitter does not change when the base changes. Defaults give a fresh integer delay of 1000..1250ms; reconnectJitter(Duration.ZERO) restores 1000ms. This changes the previous exact default retry timing, with no wire-format change.

V1 RpcNode assembles internal TCP NetworkServer/NetworkClient without duplicating Network's pipeline, TLS/WS, ChannelOption, or EventLoopGroup builder settings.

One-shot start creates the timing wheel and owned NIO groups, assembles client/server, and enters RUNNING only after successful synchronous bind. Failure enters CLOSED, cleans partial resources, and throws RpcException; no reuse. localAddress is null before bind and exposes an allocated dynamic port afterward. After closure it is diagnostic, not proof of listening.

addPeer requires RUNNING, nonzero non-self ID, nonnull address, and slotCount=1..255. Identical configuration is idempotent; address/count conflicts throw IllegalStateException. Passive Peers upgrade in place. Missing removePeer is a no-op but still rejects NEW/CLOSED lifecycle.

<a id="3-消息与-bytebuf-所有权"></a>

## 3. Messages and ByteBuf ownership

```java
public record RpcRequest(
    int command, int requestId, long routeKey, int businessIdType,
    long businessId, Metadata metadata, ByteBuf body) {}

new RpcRequest(command, body);
new RpcRequest(command, routeKey, businessIdType, businessId, metadata, body);

public record RpcResponse(
    int requestId, int errorCode, Metadata metadata, ByteBuf body) {}
```

Convenience Request constructors set requestId=0. command is nonzero; businessIdType is 0..255. Response requires nonzero requestId and errorCode>=0. Invalid construction immediately throws IllegalArgumentException without releasing body. Null Metadata normalizes to empty; null body means empty payload; inbound empty bodies are empty slices.

| Boundary | Ownership |
| --- | --- |
| Argument/null/lifecycle validation failure | Caller keeps body |
| Validated call/notify/reply | Consume exactly one body reference on every subsequent path |
| Missing Peer/no candidate connection | Release body directly without encoding |
| Encoding success/failure | Encoder releases body; success leaves only final frame |
| Network ACCEPTED | Transfer frame to Network; stop fallback immediately |
| All connections reject | RPC releases frame |
| Inbound onRequest/onResponse | Borrow body; no direct release; retain/copy for asynchronous use |

Encoder computes total length as long and checks limits before allocation. Allocate one contiguous final ByteBuf and copy body once using readerIndex/readableBytes without moving the original index. Custom Metadata must pass Core structural validation. Wrap encoding failures as RpcEncodeException and release both allocated frame and input body.

Inbound Metadata is copied into independent byte[] and structurally validated. body is frame.readSlice; Network releases frame after callback. Saving Request does not extend body lifetime. Even synchronous echo must retain before reply to avoid releasing a borrowed share twice.

Contiguous frames simplify ownership/fallback compared with CompositeByteBuf. Revisit only if production measurements identify large-body copying as a bottleneck. No zero-copy outbound guarantee.

<a id="4-handlercallback-与异常"></a>

## 4. Handler, Callback, and exceptions

```java
public interface RpcHandler {
    void onRequest(int sourceNodeId, int sourceSlotId, RpcRequest request);
    void onResponse(int sourceNodeId, int requestCommand,
                    RpcResponse response, RpcCallback<?> callback);
    void onFail(int targetNodeId, int requestCommand,
                int errorCode, RpcCallback<?> callback);
}
@FunctionalInterface
public interface RpcCallback<T> {
    void onResponse(T response);
}
```

RpcHandler must be thread-safe and return quickly. Requests/responses and matched-response protocol failures normally run on Netty EventLoops; immediate failures on the caller thread; removal/closure failures on management threads. Timeout failures run on distinct Node-owned virtual threads, after the timer claims completion and removes PendingCall. There is no global callback ordering, configurable callbackExecutor, or automatic Runtime Context restoration. One blocked timeout handler does not occupy the maintenance timer; its call admission reservation remains held until it returns.

All remote responses, including success/framework/business errors, go to onResponse. Applications interpret errors and decode by command/protocol, then invoke the appropriate RpcCallback<T>. Only local failures go to onFail; RpcCallback has no onFailure. Stored callbacks may carry application context, but the integration layer restores Route.

A RuntimeException from onRequest logs diagnostics; Call attempts an empty HANDLER_ERROR response, Notify only logs, and healthy connections remain open. RuntimeException from onResponse/onFail only logs, without repeated notification. Do not swallow JVM Error as ordinary business failure. System.Logger diagnostics never return stack traces to peers.

| Situation | Java result |
| --- | --- |
| Null required argument | NullPointerException |
| Invalid argument, timeout, or Slot range | IllegalArgumentException |
| Runtime API called in NEW/CLOSED | IllegalStateException |
| notify/reply acceptance/missing Peer/unavailable | Three RpcSendStatus values |
| call missing Peer/no writable Slot/admission exhausted | onFail(PEER_NOT_FOUND/UNAVAILABLE) |
| timeout/remove/close | onFail(TIMEOUT/PEER_REMOVED/NODE_CLOSED) |
| Encoding failure | RpcEncodeException, synchronous, body already consumed |
| Extreme requestId collision | RpcException, synchronous; preserve old PendingCall |
| Malformed matched response | onFail(PROTOCOL_ERROR), close current connection |
| Synchronous Network write exception | Stop fallback, clean this PendingCall/frame, close connection, propagate synchronously |

reply first validates sourceSlotId in 0..254, and below Peer.slotCount when Peer exists; failure retains caller ownership. Missing Peer returns PEER_NOT_FOUND and consumes body. Valid calls racing closure still arbitrate one completion without duplicate notifications.

RpcErrorCodes references Core numbers, without a separate number registry. Old high-bit framework wrapping was removed; see Wire Profile/Core compatibility notes.

<a id="5-call-发送与完成顺序"></a>

## 5. call send and completion order

1. Validate parameters/RUNNING, register an in-progress API operation, and take body ownership.
2. Find Peer/candidates; on failure release body and notify local failure.
3. Reserve one Node-wide admission permit atomically; if full, release body and invoke onFail(UNAVAILABLE) immediately. Allocate ID from the Node AtomicInteger, skipping 0; encode final frame.
4. Register PendingCall with ConcurrentHashMap.putIfAbsent; never overwrite or scan on collision.
5. Recheck Node validity and Peer object identity; fail if stale.
6. Attempt affinity/fallback sending; accept the frame at most once.
7. After ACCEPTED, register timeout with HashedWheelTimer and write volatile Timeout.
8. Recheck map membership; if already completed, cancel the new Timeout.

PendingCall stores only ID, command, callback, and volatile Timeout. Conditional remove arbitrates timeout/removal/closure/send failure. Response reads ID, removes PendingCall, cancels Timeout, then parses the remainder. Drop unmatched responses directly; malformed matched responses still notify PROTOCOL_ERROR.

maxPendingCalls bounds encoding, PendingCalls and running completion handlers collectively across all Peers. A CAS reservation occurs before ID allocation/encoding; successful registration transfers ownership to the PendingCall. Every terminal path releases exactly once after notification returns, or after synchronous exception cleanup. Rejection does not reserve another permit and runs on the caller. For example, with limit=2 and two blocked timeout handlers, the maps may already be empty while a third call still receives UNAVAILABLE; permitting it would make the virtual-thread work unbounded. There is no per-Peer fairness, byte budget, waiting queue or message retry queue. A retained callback/body can still be large; choose limits from application memory and latency measurements. Netty reclaims canceled timing-wheel tasks; the discarded DelayQueue design retained them until deadline.

<a id="6-网络装配与-peer-并发"></a>

## 6. Network assembly and Peer concurrency

The pipeline entry point is `RpcWire.configurePipeline(pipeline, maxFrameSize, heartbeatIntervalMillis, heartbeatTimeoutMillis)`. This replaces the former `RpcWire.install` name without retaining an alias. Direct callers must update their source and recompile; existing binaries invoking the old method are incompatible. Pipeline order, framing, heartbeat behavior, and wire compatibility are unchanged.

RpcWire.configurePipeline adds LengthFieldBasedFrameDecoder(maxFrameSize,0,4,0,4), then IdleStateHandler. Complete frames drive Read Idle; fragments cannot keep a connection alive indefinitely. Network adaptation receives ByteBuf with the four-byte prefix stripped.

Network guarantees client onConnected before ConnectCallback.onSuccess. The former binds AttributeKey<RpcConnectionContext> and starts the handshake timeout; the latter sets expectedPeer/expectedSlot and starts handshake. Do not bind a Slot twice across these callbacks.

One HashedWheelTimer per Node, tick=10ms, handles call/handshake timeouts and base-plus-jitter reconnect. It does not invoke business timeout handlers: it submits claimed notifications to an owned virtual-thread-per-task executor. Admission permits bound pending and executing timeout notifications; no additional notification rejection policy or application callback queue is introduced. Immediate failures, responses and management notifications retain their execution threads. Per-connection IdleStateHandler handles heartbeats; EventLoop handlers must return quickly.

Peers use ConcurrentHashMap<int,RpcPeer>. Low-frequency start/close/add/remove/handshake topology changes use a lifecycle lock. compute arbitrates same-ID passive creation, upgrade, and cleanup. Hot sends do not acquire the lifecycle lock.

Slot uses AtomicReference<Connection> for identity-aware bind/unbind and AtomicBoolean connecting over the entire recovery chain. Old asynchronous Peer tasks check map object identity without a separate removed flag. Active target address is volatile; disconnection does not clear waiting calls.

Native Netty AttributeKey stores RPC context. Node tracks all owned connections for final closure, including unassociated inbound handshakes; each Peer additionally tracks its READY and outbound unfinished-handshake connections. Association checks current identity under lifecycleLock before publishing the Peer reference, so removal cannot miss a late association. Disconnect removes the ownership index and unbinds by exact connection identity. detach scans only that Peer index and its Slots; final close scans the global set once, avoiding repeated P × C context scans. These are internal indexes, not a public ConnectionManager.

<a id="7-关闭屏障与线程边界"></a>

## 7. Closure barrier and thread boundaries

Under the lifecycle lock, close first sets CLOSED and detaches current Peers. Outside it, wait for admitted API operations, terminate PendingCalls/Slots, close unfinished handshakes/connections, NetworkServer/Client, owned boss/worker groups, and timing wheel; then close the timeout notification executor and await every submitted handler. The executor remains open until timer.stop returns, preventing shutdown rejection of a notification already claimed by the timer. Never hold the topology lock while waiting for EventLoops.

Phaser admission covers a sender registering PendingCall after close clears the map; it is not a business queue and does not change routing. CountDownLatch makes concurrent close await the same cleanup. Application RuntimeException cannot stop other cleanup; resource-close errors log and cleanup continues.

start/close from this Node's RpcHandler, EventLoop, or timing wheel throws IllegalStateException before lifecycle mutation to prevent self-wait. Reentrant close during management-thread cleanup callbacks is idempotent. Shut down from an independent management thread.

After close returns, no PendingCall, RPC connection, timing wheel, or owned timeout notification remains. It does not await work submitted to Runtime/other executors. Stop admission and handle business work first, then close RPC. Startup failure cannot restart.

<a id="8-可运行示例与源码"></a>

## 8. Runnable example and source

[RpcEchoExample](../../game-example/src/main/java/cn/managame/example/rpc/RpcEchoExample.java) starts two local nodes on dynamic ports, performs TCP handshake/call, application string decoding, and reply. It retains inbound body before reply, configures the client admission limit to 1024, and cleans nodes using try-with-resources.

Main entries: [RpcNode](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java), [RpcNodeBuilder](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNodeBuilder.java), [RpcWire](../../game-rpc/src/main/java/cn/managame/rpc/netty/RpcWire.java).

RpcWire exposes configurePipeline, encodeRequest(request,assignedId,maxFrameSize), encodeResponse, encodeHandshake, encodeHeartbeat, and matching decoders. Encoding consumes body; decoding returns slices borrowed from the input frame. Follow Javadoc type/ID read-position requirements. Direct RpcWire use lacks RpcNode's handshake state, Peers, and completion arbitration and is not a complete semantic RPC implementation.

<a id="9-验证与未实现范围"></a>

## 9. Validation and unimplemented scope

- [RpcWireTest](../../game-rpc/src/test/java/cn/managame/rpc/netty/RpcWireTest.java): four golden vectors, uint32/uint64, Metadata, reference counts, length limits, fragmented/coalesced frames, malformed frames.
- [RpcNodeTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcNodeTest.java): Slot fallback, fast responses, completion races, ID wrap/collision, passive lifetime, heartbeat rejection, handshake timeout, Handler exceptions, closure barrier.
- [RpcIntegrationTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java): real TCP bidirectional communication, independent reconnection, cross-Slot responses, active recovery, passive upgrade.
- [RpcResilienceTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcResilienceTest.java): Peer recreation, concurrent admission, notification isolation/ownership, unfinished handshake closure, reconnect configuration.
- [RpcExampleTest](../../game-example/src/test/java/cn/managame/example/rpc/RpcExampleTest.java): complete example execution in game-example; run mvn -pl game-example -am test.

Targeted command: mvn -pl game-rpc -am test. Dependency/cross-component changes require root mvn clean verify. Windows tests reuse Network's TCP Selector wakeup compatibility setting; production code does not change JVM properties.

Unimplemented: automatic Runtime integration, TLS/WS RPC Builder, discovery, Router, retries, remote cancellation, durable delivery. Unverified: actual cross-language interoperability, production throughput/memory limits, sustained stress, public-network deployment. Passing tests do not establish these capabilities.

<a id="91-审阅确认的缺陷与规模风险"></a>

### 9.1 Repair validation and remaining scale boundaries

**Correlation across Peer recreation is repaired.** [RpcNode](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java) owns the AtomicInteger allocator for its entire lifetime; removing an active Peer or reclaiming a passive Peer does not reset it. PendingCalls and matching still belong to each Peer. The uint32 layout, initial ID 1, zero-skip and occupied-ID exception remain unchanged; allocation gaps are allowed. The full-wrap period now depends on total Node ID allocation across all Peers, including failed encodings, rather than one Peer alone. Restarting/replacing the local Node is outside this continuity guarantee: wire v1 carries no incarnation/epoch. Applications needing that guarantee must validate their own epoch or adopt an explicitly versioned protocol.

Concrete boundary: A calls B with command 101; B saves it. A removes B and receives PEER_REMOVED, then re-adds B and calls command 202. B sends its saved old response over the valid new connection, followed by the new response. [RpcIntegrationTest.oldBusinessReplyAfterPeerRecreationCannotCompleteNewCall](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java) verifies that only the new ID/body reaches command 202. [RpcResilienceTest.passiveRecreationDoesNotReuseCallIdsOrAdmitOldReplies](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcResilienceTest.java) covers automatic passive reclamation. The old-connection identity checks remain necessary but are not sufficient alone.

**Timeout notifications are isolated and bounded.** The timing wheel claims completion and submits onFail to an owned virtual thread. A slow notification retains its admission permit. The blockedTimeoutNotificationLeavesOtherDeadlinesLiveAndCloseWaits test holds one timeout handler while another call times out and a silent handshake closes; it also verifies saturation rejection, close waiting for that handler, and rejection of synchronous close from inside it. responseNotificationRetainsAdmissionUntilHandlerReturns covers response-handler ownership. There is no hard deadline or callback global ordering guarantee; EventLoop response/request handlers still need to return quickly.

**Node-wide call admission is finite.** [RpcNodeBuilder](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNodeBuilder.java) exposes maxPendingCalls, default 16384. The concurrent admission test submits 64 calls across two Peers with limit 8 and verifies eight accepted frames, 56 UNAVAILABLE failures, body consumption, and subsequent capacity reuse. Encoding/send failures, uint32 collision and terminal races test exact release rather than merely map size. This is a count limit, not a byte budget or per-Peer fairness mechanism. Callbacks can retain large object graphs; throughput and memory sizing still require deployment measurements.

**Shutdown avoids repeated global scans.** Each Peer indexes READY and outbound unfinished-handshake connections; global ownership covers unassociated inbound handshakes. The removePeerOnlyTouchesItsConnections test fails if removal inspects an unrelated connection. removePeerClosesOutboundUnfinishedHandshake uses a real TCP peer that never responds to the handshake and verifies EOF after removal. Aggregate traversal is O(P + C + S), plus outstanding-call notifications, where S is total configured Slots; no large-topology shutdown latency benchmark is claimed.

**Reconnect attempts have bounded jitter.** Each failed recovery waits base plus a fresh integer sample in [0, jitter]. Default jitter is 25% of the base (rounded down and range-clamped); zero preserves fixed timing. Builder tests cover inclusive ranges, independent snapshots, negative/overflow rejection and zero; real TCP integration covers initial failure recovery and independent Slot reconnection. Jitter spreads attempts but does not guarantee an attempt rate cap, exponential backoff or recovery under unlimited topology growth. Business frames are never resent.

These repairs preserve wire v1 and Core error numbers. Defaults now bound admission and spread reconnect timing; timeout onFail changes from the timer thread to owned virtual threads. Applications relying on timer-thread identity or exact default retry timing must adapt. Cross-language interoperability, sustained production capacity, Node-restart epochs, full-wrap late replies and simultaneous bidirectional recovery under scale remain unverified. Runtime integration, public TLS/WS configuration, authentication and durable delivery remain outside this implementation.
<a id="92-后续审阅与扩展候选"></a>

### 9.2 Follow-up review and extension candidates

**Verified defect, not repaired: a stopped recovery chain can miss a concurrent unbind.** In [RpcNode.connect/reconnect](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java), observing an occupied Slot and clearing connecting are separate operations. Meanwhile, disconnected unbinds the connection and starts recovery only if it can change connecting from false to true. Valid interleaving: an old outbound attempt fails while an inbound connection occupies its Slot; reconnect observes that connection; another connection EventLoop unbinds it and sees connecting still true, so it starts no new chain; reconnect then clears connecting and returns. The active Peer is still current, its Slot is empty, and no recovery is scheduled. Adding the same Peer again can restart maintenance, but normal automatic recovery has stopped. This violates the maintenance intent of R-LIVE-03; the specification is not relaxed to permit it.

A local two-thread diagnostic invoked the real reconnect and disconnected methods, starting from an active Peer with a READY connection and an existing recovery chain. Across three runs it observed 10 lost chains in each run (8953, 18868 and 9209 iterations). These are synthetic race counts, not production rates or a real-network load benchmark. The diagnostic source/log are retained temporarily under game-rpc/target/rpc-review2; root clean removes them. Permanent deterministic regression coverage and the repair remain pending. Repair should make recovery stopping and empty-Slot rechecking coherent with unbind, preserving one chain per Slot and stale-Peer rejection; merely increasing jitter does not repair the race.

**Node replacement correlation is a verified wire-v1 boundary.** A real TCP diagnostic kept B running, closed local Node A, then created a fresh A with the same nodeId. Old command 101 and new command 202 both received requestId=1; B sent its saved old reply over the fresh valid connection, and A delivered that old body to command 202 and discarded the correct later reply. This is the already declared R-CALL-04 Node-lifetime boundary, distinct from the repaired same-Node Peer recreation case. Node-incarnation correlation or wider identifiers are future protocol-design candidates; wider IDs alone do not fix restart reuse when allocation resets. No wire-v2 layout or requirement is selected here.

**Simultaneous two-sided binding needs convergence validation.** Under the confirmed first-valid-binding-wins policy, a controlled handshake ordering let A and B each bind the incoming half of a different physical connection before processing their outgoing handshake replies. Both outgoing candidates then failed as Duplicate slot; propagating their disconnects left both Slots empty with recovery pending. This demonstrates one failed recovery round, not permanent livelock or a production failure rate. Permanent real TCP regressions should cover simultaneous addPeer and simultaneous disconnection/recovery with zero and nonzero jitter. Node-ID ordering arbitration remains excluded by the current contract; this review does not revive it.

**Capacity and execution boundaries remain:** maxPendingCalls counts locally initiated calls and unfinished completion handlers, across the whole Node. It does not impose per-Peer fairness, a byte budget, limits on inbound calls, passive Peers, or uncompleted inbound handshakes. One silent Peer or blocked completion handler can occupy the shared quota and reject calls to healthy Peers. Request/response handlers and matched-response protocol failures still execute on connection EventLoops; dispatching business work elsewhere requires retaining/copying the borrowed body and releasing it on both acceptance and rejection paths. Every Node owns its NIO groups; deployments hosting many Nodes in one JVM need resource sizing. Jitter imposes no global attempt rate cap. Long-lived stress, large bodies, slow peers, inbound bursts, repeated recovery and shutdown latency need measurement; current tests do not establish production capacity.

**Useful extension candidates, not implemented or active requirements:** immutable Peer/Slot readiness snapshots and state events; counters for pending/admitted calls, rejection reasons, timeouts, unmatched replies, retries and handshake failures; optional per-Peer and inbound admission; application/Runtime dispatch integration; a drain phase that rejects new work while allowing pending responses and replies until a deadline; a locally cancelable call handle or CompletionStage adapter with explicit decoded-body ownership. Drain expiry must not silently weaken close's existing barrier, and local cancellation cannot imply stopping remote execution. A Node-incarnation wire profile and TLS/identity authorization are justified by explicit rolling-restart or trust-boundary requirements. Discovery/routing, automatic business retries, receiver deduplication and durable delivery remain outside the current RPC core; they require their own contract before implementation.

Prioritize recovery correctness and its regression first, then readiness/diagnostics and measured admission. Performance work such as allocation reduction, send/flush batching or internal decomposition should follow measurements and preserve ownership, acceptance and completion ordering. The stale RpcHandler threading comment has been corrected to match the existing virtual-thread timeout notification contract; this review does not change runtime behavior.
