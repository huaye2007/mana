# OGBS RPC Java 25 Development Specification 1.0

**[English](OGBS-RPC-Java-25-Specification-1.0.md)** | [简体中文](OGBS-RPC-Java-25-Specification-1.0.zh-CN.md)

Document type: **Java Development Specification**. Standard: [RPC Specification](OGBS-RPC-1.0.md). Byte layout: [Wire Profile](../rpc-wire.md).

Status: game-rpc has an implementation, but the current restored source has confirmed contract deviations and its RPC test suite does not compile. See section 9.1; previous repair claims do not describe this source. JDK 25 without preview; Maven cn.managame:game-rpc:1.0.0-SNAPSHOT depends on game-core and game-network, transitively using Netty. No Runtime/Data, Spring, business protocol codec, or discovery dependency.

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
| heartbeatInterval(Duration) | 10 seconds | Same positive duration range as callTimeout |
| heartbeatTimeout(Duration) | 30 seconds | Greater than interval |
| maxFrameSize(int) | 4 MiB | >=32, including length prefix |

Duration.toMillis overflow synchronously throws ArithmeticException. Values below 1ms or beyond Long.MAX_VALUE nanoseconds throw IllegalArgumentException. Per-call timeoutMillis has the same validation. Current RpcNodeBuilder does not expose maxPendingCalls or reconnectJitter. There is no application call-admission bound; retries use a fixed reconnectDelay, default 1000ms. The finite bound required by R-SEND-04 remains unimplemented. Current retry timing corresponds to the zero-jitter case permitted by R-LIVE-03; configurable jitter is not provided.

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

RpcHandler must be thread-safe and return quickly. Requests/responses and matched-response protocol failures normally run on Netty EventLoops; immediate failures on the caller thread; removal/closure failures on management threads. In the current source, call timeout runs fail and onFail directly on the shared maintenance timer. A blocked timeout handler therefore delays other calls, handshake deadlines and reconnect tasks. This violates R-CALL-03; isolation is not implemented. There is no callback executor, call-admission reservation, global callback ordering or automatic Runtime Context restoration.

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
3. Allocate ID from the current Peer AtomicInteger, skipping 0, and encode the final frame. No admission permit is reserved. Recreating a Peer resets allocation to 1, contrary to R-CALL-04.
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

One HashedWheelTimer per Node, tick=10ms, handles call/handshake timeouts and fixed-delay reconnect. Current call-timeout tasks directly invoke application onFail, so maintenance can be blocked by application work; see sections 4 and 9.1. Immediate failures, responses and management notifications retain their execution threads. Per-connection IdleStateHandler handles heartbeats; EventLoop handlers must return quickly. timerThread identifies the timer thread for self-wait prevention; it does not create an additional worker.

Peers use ConcurrentHashMap<int,RpcPeer>. Low-frequency start/close/add/remove/handshake topology changes use a lifecycle lock. compute arbitrates same-ID passive creation, upgrade, and cleanup. Hot sends do not acquire the lifecycle lock.

Slot uses AtomicReference<Connection> for identity-aware bind/unbind and AtomicBoolean connecting over the entire recovery chain. Old asynchronous Peer tasks check map object identity without a separate removed flag. Active target address is volatile; disconnection does not clear waiting calls.

Native Netty AttributeKey stores RPC context. Node tracks all owned connections globally, including unassociated inbound handshakes; current RpcPeer has no connection index. detach scans the global set to find READY or expected connections belonging to that Peer, then clears its Slots. close repeats this scan for each Peer, giving O(P × C + S) connection/Slot traversal, before a final global pass; pending-call completion adds separate work. No large-topology shutdown latency result is available. Outbound expectedPeer association is published after a current-Peer check without lifecycleLock; concurrent removal deserves permanent coverage rather than an unconditional claim that late association cannot be missed.

<a id="7-关闭屏障与线程边界"></a>

## 7. Closure barrier and thread boundaries

The deployment model follows RPC Specification section 1: use a long-lived RpcNode for the service, start it once during service startup, and close it from an independent management context during maintenance or exit. A remote restart or connection failure keeps the local RpcNode running. There is no pause/resume, reuse after close, hot replacement coordinator or dedicated shutdown worker. The application owns stopping business admission and managing business work before close; see the standard's section 8. Abrupt JVM termination cannot execute a cleanup barrier that was never invoked.

Simplification guidance: preserve the existing one-shot lifecycle and resource ownership, with a single ordinary cleanup path. Phaser, CountDownLatch and thread-identity fields currently implement accepted-operation and self-wait boundaries; removing them requires checking those boundaries rather than treating each field as another thread. Optimize repeated global cleanup scans only if maintenance measurements justify it. Additional executors, lifecycle states and drain APIs need a concrete execution or maintenance requirement. This clarification changes no public API or current closure behavior.

Under the lifecycle lock, close first sets CLOSED and removes current Peers from topology. Outside it, wait for admitted API operations, detach PendingCalls/Slots, close unfinished handshakes/connections, NetworkServer/Client, owned boss/worker groups, and the timing wheel. Current source has no timeout-notification executor. timer.stop must wait for a running timer callback; a blocked onFail can therefore delay close indefinitely. Never hold the topology lock while waiting for EventLoops. closingThread records the management thread performing cleanup so its reentrant close returns; it does not create a shutdown thread.

Phaser admission covers a sender registering PendingCall after close clears the map; it is not a business queue and does not change routing. CountDownLatch makes concurrent close await the same cleanup. Application RuntimeException cannot stop other cleanup; resource-close errors log and cleanup continues.

start/close from this Node's RpcHandler, EventLoop, or timing wheel throws IllegalStateException before lifecycle mutation to prevent self-wait. Reentrant close during management-thread cleanup callbacks is idempotent. Shut down from an independent management thread.

After close returns, no PendingCall, RPC connection, timing wheel, or owned timeout notification remains. It does not await work submitted to Runtime/other executors. Stop admission and handle business work first, then close RPC. Startup failure cannot restart.

<a id="8-可运行示例与源码"></a>

## 8. Runnable example and source

[RpcEchoExample](../../game-example/src/main/java/cn/managame/example/rpc/RpcEchoExample.java) is intended to start two local nodes on dynamic ports and demonstrate TCP handshake/call, string decoding, body retain and reply. Its current maxPendingCalls(1024) invocation does not compile against the restored Builder. It is not currently a verified runnable example.

Main entries: [RpcNode](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java), [RpcNodeBuilder](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNodeBuilder.java), [RpcWire](../../game-rpc/src/main/java/cn/managame/rpc/netty/RpcWire.java).

RpcWire exposes configurePipeline, encodeRequest(request,assignedId,maxFrameSize), encodeResponse, encodeHandshake, encodeHeartbeat, and matching decoders. Encoding consumes body; decoding returns slices borrowed from the input frame. Follow Javadoc type/ID read-position requirements. Direct RpcWire use lacks RpcNode's handshake state, Peers, and completion arbitration and is not a complete semantic RPC implementation.

<a id="9-验证与未实现范围"></a>

## 9. Validation and unimplemented scope

- [RpcWireTest](../../game-rpc/src/test/java/cn/managame/rpc/netty/RpcWireTest.java): four golden vectors, uint32/uint64, Metadata, reference counts, length limits, fragmented/coalesced frames, malformed frames.
- [RpcNodeTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcNodeTest.java): Slot fallback, fast responses, completion races, ID wrap/collision, passive lifetime, heartbeat rejection, handshake timeout, Handler exceptions, closure barrier.
- [RpcIntegrationTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java): real TCP bidirectional communication, independent reconnection, cross-Slot responses, active recovery, passive upgrade.
- [RpcResilienceTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcResilienceTest.java): Peer recreation, concurrent admission, notification isolation/ownership, unfinished handshake closure, reconnect configuration.
- [RpcExampleTest](../../game-example/src/test/java/cn/managame/example/rpc/RpcExampleTest.java): complete example execution in game-example; run mvn -pl game-example -am test.

Current validation: mvn -o -pl game-rpc -am test passed 7 Core and 60 Network tests but failed RPC test compilation with 33 errors. Tests reference removed requestIds/admittedCalls/maxPendingCalls/reconnectJitter and the Peer connection index. The test inventory above describes intended coverage, not passing verification of this source. Targeted command: mvn -pl game-rpc -am test. Dependency/cross-component changes require root mvn clean verify. Windows tests reuse Network's TCP Selector wakeup compatibility setting; production code does not change JVM properties.

Unimplemented: automatic Runtime integration, TLS/WS RPC Builder, discovery, Router, retries, remote cancellation, durable delivery. Unverified: actual cross-language interoperability, production throughput/memory limits, sustained stress, public-network deployment. Passing tests do not establish these capabilities.

<a id="91-审阅确认的缺陷与规模风险"></a>

### 9.1 Current-source review and remaining scale boundaries

Review baseline: restored source at commit da7bc6e, reviewed on 2026-10-01. No production code was changed. Earlier claims of completed repairs are withdrawn for this baseline; the existing semantic requirements remain in force.

**Confirmed correctness defect: Peer recreation reuses call IDs.** [RpcPeer](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcPeer.java) owns requestId starting at 1. A new same-remote Peer restarts this allocator. A real TCP diagnostic called command 101, removed/re-added the Peer, and called command 202. Both received ID 1; the saved old response completed command 202 with the old body, and the correct later response was dropped. Old physical-connection rejection does not prevent a saved business response arriving over a valid replacement connection. This violates R-CALL-04 within one Node lifetime. Active recreation was reproduced; passive recreation uses the same allocator reset and requires permanent regression coverage. [RpcIntegrationTest.oldBusinessReplyAfterPeerRecreationCannotCompleteNewCall](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java) expresses the intended boundary but is currently blocked by suite compilation.

**Confirmed maintenance defect: a slow timeout handler blocks other deadlines.** [RpcNode.call/fail](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java) invokes onFail on the shared HashedWheelTimer. A diagnostic held one timeout callback; after 200ms, a second call with a 20ms timeout was still pending and a silent connection with a 60ms handshake deadline remained active. Both progressed after releasing the callback. Reconnect tasks share this timer, and close waits for the timer callback. This violates the timeout-notification isolation requirement of R-CALL-03. There is no timeout callback executor in this baseline.

**Confirmed recovery defect: stopping a chain can miss concurrent unbind.** reconnect/connect checks for an occupied Slot separately from clearing connecting. disconnected may unbind between those operations, see connecting=true, and schedule nothing; reconnect then clears it and returns. A temporary two-thread diagnostic invoked the real reconnect/disconnected methods with a recording timer, observing two empty Slots with connecting=false and no task scheduled in 30000 rounds. Per-round recording excludes retries from earlier rounds. This is a synthetic race observation, not a production incidence or throughput result. It violates R-LIVE-03. Permanent deterministic regression coverage and repair remain pending; changing retry delay alone cannot fix it.

**Unimplemented bound: outstanding calls can grow without a configured limit.** Current Builder has no maxPendingCalls; pending maps, callbacks and timeout tasks are bounded only by traffic, response/timeout progress and memory. Network writability is not a bound on already-sent calls. For illustration, 20000 accepted calls/second waiting approximately 5 seconds can retain roughly 100000 calls; this is an estimate, not a benchmark. The finite admission contract R-SEND-04 is not implemented. Inbound handshakes/passive Peers also have no configured admission bound.

**Scale boundaries and priority.** detach scans Node.connections for each Peer. Closing P Peers with C connections visits up to P × C connection contexts, plus Slots and pending completions. This is a complexity finding, not measured shutdown latency. Under the long-lived service baseline, optimizing final closure is lower priority until measured maintenance impact justifies it. Fixed reconnectDelay has no jitter or global attempt rate limit, so simultaneous failures may synchronize retries; this affects running-service recovery. Every Node owns boss/worker groups; many Nodes in one JVM need resource sizing.

**Validation status.** Maven RPC tests fail during compilation; the current example also references a missing Builder API. The temporary source and log are under game-rpc/target/rpc-review-current and are removed by clean. The diagnostic verifies the reproduced defects, not conformance. Production throughput/memory, long-lived stress, cross-language interoperability and recovery convergence remain unverified. Repair priority: restore consistent build/test entry points, eliminate wrong-call completion and lost recovery, isolate timeout execution with explicit ownership, then measure and implement admission. No executor design, new retry protocol or wire change is selected by this review.

<a id="92-后续审阅与扩展候选"></a>

### 9.2 Validation boundaries and extension candidates

Wire v1 has no Node-incarnation field. Resetting IDs after replacing/restarting a Node can also admit saved old replies; this is the declared R-CALL-04 Node-lifetime boundary, distinct from the current same-Node Peer-recreation defect. Wider IDs alone do not fix restart reuse if allocation resets. An application epoch or explicitly versioned protocol needs its own design; this review selects neither.

Under the confirmed first-valid-binding-wins policy, simultaneous two-sided connections can each bind the incoming half of different physical connections, then reject both outgoing halves as duplicate Slots. Recovery convergence needs permanent real TCP coverage for simultaneous addPeer and simultaneous disconnection. This review does not establish permanent livelock or a production failure rate, and does not reintroduce Node-ID arbitration.

Request/response handlers run on connection EventLoops; blocking them delays other connections on the same EventLoop. Application dispatch elsewhere must retain/copy the borrowed body and release it on both acceptance and rejection paths. Admission, byte budgets and per-Peer fairness need measured limits; no current API supplies them. Existing diagnostics do not establish sustained capacity.

Optional diagnostics for the current baseline include Peer/Slot readiness and counts for pending calls, timeouts, unmatched replies, retries and handshake failures. They are candidates, not implemented or active requirements. Application/Runtime dispatch integration, a separate drain phase, local cancellation and CompletionStage adapters require an explicit business need before implementation; none is part of the current simplification work. Local cancellation does not stop remote execution. Discovery/routing, automatic business retries, receiver deduplication and durable delivery require separate contracts. First repair correctness and build consistency; internal decomposition and allocation/flush optimizations should follow measurements and preserve ownership, acceptance and completion ordering.
