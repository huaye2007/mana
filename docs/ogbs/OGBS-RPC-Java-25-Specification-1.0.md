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
| cn.managame.rpc.example | Runnable RpcEchoExample |

Responsibility-based packages follow the user's confirmed choice. Read-only records avoid exposing requestId setters or internal cross-package bridges. Node passes its assigned ID to the encoder and **does not mutate the caller's Request**. Public full constructors can represent decoded messages, but call/notify reject nonzero requestId. This differs from the earlier single-package mutable-object sketch without changing Wire or call semantics.

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
| reconnectDelay(Duration) | 1 second | Same |
| heartbeatInterval(Duration) | 10 seconds | Same |
| heartbeatTimeout(Duration) | 30 seconds | Greater than interval |
| maxFrameSize(int) | 4 MiB | >=32, including length prefix |

Duration.toMillis overflow synchronously throws ArithmeticException. Values below 1ms or beyond Long.MAX_VALUE nanoseconds throw IllegalArgumentException. Per-call timeoutMillis has the same validation.

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

RpcHandler must be thread-safe and return quickly. Requests/responses normally run on Netty EventLoops; immediate failures on the caller thread; timeouts on the timing wheel; removal/closure failures on management threads. No callbackExecutor or automatic Runtime Context restoration.

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
3. Allocate Peer AtomicInteger ID, skipping 0; encode final frame.
4. Register PendingCall with ConcurrentHashMap.putIfAbsent; never overwrite or scan on collision.
5. Recheck Node validity and Peer object identity; fail if stale.
6. Attempt affinity/fallback sending; accept the frame at most once.
7. After ACCEPTED, register timeout with HashedWheelTimer and write volatile Timeout.
8. Recheck map membership; if already completed, cancel the new Timeout.

PendingCall stores only ID, command, callback, and volatile Timeout. Conditional remove arbitrates timeout/removal/closure/send failure. Response reads ID, removes PendingCall, cancels Timeout, then parses the remainder. Drop unmatched responses directly; malformed matched responses still notify PROTOCOL_ERROR.

No maxPendingCallsPerPeer or message retry queue. Volume and timeout determine memory use and require production measurement. Netty reclaims canceled timing-wheel tasks; the discarded DelayQueue design retained them until deadline.

<a id="6-网络装配与-peer-并发"></a>

## 6. Network assembly and Peer concurrency

RpcWire.install adds LengthFieldBasedFrameDecoder(maxFrameSize,0,4,0,4), then IdleStateHandler. Complete frames drive Read Idle; fragments cannot keep a connection alive indefinitely. Network adaptation receives ByteBuf with the four-byte prefix stripped.

Network guarantees client onConnected before ConnectCallback.onSuccess. The former installs AttributeKey<RpcConnectionContext> and handshake timeout; the latter sets expectedPeer/expectedSlot and starts handshake. Do not bind a Slot twice across these callbacks.

One HashedWheelTimer per Node, tick=10ms, handles call/handshake timeouts and fixed-delay reconnect. Per-connection IdleStateHandler handles heartbeats. Neither timing wheel nor EventLoop runs blocking business work.

Peers use ConcurrentHashMap<int,RpcPeer>. Low-frequency start/close/add/remove/handshake topology changes use a lifecycle lock. compute arbitrates same-ID passive creation, upgrade, and cleanup. Hot sends do not acquire the lifecycle lock.

Slot uses AtomicReference<Connection> for identity-aware bind/unbind and AtomicBoolean connecting over the entire recovery chain. Old asynchronous Peer tasks check map object identity without a separate removed flag. Active target address is volatile; disconnection does not clear waiting calls.

Native Netty AttributeKey stores RPC context. Node separately tracks owned connections solely for closure and unfinished-handshake cleanup, not a public ConnectionManager.

<a id="7-关闭屏障与线程边界"></a>

## 7. Closure barrier and thread boundaries

Under the lifecycle lock, close first sets CLOSED and detaches current Peers. Outside it, wait for admitted API operations, terminate PendingCalls/Slots, close unfinished handshakes/connections, NetworkServer/Client, owned boss/worker groups, and timing wheel. Never hold the topology lock while waiting for EventLoops.

Phaser admission covers a sender registering PendingCall after close clears the map; it is not a business queue and does not change routing. CountDownLatch makes concurrent close await the same cleanup. Application RuntimeException cannot stop other cleanup; resource-close errors log and cleanup continues.

start/close from this Node's RpcHandler, EventLoop, or timing wheel throws IllegalStateException before lifecycle mutation to prevent self-wait. Reentrant close during management-thread cleanup callbacks is idempotent. Shut down from an independent management thread.

After close returns, no PendingCall, RPC connection, or timing wheel remains. It does not await work submitted to Runtime/other executors. Stop admission and handle business work first, then close RPC. Startup failure cannot restart.

<a id="8-可运行示例与源码"></a>

## 8. Runnable example and source

[RpcEchoExample](../../game-rpc/src/main/java/cn/managame/rpc/example/RpcEchoExample.java) starts two local nodes on dynamic ports, performs TCP handshake/call, application string decoding, and reply. It retains inbound body before reply and cleans nodes using try-with-resources.

Main entries: [RpcNode](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNode.java), [RpcNodeBuilder](../../game-rpc/src/main/java/cn/managame/rpc/node/RpcNodeBuilder.java), [RpcWire](../../game-rpc/src/main/java/cn/managame/rpc/netty/RpcWire.java).

RpcWire exposes install, encodeRequest(request,assignedId,maxFrameSize), encodeResponse, encodeHandshake, encodeHeartbeat, and matching decoders. Encoding consumes body; decoding returns slices borrowed from the input frame. Follow Javadoc type/ID read-position requirements. Direct RpcWire use lacks RpcNode's handshake state, Peers, and completion arbitration and is not a complete semantic RPC implementation.

<a id="9-验证与未实现范围"></a>

## 9. Validation and unimplemented scope

- [RpcWireTest](../../game-rpc/src/test/java/cn/managame/rpc/netty/RpcWireTest.java): four golden vectors, uint32/uint64, Metadata, reference counts, length limits, fragmented/coalesced frames, malformed frames.
- [RpcNodeTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcNodeTest.java): Slot fallback, fast responses, completion races, ID wrap/collision, passive lifetime, heartbeat rejection, handshake timeout, Handler exceptions, closure barrier.
- [RpcIntegrationTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcIntegrationTest.java): real TCP bidirectional communication, independent reconnection, cross-Slot responses, active recovery, passive upgrade.
- [RpcExampleTest](../../game-rpc/src/test/java/cn/managame/rpc/node/RpcExampleTest.java): complete example execution.

Targeted command: mvn -pl game-rpc -am test. Dependency/cross-component changes require root mvn clean verify. Windows tests reuse Network's TCP Selector wakeup compatibility setting; production code does not change JVM properties.

Unimplemented: automatic Runtime integration, TLS/WS RPC Builder, discovery, Router, retries, remote cancellation, durable delivery. Unverified: actual cross-language interoperability, production throughput/memory limits, sustained stress, public-network deployment. Passing tests do not establish these capabilities.
