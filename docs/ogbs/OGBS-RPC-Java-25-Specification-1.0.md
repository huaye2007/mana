# OGBS RPC Java 25 Development Specification 1.0

**[English](OGBS-RPC-Java-25-Specification-1.0.md)** | [简体中文](OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)

Document type: **Java Development Specification**. Standard: [RPC Specification](OGBS-RPC-1.0.md). Byte layout: [Wire Profile](../rpc-wire.md).

Status: implemented and locally verified. Peer recreation preserves Node-lifetime call IDs; timeout notifications are isolated from maintenance; recovery stop rechecks concurrent unbind. Finite call admission remains unimplemented/deferred under R-SEND-04. JDK 25 without preview; Maven cn.managame:game-rpc:1.0.0-SNAPSHOT depends on game-core and game-network, transitively using Netty. Core RPC has no Runtime/Data, Spring, business codec or discovery dependency. Optional Runtime integration belongs to [game-spring](OGBS-Spring-Java-25-Specification-1.0.md#managed-rpc).

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

Runnable examples and their execution tests live in game-demo (cn.managame.demo.examples.rpc), outside the game-rpc artifact. Example users must update their imports and module dependency; no old-package alias is retained. Component contract tests remain in game-rpc. Types use responsibility-based packages with package-private internals. Read-only records avoid exposing requestId setters or internal cross-package bridges. Node passes its assigned ID to the encoder and **does not mutate the caller's Request**. Public full constructors can represent decoded messages, but call/notify reject nonzero requestId. This encapsulation preserves Wire layout and call semantics.

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
| reconnectRandomDelay(Duration) | One quarter of base delay | Nonnegative additive maximum; zero gives fixed delay; sum must fit nanoseconds |
| heartbeatInterval(Duration) | 10 seconds | Same positive duration range as callTimeout |
| heartbeatTimeout(Duration) | 30 seconds | Greater than interval |
| maxFrameSize(int) | 4 MiB | >=32, including length prefix |

Duration.toMillis overflow synchronously throws ArithmeticException. Positive durations below 1ms or beyond Long.MAX_VALUE nanoseconds throw IllegalArgumentException; per-call timeoutMillis follows the same range. reconnectRandomDelay(Duration) is a nonnegative additive random-delay maximum, default reconnectDelay/4 truncated to milliseconds; zero means fixed delay. The base plus maximum must fit positive long nanoseconds. Each retry samples an inclusive integer-millisecond value from zero to that maximum. For a 1000ms base and 250ms maximum, wait 1000..1250ms. This spreads retries and is independent of recovery ownership; it is no global attempt limit. maxPendingCalls is not provided; finite admission under R-SEND-04 remains deferred.

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

RpcHandler must be thread-safe and return quickly. Requests/responses and matched-response protocol failures normally run on Netty EventLoops; immediate failures on the caller; removal/closure notifications on management threads. A winning timeout removes the PendingCall on the shared maintenance timer, then runs onFail in a separately tracked virtual thread. Slow notifications cannot block other maintenance deadlines. Each notification deregisters in finally, including handler failure; close stops the timer before awaiting these notifications. There is no fixed business thread pool, finite admission reservation, global callback ordering or automatic Runtime restoration in game-rpc itself.

All remote responses, including success/framework/business errors, go to onResponse. Applications interpret errors and decode by command/protocol, then invoke the appropriate RpcCallback<T>. Only local failures go to onFail; RpcCallback has no onFailure. Stored callbacks may carry application context, but the integration layer restores Route.

A RuntimeException from onRequest logs diagnostics; Call attempts an empty HANDLER_ERROR response, Notify only logs, and healthy connections remain open. RuntimeException from onResponse/onFail only logs, without repeated notification. Do not swallow JVM Error as ordinary business failure. System.Logger diagnostics never return stack traces to peers.

| Situation | Java result |
| --- | --- |
| Null required argument | NullPointerException |
| Invalid argument, timeout, or Slot range | IllegalArgumentException |
| Runtime API called in NEW/CLOSED | IllegalStateException |
| notify/reply acceptance/missing Peer/unavailable | Three RpcSendStatus values |
| call missing Peer/no writable Slot | onFail(PEER_NOT_FOUND/UNAVAILABLE) |
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
3. Allocate from the Node AtomicInteger, skipping zero, and encode the final frame. Peer removal/passive cleanup/recreation does not reset allocation; full uint32 wrap and Node restart retain R-CALL-04 boundaries. No finite admission reservation.
4. Register PendingCall with ConcurrentHashMap.putIfAbsent; never overwrite or scan on collision.
5. Recheck Node validity and Peer object identity; fail if stale.
6. Attempt affinity/fallback sending; accept the frame at most once.
7. After ACCEPTED, register timeout with HashedWheelTimer and write volatile Timeout.
8. Recheck map membership; if already completed, cancel the new Timeout.

PendingCall stores only ID, command, callback, and volatile Timeout. Conditional remove arbitrates timeout/removal/closure/send failure. Response reads ID, removes PendingCall, cancels Timeout, then parses the remainder. Drop unmatched responses directly; malformed matched responses still notify PROTOCOL_ERROR.

The pending map has no configured count or byte bound. Network writability only controls outgoing acceptance; it does not limit calls already sent whose responses remain outstanding. A reachable slow Peer can accumulate PendingCalls, callbacks and timer tasks throughout the timeout interval. Approximately, outstanding count follows accepted call rate multiplied by response residence time; this is a sizing relationship, not a measured capacity result. No per-Peer fairness, waiting queue or business retry queue is provided. A finite Node-wide admission bound required by R-SEND-04 remains unimplemented.

<a id="6-网络装配与-peer-并发"></a>

## 6. Network assembly and Peer concurrency

The pipeline entry point is `RpcWire.configurePipeline(pipeline, maxFrameSize, heartbeatIntervalMillis, heartbeatTimeoutMillis)`. This replaces the former `RpcWire.install` name without retaining an alias. Direct callers must update their source and recompile; existing binaries invoking the old method are incompatible. Pipeline order, framing, heartbeat behavior, and wire compatibility are unchanged.

RpcWire.configurePipeline adds LengthFieldBasedFrameDecoder(maxFrameSize,0,4,0,4), then IdleStateHandler. Complete frames drive Read Idle; fragments cannot keep a connection alive indefinitely. Network adaptation receives ByteBuf with the four-byte prefix stripped.

Network guarantees client onConnected before ConnectCallback.onSuccess. The former binds AttributeKey<RpcConnectionContext> and starts the handshake timeout; the latter sets expectedPeer/expectedSlot and starts handshake. Do not bind a Slot twice across these callbacks.

One HashedWheelTimer per Node, tick=10ms, handles call/handshake deadlines and delayed reconnect with the configured random addition. It never executes timeout business notifications inline. A concurrent set tracks separately started virtual notification threads; after timer.stop, close joins all remaining notifications uninterruptibly and restores interruption status. No late timer registration can escape this barrier. Immediate failures, responses and management notifications retain their execution threads. Per-connection IdleStateHandler handles heartbeats; EventLoop handlers must return quickly. timerThread records timer identity for self-wait prevention.

Peers use ConcurrentHashMap<int,RpcPeer>. Low-frequency start/close/add/remove/handshake topology changes use a lifecycle lock. compute arbitrates same-ID passive creation, upgrade, and cleanup. Hot sends do not acquire the lifecycle lock.

Slot uses AtomicReference<Connection> for identity-aware bind/unbind and AtomicBoolean connecting across delay, connect and handshake. To stop a chain after seeing an occupied Slot, first clear connecting, then recheck empty/current/active-target and reacquire with CAS. If unbind previously saw connecting=true, the stopping chain repairs that lost handoff; if unbind saw false, it reacquires itself and the old chain CAS fails. Either path maintains one recovery owner. The same clear/recheck rule covers a passive Peer upgrade racing a chain that observed no target, avoiding an empty Slot with no recovery after the target is published. Old Peer tasks check map object identity; disconnection does not clear waiting calls. RpcResilienceTest exercises 10000 synchronized race rounds; this is a concurrency regression, not a capacity benchmark.

Native Netty AttributeKey stores RPC context. Node tracks all owned connections globally, including unassociated inbound handshakes. Outbound expectedPeer/expectedSlot publication and the current-Peer check occur under lifecycleLock, so remove cannot miss an association published after its topology removal. RpcPeer has no connection index; detach scans the global set for READY/expected associations, then clears Slots. Closure traversal remains O(P × C + S) plus pending completion work. Large-topology shutdown latency is unverified and optimization is deferred.

<a id="7-关闭屏障与线程边界"></a>

## 7. Closure barrier and thread boundaries

The deployment model follows RPC Specification section 1: use a long-lived RpcNode for the service, start it once during service startup, and close it from an independent management context during maintenance or exit. A remote restart or connection failure keeps the local RpcNode running. There is no pause/resume, reuse after close, hot replacement coordinator or dedicated shutdown worker. The application owns stopping business admission and managing business work before close; see the standard's section 8. Abrupt JVM termination cannot execute a cleanup barrier that was never invoked.

Simplification guidance: preserve the existing one-shot lifecycle and resource ownership, with a single ordinary cleanup path. Phaser, CountDownLatch and thread-identity fields currently implement accepted-operation and self-wait boundaries; removing them requires checking those boundaries rather than treating each field as another thread. Optimize repeated global cleanup scans only if maintenance measurements justify it. Additional executors, lifecycle states and drain APIs need a concrete execution or maintenance requirement. This clarification changes no public API or current closure behavior.

Under lifecycleLock, close first sets CLOSED and removes current Peers. Outside it, await admitted API operations, detach PendingCalls/Slots, close connections/listening/client/owned groups, stop the timing wheel, then await all owned timeout notifications. Stopping the timer before the notification-join barrier prevents late registrations. A blocked notification can still keep close waiting, while other deadlines progress independently. Never hold the topology lock while waiting for EventLoops. closingThread makes reentrant management cleanup idempotent; no shutdown worker is created.

Phaser admission covers a sender registering PendingCall after close clears the map; it is not a business queue and does not change routing. CountDownLatch makes concurrent close await the same cleanup. Application RuntimeException cannot stop other cleanup; resource-close errors log and cleanup continues.

start/close from this Node's RpcHandler, EventLoop, or timing wheel throws IllegalStateException before lifecycle mutation to prevent self-wait. Reentrant close during management-thread cleanup callbacks is idempotent. Shut down from an independent management thread.

After close returns, no PendingCall, RPC connection, timing wheel, or owned timeout notification remains. It does not await work submitted to Runtime/other executors. Stop admission and handle business work first, then close RPC. Startup failure cannot restart.

<a id="8-可运行示例与源码"></a>

## 8. Runnable example and source

[RpcEchoExample](../../game-demo/src/main/java/cn/managame/demo/examples/rpc/RpcEchoExample.java) starts two local nodes on dynamic ports and demonstrates TCP handshake/call, string decoding, body retain and reply. RpcExampleTest executes the complete example in root clean verify. The optional Spring adapter has a separate usage example in [its specification](OGBS-Spring-Java-25-Specification-1.0.md#managed-rpc).

Main entries: [RpcNode](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java), [RpcNodeBuilder](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNodeBuilder.java), [RpcWire](../../game-rpc/src/main/java/cn/managame/rpc/netty/RpcWire.java).

RpcWire exposes configurePipeline, encodeRequest(request,assignedId,maxFrameSize), encodeResponse, encodeHandshake, encodeHeartbeat, and matching decoders. Encoding consumes body; decoding returns slices borrowed from the input frame. Follow Javadoc type/ID read-position requirements. Direct RpcWire use lacks RpcNode's handshake state, Peers, and completion arbitration and is not a complete semantic RPC implementation.

<a id="9-验证与未实现范围"></a>

## 9. Validation and unimplemented scope

- [RpcWireTest](../../game-rpc/src/test/java/cn/managame/rpc/netty/RpcWireTest.java): four golden vectors, uint32/uint64, Metadata, reference counts, length limits, fragmented/coalesced frames, malformed frames.
- [RpcNodeTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcNodeTest.java): Slot fallback, fast responses, completion races, ID wrap/collision, passive lifetime, heartbeat rejection, handshake timeout, Handler exceptions, closure barrier.
- [RpcIntegrationTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java): real TCP bidirectional communication, independent reconnection, cross-Slot responses, active recovery, passive upgrade.
- [RpcResilienceTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcResilienceTest.java): Peer recreation, recovery ownership races, notification isolation/ownership, unfinished handshake closure, reconnect configuration.
- [RpcExampleTest](../../game-demo/src/test/java/cn/managame/demo/examples/rpc/RpcExampleTest.java): complete example execution in game-demo; run mvn -pl game-demo -am test.

Current validation on 2026-10-03: root mvn clean verify passes all seven component/application modules, including 32 RPC tests and the runnable RPC example. Focused command: mvn -pl game-rpc -am test. Cross-component/dependency changes require root clean verify. Windows tests reuse Network TCP Selector wakeup compatibility; production changes no JVM properties.

Unimplemented/deferred: finite call admission, TLS/WS RPC Builder, discovery, Router, business retries, remote cancellation and durable delivery. Optional Spring decoding/Runtime dispatch/typed replies/Route callbacks are implemented outside the RPC core. Unverified: cross-language interoperability, production throughput/memory, sustained stress and public-network deployment.

<a id="91-审阅确认的缺陷与规模风险"></a>

### 9.1 Current repairs and remaining scale boundaries

Node-wide ID allocation preserves cross-Slot/replacement-connection replies while preventing immediate ID reuse after same-Node Peer recreation. [RpcIntegrationTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java) sends saved old business replies over real replacement TCP connections without completing fresh calls. [RpcResilienceTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcResilienceTest.java) covers passive recreation, timeout isolation/close ownership, unfinished-handshake closure, recovery ownership races and random-delay boundaries.

Replying only through the original physical Connection would lose otherwise valid replies after its disconnection and would not repair maintenance blocking or lost recovery ownership. Preserve logical Peer/Slot replies and repair IDs and concurrent handoff without changing Wire v1.

Finite call admission (R-SEND-04) and inbound handshake/passive-Peer limits remain unimplemented and deferred. Network writability does not bound already-sent calls. Closure still scans global connections per Peer; large-topology closure optimization and diagnostics are deferred. Random retry delay spreads attempts but imposes no global rate limit. A passing build does not remove these limits.

<a id="92-后续审阅与扩展候选"></a>

### 9.2 Validation boundaries and extension candidates

Wire v1 has no Node-incarnation field. Resetting IDs after replacing/restarting a Node can also admit saved old replies; this is the declared R-CALL-04 Node-lifetime boundary, distinct from same-Node Peer recreation, now protected by Node-wide allocation. Wider IDs alone do not fix restart reuse if allocation resets. An application epoch or explicitly versioned protocol needs its own design; this review selects neither.

Under the confirmed first-valid-binding-wins policy, simultaneous two-sided connections can each bind the incoming half of different physical connections, then reject both outgoing halves as duplicate Slots. Recovery convergence needs permanent real TCP coverage for simultaneous addPeer and simultaneous disconnection. This review does not establish permanent livelock or a production failure rate, and does not reintroduce Node-ID arbitration.

Request/response handlers run on connection EventLoops; blocking them delays other connections on the same EventLoop. Application dispatch elsewhere must retain/copy the borrowed body and release it on both acceptance and rejection paths. Admission, byte budgets and per-Peer fairness need measured limits; no current API supplies them. Existing diagnostics do not establish sustained capacity.

Optional diagnostics, a separate RPC drain phase and local cancellation remain extension candidates. Optional Spring Runtime dispatch is now implemented; it does not add discovery, business retries, receiver deduplication or durable delivery. Internal decomposition and allocation/flush optimizations require measurements and must preserve ownership, acceptance and completion order.