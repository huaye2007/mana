# OGBS RPC Java 25 Development Specification 1.0

**[English](OGBS-RPC-Java-25-Specification-1.0.md)** | [简体中文](OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)

Document type: **Java Development Specification**. Standard: [RPC Specification](OGBS-RPC-1.0.md). Byte layout: [Wire Profile](../rpc-wire.md).

Status: implemented and locally verified. JDK 25 without preview; Maven `cn.managame:game-rpc:1.0.0-SNAPSHOT` depends on game-core and game-network (and through them Netty). RPC has no Runtime, Data, Spring, codec or discovery dependency; the optional Runtime integration lives in [game-spring](OGBS-Spring-Java-25-Specification-1.0.md#managed-rpc).

<a id="1-包与封装"></a>

## 1. Packages and encapsulation

| Package | Types and responsibilities |
| --- | --- |
| cn.managame.rpc.node | RpcNode, RpcNodeBuilder; package-private RpcPeer, ConnectionSlot, PendingCall, RpcConnectionContext, RpcConnectionHandler |
| cn.managame.rpc.message | Read-only RpcRequest, RpcResponse, RpcHandshake |
| cn.managame.rpc.call | RpcHandler, RpcCallback |
| cn.managame.rpc.transport | RpcSendStatus |
| cn.managame.rpc.error | RpcErrorCodes, RpcException, RpcEncodeException |
| cn.managame.rpc.netty | Public RpcWire; package-private RpcEncoder, RpcProtocol |

Messages are read-only records. Call IDs are positive and wrap from Integer.MAX_VALUE back to 1; allocation starts at a random value per Node instance, so a restarted Node does not reissue the IDs a peer may still hold (R-CALL-04). The Node assigns the wire request ID while encoding and never mutates the caller's request; call/notify reject a nonzero requestId. RpcWire is a standalone Wire Profile codec without Peers, Slots or timers. Runnable examples live in game-demo (`cn.managame.demo.examples.rpc`).

Upper layers such as Router compose an ordinary RpcHandler at construction ([Router boundaries](OGBS-Router-1.0.md#1-responsibility-and-composition)). RPC adds no Router-specific connection wrappers, state or lifecycle hooks; a new public integration API needs a concrete need that existing operations cannot meet.

<a id="2-rpcnode-与-builder"></a>

## 2. RpcNode and Builder

```java
RpcNode node = RpcNode.builder()
    .nodeId(1)
    .bindAddress(new InetSocketAddress("127.0.0.1", 9000))
    .handler(handler)
    .build();

void start();
SocketAddress localAddress();
void addPeer(int nodeId, SocketAddress address, int slotCount);
void removePeer(int nodeId);
boolean isPeerConnected(int nodeId);
int peerSlotCount(int nodeId);
RpcSendStatus notify(int nodeId, RpcRequest request);
<T> void call(int nodeId, RpcRequest request, RpcCallback<T> callback);
<T> void call(int nodeId, RpcRequest request, long timeoutMillis, RpcCallback<T> callback);
RpcSendStatus reply(int nodeId, int sourceSlotId, RpcResponse response);
RpcSendStatus forwardResponse(int nodeId, long routeKey, RpcResponse response);
void close();
```

nodeId, bindAddress and handler are required. nodeId is nonzero; the int holds raw uint32 bits, so negative values are valid IDs. The builder is not thread-safe; build only snapshots configuration.

| Builder method | Default | Boundary |
| --- | --- | --- |
| callTimeout(Duration) | 5 s | ≥1 ms and representable in long nanoseconds; per-call timeoutMillis uses the same range |
| handshakeTimeout(Duration) | 5 s | Same |
| reconnectDelay(Duration) | 1 s | Positive base delay |
| reconnectRandomDelay(Duration) | base / 4 | Nonnegative maximum added per retry (uniform whole milliseconds); 0 = fixed delay. 1000 ms + 250 ms waits 1000..1250 ms |
| heartbeatInterval(Duration) | 10 s | Positive |
| heartbeatTimeout(Duration) | 30 s | Greater than the interval |
| maxFrameSize(int) | 4 MiB | ≥32, including the length prefix; `RpcNode.maxFrameSize()` returns it, and components composed on the Node (Router) derive their limits from it instead of a setting of their own |
| maxPendingCalls(int) | 65536 | ≥1, pending calls allowed per Peer (R-SEND-04); a call beyond it fails at once with onFail(UNAVAILABLE) and its body released; notify/reply are not counted |
| maxInboundPeers(int) | 1024 | ≥1, Peers learned from inbound handshakes (R-HS-06); beyond it a handshake announcing an unknown Node is refused and its connection closed, while admitted Peers keep theirs; Peers configured with addPeer are not counted |
| maxConnections(int) | 4096 | ≥1, connections this Node holds at once, dialed and accepted together (R-HS-06); one beyond it is closed before its handshake, which bounds a dial storm between handshake timeouts |
| eventLoopGroup(EventLoopGroup) | none (Node-owned) | Caller-owned event loops, for example shared with the client gateway; the Node never shuts it down and close still waits (up to 5 s) for its own connections to end. Without it the Node creates Epoll or NIO loops for the platform |
| writeBufferWaterMark(low, high) | Netty 32 KiB / 64 KiB | Above `high` a connection is unwritable; a call/notify moves to the next Slot and returns UNAVAILABLE when none is writable, while a reply has no other Slot to take (R-SEND-01). Internal links usually need larger values |
| channelOption(option, value) | none | Applied to inbound and outbound connections (TCP_NODELAY, SO_SNDBUF, …) |
| transport(Consumer&lt;ChannelPipeline&gt;) | none | Byte-level handlers placed before RPC framing: SslHandler (addFirst), traffic shaping, logging. They must not decode RPC frames |
| handshakeSecret(byte[] [, Duration]) | none | ≥16 bytes, identical across the cluster; clock skew 60 s by default |

Duration.toMillis overflow throws ArithmeticException; other out-of-range values throw IllegalArgumentException. RpcNode owns its TCP NetworkServer/NetworkClient; by default it also creates its EventLoopGroups through [NetworkTransports](OGBS-Network-Java-25-Specification-1.0.md) (Epoll when the native library loads on Linux, NIO otherwise), or it runs on a caller-owned group passed to eventLoopGroup. Framing, heartbeat and handshake stay internal.

`start` is one-shot: it creates the timer and event loops and enters RUNNING only after a successful bind; failure closes partial resources and throws RpcException. `localAddress` is null before bind and returns the bound (possibly dynamic) port afterwards.

`addPeer` requires RUNNING, a nonzero non-self ID, an address and 1..255 Slots. Repeating the same configuration is idempotent; a different address or Slot count throws IllegalStateException. Only one side of a pair calls addPeer: calling it for a node that already dialed in throws IllegalStateException, and an inbound handshake from a node this side dials is rejected. `removePeer` of a missing Peer is a no-op.

<a id="direct-forwarding-and-infrastructure-extensions"></a>
<a id="direct-forwarding-and-handler-composition"></a>
<a id="handler-composition-and-node-notifications"></a>

### Handler composition and availability inspection

RpcHandler has only onRequest, onResponse and onFail and is fixed at construction; there is no Handler mutation API, readiness listener or cleanup listener. `isPeerConnected` reports whether any Slot of the Peer has an active connection (false in NEW/CLOSED); `peerSlotCount` returns the configured Slot count or 0. Both are lock-free snapshots: they establish neither writability, delivery nor remote process identity. A passive Peer disappears once it has neither connections nor pending calls. External discovery owns service existence.

<a id="3-消息与-bytebuf-所有权"></a>

## 3. Messages and ByteBuf ownership

```java
public record RpcRequest(int command, int requestId, long routeKey, int businessIdType,
                         long businessId, Metadata metadata, ByteBuf body) {}
new RpcRequest(command, body);
new RpcRequest(command, routeKey, businessIdType, businessId, metadata, body);

public record RpcResponse(int requestId, int errorCode, Metadata metadata, ByteBuf body) {}
```

command is nonzero and businessIdType 0..255; a request's requestId is 0 or positive, a response needs a positive requestId, and errorCode ≥ 0. Invalid construction throws IllegalArgumentException without releasing the body. Null Metadata becomes empty; a null body is an empty payload.

| Boundary | Ownership |
| --- | --- |
| Argument, null or lifecycle validation fails | Caller keeps the body |
| Validated call/notify/reply | Exactly one body reference is consumed on every later path |
| Missing Peer or no candidate connection | Body released without encoding |
| Encoding (success or failure) | Encoder consumes the body; on success only the frame remains. A body over 2 KiB becomes part of the frame, so its reference count stays positive until the frame is written: never release a body after the call returned, only if it threw before validation |
| Network ACCEPTED | Frame belongs to Network; no further fallback |
| Every connection rejects | RPC releases the frame |
| Inbound onRequest/onResponse | Body is borrowed; retain or copy it for asynchronous use |

The encoder checks the total length before allocating. Bodies up to 2048 bytes are copied behind the header into one buffer; larger bodies are not copied: the frame is a composite of the header and the body, and the body reference moves into it. Non-empty custom Metadata must pass Core's structural validation. Encoding failures throw RpcEncodeException after releasing everything they own.

Inbound Metadata is copied and validated; the body is a slice of the frame, which Network releases after the callback. Even a synchronous echo must retain the body before passing it to `reply`.

<a id="4-handlercallback-与异常"></a>

## 4. Handler, callback and exceptions

```java
public interface RpcHandler {
    void onRequest(int sourceNodeId, int sourceSlotId, RpcRequest request);
    void onResponse(int sourceNodeId, int requestCommand, RpcResponse response, RpcCallback<?> callback);
    void onFail(int targetNodeId, int requestCommand, int errorCode, RpcCallback<?> callback);
}
@FunctionalInterface
public interface RpcCallback<T> { void onResponse(T response); }
```

The handler must be thread-safe and return quickly. Requests, responses and failures detected on a connection (malformed response, loss of a Peer's last connection) run on Netty EventLoops; immediate send failures run on the caller; removal and closure on the management thread; a timeout on the Node's timer thread. The framework only delivers the notification: a handler that needs more than a short step hands it to its own executor (the Spring adapter, for example, submits it to the caller's Route), because a blocked timer thread delays later timeouts, reconnects and heartbeats. Every remote response, including framework and business errors, goes to onResponse; only local failures go to onFail. The application decodes and invokes its RpcCallback.

A RuntimeException from onRequest is logged; for a Call an empty HANDLER_ERROR response is attempted and the connection stays open. Exceptions from onResponse/onFail are logged without a second notification. Stack traces never reach peers.

| Situation | Java result |
| --- | --- |
| Null required argument | NullPointerException |
| Invalid argument, timeout or Slot range | IllegalArgumentException |
| API used in NEW or CLOSED | IllegalStateException |
| notify/reply outcome | RpcSendStatus ACCEPTED / PEER_NOT_FOUND / UNAVAILABLE |
| call without Peer or writable Slot | onFail(PEER_NOT_FOUND / UNAVAILABLE) |
| Peer loses its last connection | onFail(UNAVAILABLE) for its pending calls, immediately |
| Timeout / removePeer / close | onFail(TIMEOUT / PEER_REMOVED / NODE_CLOSED) |
| Encoding failure | RpcEncodeException, synchronous, body consumed |
| requestId collision after wrap | RpcException, synchronous; the older call is kept |
| Malformed matched response | onFail(PROTOCOL_ERROR); the connection is closed |
| Synchronous Network write exception | Pending call and frame cleaned, connection closed, exception propagated |

`reply` validates sourceSlotId (0..254 and below the Peer's Slot count); failure leaves the body with the caller. It answers on that Slot alone (R-SEND-01): a gone or unwritable connection there returns UNAVAILABLE with the body released, and the caller's call then ends on its timeout. `forwardResponse` is the relay entry: it takes no source Slot and selects one from routeKey like a notify, for a Router delivering a response it did not produce. RpcErrorCodes aliases the Core numbers.

<a id="5-call-发送与完成顺序"></a>

## 5. Call order

1. Validate, enter the closure barrier and take ownership of the body.
2. Without a Peer or candidate connection, release the body and notify the failure.
3. Allocate a Node-wide request ID (never 0; not reset when a Peer is recreated) and encode.
4. Register the PendingCall with putIfAbsent; a collision throws instead of overwriting.
5. Recheck that the Node runs and the Peer is still current; otherwise fail the call.
6. Send with affinity and fallback; the frame is accepted at most once.
7. After ACCEPTED, schedule the timeout; if the call already completed, cancel it.

Response, timeout, removal, closure, send failure and last-connection loss compete through a conditional remove, so each call completes exactly once. Losing one of several Slots keeps pending calls: the response may arrive on another Slot. Each Peer's pending calls are bounded by maxPendingCalls (R-SEND-04): a place is reserved before registration and returned by whichever completion (response, timeout, removal, closure, send failure) wins the conditional remove; a synchronous failure such as an ID collision returns the reservation too. There is no byte bound: each call is still limited by maxFrameSize.

<a id="6-网络装配与-peer-并发"></a>

## 6. Network assembly and concurrency

The pipeline is: application `transport` handlers, then `rpc-flush` (Netty FlushConsolidationHandler(256, true), which merges flushes from concurrent senders into one write per EventLoop turn), then `rpc-frame` (LengthFieldBasedFrameDecoder) and `rpc-idle` (IdleStateHandler), both added by `RpcWire.configurePipeline`. Only complete frames refresh the read deadline.

One HashedWheelTimer per Node (10 ms tick) drives call and handshake deadlines and delayed reconnects; a call timeout's onFail is delivered directly on that thread (see the thread rules above), so the handler hands business work to its own executor. Peers live in a copy-on-write int→Peer table: sends look up by primitive int without boxing or locking; topology changes (start, close, addPeer, removePeer, handshake binding, passive-Peer reclamation) copy and publish a new table under the lifecycle lock, which sending never takes. Each Slot holds its connection in an AtomicReference (identity-checked bind/unbind) and an AtomicBoolean marking the single recovery chain (delay, connect, handshake). When a Slot's connection ends, the Node fails the Peer's pending calls if no Slot is connected any more, then starts recovery for active Peers.

<a id="7-关闭屏障与线程边界"></a>

## 7. Closure barrier and thread boundaries

Use one long-lived RpcNode per service: start it once, close it once from a management thread. A remote restart or connection failure keeps the local Node running; there is no pause, resume or reuse after close.

`close` sets CLOSED and removes all Peers under the lifecycle lock, waits until admitted API operations have left (a striped LongAdder count, so concurrent senders do not contend and there is no party limit), fails pending calls with NODE_CLOSED, closes connections, listener, client and event loops, and stops the timer, which waits for a timeout notification already running. Concurrent closes wait for the same cleanup; resource errors are logged and cleanup continues. start/close from this Node's handler, EventLoop or timer throws IllegalStateException to prevent self-waiting.

After close returns, no pending call, connection, timer or owned notification remains. Work the application dispatched elsewhere is not awaited: stop business admission first, then close RPC.

<a id="8-可运行示例与源码"></a>

## 8. Example and source

[RpcEchoExample](../../game-demo/src/main/java/cn/managame/demo/examples/rpc/RpcEchoExample.java) runs two Nodes with a call, decoding and a retained echo reply; RpcExampleTest executes it. Sources: [RpcNode](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java), [RpcNodeBuilder](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNodeBuilder.java), [RpcWire](../../game-rpc/src/main/java/cn/managame/rpc/netty/RpcWire.java). RpcWire offers configurePipeline, encode/decode for every message type and the handshake proof, and publishes the frame layout constants (`TYPE_OFFSET`, `REQUEST_ID_OFFSET`, `REQUEST_ROUTE_KEY_OFFSET`, `RESPONSE_ID_OFFSET`, `MIN_REQUEST_FRAME`, `MIN_RESPONSE_FRAME`, …) so components such as the Router read routing headers without redefining offsets; direct use lacks the Node's handshake state, Peers and completion arbitration.

<a id="9-验证与未实现范围"></a>

## 9. Validation and limits

- [RpcWireTest](../../game-rpc/src/test/java/cn/managame/rpc/netty/RpcWireTest.java): golden vectors, integer ranges, Metadata, reference counts, limits, fragmented and malformed frames.
- [RpcNodeTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcNodeTest.java): Slot fallback, completion races, ID wrap, last-connection loss, large-body sharing, passive lifetime, handshake timeout, handler failures, closure.
- [RpcIntegrationTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java): real TCP, reconnection, cross-Slot responses.
- [RpcResilienceTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcResilienceTest.java): Peer recreation, recovery ownership races, timeout isolation, unfinished handshakes.
- [RpcSecurityTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcSecurityTest.java): shared secret, replayed/stale/forged handshakes, transport order, water mark.

Run `mvn -pl game-rpc -am test`; cross-module changes need root `mvn clean verify`.

<a id="91-审阅确认的缺陷与规模风险"></a>
<a id="92-后续审阅与扩展候选"></a>
<a id="rpc-router-review-2026-10-07"></a>

Remaining limits: closure scans all connections per Peer; the wire carries no Node incarnation, so IDs restarting with a new process can match replies saved by a peer (R-CALL-04). An answer already travels only over the Slot that carried its request (R-SEND-01), which narrows that window without closing it; closing it needs the caller to accept a response only on the connection it called over, which a Router cannot satisfy while it relays a routed response over whichever connection it holds and Router Profile v4 carries no caller Slot, so a wire field or Router state is required. Handlers run on EventLoops: blocking one delays every connection on it. Not implemented: TLS/WebSocket builder options, discovery, business retries, remote cancellation, durable delivery. Unverified: cross-language interoperability, production throughput and memory. Router composes on an existing Node and uses only `isPeerConnected` and `forwardResponse` beyond the ordinary API ([review record](../reviews/2026-10-07-router-rpc-review.md#rpc-integration)).

<a id="10-stats"></a>

## 10. Stats

Implements [RPC §12](OGBS-RPC-1.0.md#12-observability). `RpcNode.stats()` returns `cn.managame.rpc.node.RpcStats`, a record of nine `long` components in the order `peers, peersConnected, connections, pendingCalls, callsRejected, callsTimedOut, sendsRefused, handshakesAccepted, reconnects`.

The five counters are `LongAdder` fields in one package-private holder, because a refusal arrives on the caller's thread, a timeout on the timer thread and a handshake on an EventLoop. Each is taken either once per connection or on a branch that was already exceptional, so a Node sending and receiving normally never touches the holder: C-OBS-05 is satisfied by construction, which is why there is no switch. The four gauges are **not** counted: `stats()` walks the Peer table and, per Peer, counts Slots with a connection and the size of its pending map. Nothing therefore has to be kept in step with the Peer table, which is where the node's own races already are; the cost is proportional to the number of Peers, so `stats()` is for periodic collection and not for a data path.

Increment points, each a single site:

| Reading | Where |
| --- | --- |
| `callsRejected` | the `reservePending` refusal in `call`, before the body is released |
| `callsTimedOut` | in the timeout task, after its conditional `removePending` won, so exactly once per call |
| `sendsRefused` | the refusal branch of the one internal `send` that every outbound request, response and heartbeat goes through |
| `handshakesAccepted` | where a connection is bound to its Slot, both the initiating and the inbound path |
| `reconnects` | the recovery scheduling entry, after the ownership check |

Counting at the internal `send` is deliberate: `notify`, `reply`, `forwardResponse` and `call` all pass through it, so one site covers every public send and a refusal is counted even when the caller ignores the returned `RpcSendStatus`. Only the refusal branch increments; the accepted branch does nothing, and `fireRequest` and the successful part of `call` were deliberately left uncounted. `answer`, which replies on the request's own Slot without fallback, is the one outbound path outside `send` and its refusals are therefore not counted in V1.

No reading is configurable and none can be turned off, because with nothing on the success paths there is nothing to turn off. Adapting a snapshot to a registry is [Spring §5](OGBS-Spring-Java-25-Specification-1.0.md#5-observability).

Validation: [RpcStatsTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcStatsTest.java) covers an idle Node reporting zeros, a bound Slot appearing as a connected Peer with an accepted handshake, 100 notifies plus an inbound request and a completed call leaving every counter unchanged while only the gauges move, and a call refused by `maxPendingCalls` being counted separately from a send refused by an unwritable Slot.
