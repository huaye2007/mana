# OGBS Network Java 25 Development Specification 1.0

**[English](OGBS-Network-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Network-Java-25-Specification-1.0.zh-CN.md)

Document type: **Java Development Specification**. Standard: [OGBS Network Specification](OGBS-Network-1.0.md).

This document defines public Java APIs, defaults, exceptions, threads/resources, extension integration, and validation. Implementations must satisfy both documents, including behavior rather than signatures alone. Fix code that violates the contract; design changes update both specifications. Explicitly pending/unverified capabilities are not completed features.

Status: V1 Draft, current game-network implementation baseline. Shared semantics: [Network Specification](OGBS-Network-1.0.md).

Maven: `cn.managame:game-network:1.0.0-SNAPSHOT`. JDK 25 without preview. Root POM BOM manages Netty, currently 4.1.135.Final. Interfaces and Netty implementation share one artifact, without a game-core dependency.

<a id="1-包结构"></a>

## 1. Package structure

| Package | Public API |
| --- | --- |
| cn.managame.network.connection | Connection, ConnectionHandler, WriteStatus |
| cn.managame.network.connector | ConnectCallback, WebSocketConnectOptions |
| cn.managame.network.error | NetworkException |
| cn.managame.network.netty | NetworkServer, NetworkServerBuilder, NetworkClient, NetworkClientBuilder |

NettyConnection, ConnectAttempt, NetworkChannelInitializer, ConnectionLifecycle, ChannelTransport, ConnectionEstablishment, TlsTransport, WebSocketTransport, ConnectionHandlerAdapter, and WS payload adapters remain package-private in netty. Server/Client entries share their implementation package to avoid exposing internal collaboration types merely for package separation.

There is no attribute package or custom ConnectionKey: use Netty AttributeKey. Concrete NetworkServer/NetworkClient replace Acceptor/Connector. Module entry and runnable examples: [game-network](../../game-network/README.md).

<a id="2-connection-与-handler"></a>

## 2. Connection and Handler

```java
public interface Connection {
    boolean isActive();
    boolean isWritable();
    WriteStatus write(Object message);
    void close();
    SocketAddress localAddress();
    SocketAddress remoteAddress();
    <T> T get(AttributeKey<T> key);
    <T> void set(AttributeKey<T> key, T value);
    <T> T remove(AttributeKey<T> key);
}

public enum WriteStatus { ACCEPTED, INACTIVE, NOT_WRITABLE }

public interface ConnectionHandler {
    void onConnected(Connection connection);
    void onMessage(Connection connection, Object message);
    void onDisconnected(Connection connection);
    default void onEvent(Connection connection, Object event) {}
    void onException(Connection connection, Throwable cause);
}
```

Connection delegates active/writable and addresses to Channel; addresses may be null. Attributes use `channel.attr(key)`; remove uses `getAndSet(null)`. Same-name AttributeKeys follow Netty semantics; set(key, null) is allowed. Attributes are neither frozen nor cleared automatically. Callers ensure attribute-object concurrency safety.

write rejects null, checks active then writable, and calls `channel.writeAndFlush(message, channel.voidPromise())`. Reusable void promises route encoding/write failures through pipeline exceptionCaught. An internal head error forwarder sends established outbound errors past the automatically closing WS protocol handler into user pipeline/onException, without a public Future, callback, or send queue. Ownership transfers at Netty write submission. Only INACTIVE/NOT_WRITABLE guarantee retained caller ownership; arbitrary custom outbound-handler exceptions do not necessarily return it.

```java
ByteBuf buffer = ...;
if (connection.write(buffer) != WriteStatus.ACCEPTED) {
    buffer.release();
}
```

All ConnectionHandler callbacks execute on the Channel EventLoop. Several connections may concurrently use one handler; never block the EventLoop. Inbound ReferenceCounted messages are released after onMessage returns or throws. Retain before echoing the same ByteBuf or holding it asynchronously, then eventually release the independent share. Releasing ordinary objects is a no-op. onEvent does not auto-release.

Ordinary pipeline/business errors preserve the Throwable and enter onException without automatic closure. System.Logger handles failure of onException itself. Late errors after disconnection receive diagnostics only; failure inside onDisconnected still enters onException before lifecycle termination.

<a id="21-引用计数的逐步解释"></a>

### 2.1 Reference counting step by step

Assume onMessage receives a ByteBuf with one framework-owned borrowed share. Before echoing it, the application retains a send share. ACCEPTED transfers the send share to Netty. Returning from onMessage releases the inbound share; Netty later handles the send share on completion/failure.

For INACTIVE/NOT_WRITABLE, immediately release the extra send share. The framework still releases the original borrowed share at callback end. Do not release the borrowed share yourself and then let the adapter release it again.

For asynchronous work, retain before leaving callback scope, release the new share if submission fails, and transfer release responsibility to the final consumer when accepted. These are usage rules: Network creates no asynchronous business queue and cannot know whether higher-level work was accepted.

<a id="22-属性与回调状态"></a>

### 2.2 Attributes and callback state

Use Netty AttributeKey<T> semantics directly. Different business modules must not interpret a same-name key as different types. set/remove changes the slot without releasing its object or making it thread-safe. Closure does not clear attributes; business cleanup may run in onDisconnected.

Callbacks for one Connection run serially on its EventLoop, but one ConnectionHandler instance may be called from multiple EventLoops. Undifferentiated mutable fields such as current player or last message cause cross-connection interference. Store connection-specific state explicitly in attributes or higher-level mappings.

## 3. Server

```java
public final class NetworkServer implements AutoCloseable {
    public static NetworkServerBuilder builder();
    public void start();
    public SocketAddress localAddress();
    public void close();
}
```

start synchronously awaits Netty bind. Repeated start or start after close throws IllegalStateException. Bind failure throws NetworkException, closes the instance, and reclaims owned resources. localAddress is null before start; port 0 exposes its allocated port after successful startup.

Complete builder configuration:

| Method | Default / meaning |
| --- | --- |
| bindAddress(SocketAddress) | Required |
| handler(ConnectionHandler) | Required |
| pipeline(Consumer&lt;ChannelPipeline&gt;) | Append repeatedly |
| webSocket(String path) | Enable WS; maximum message 1 MiB |
| webSocket(String path, int maxMessageSize) | Positive maximum message size |
| sslContext(SslContext) | Server context required; enables TLS TCP / WSS |
| bossGroup(EventLoopGroup) | Create NioEventLoopGroup(1) by default |
| workerGroup(EventLoopGroup) | Create standard NioEventLoopGroup() by default |
| channelFactory(ChannelFactory&lt;? extends ServerChannel&gt;) | NioServerSocketChannel::new |
| option(ChannelOption&lt;T&gt;, T) | ServerBootstrap parent option |
| childOption(ChannelOption&lt;T&gt;, T) | ServerBootstrap child option |
| build() | Validate/snapshot; no listen or default-group startup |

The WS path is one path beginning with /; reject authority, scheme, query, fragment, and wildcards. No automatic normalization. Request query remains available to HTTP handshake handlers but is excluded from path matching. A different path closes with HTTP 404.

## 4. Client

```java
public final class NetworkClient implements AutoCloseable {
    public static NetworkClientBuilder builder();
    public Connection connect(SocketAddress remoteAddress);
    public void connectAsync(SocketAddress remoteAddress, ConnectCallback callback);
    public Connection connect(URI uri);
    public Connection connect(URI uri, WebSocketConnectOptions options);
    public void connectAsync(URI uri, ConnectCallback callback);
    public void connectAsync(URI uri, WebSocketConnectOptions options, ConnectCallback callback);
    public void close();
}

public interface ConnectCallback {
    void onSuccess(Connection connection);
    void onFailure(Throwable cause);
}
```

A Client can connect to several targets or repeatedly to one target. Sync/async share ConnectAttempt and a single CAS success/failure arbitration. Claim success before onConnected; deliver the final success callback afterward. Callback exceptions only log, producing neither a second result nor ConnectionHandler.onException.

Synchronous connect from any EventLoop of that Client immediately throws IllegalStateException. Interrupted waits restore the interrupt flag, cancel the attempt, close the Channel, and throw NetworkException. Even if success just won, close the connection the synchronous caller cannot receive.

Valid asynchronous attempt callbacks run on the selected Netty EventLoop. Pre-attempt rejection, including invalid input or closed Client, invokes onFailure directly on the caller thread. If a terminated external EventLoop rejects notification, notify once on the current thread as a fallback. A null callback directly throws NullPointerException.

| Client Builder method | Default / meaning |
| --- | --- |
| handler(ConnectionHandler) | Required |
| pipeline(Consumer&lt;ChannelPipeline&gt;) | Append in registration order |
| webSocket() / webSocket(int maxMessageSize) | WS mode; default 1 MiB |
| sslContext(SslContext) | Client context required |
| eventLoopGroup(EventLoopGroup) | Create standard NioEventLoopGroup() by default |
| channelFactory(ChannelFactory&lt;? extends Channel&gt;) | NioSocketChannel::new |
| option(ChannelOption&lt;T&gt;, T) | Native Bootstrap option |
| build() | Snapshot and create an independent Client |

TCP accepts only SocketAddress and WS only URI; mode mismatch throws IllegalStateException. URI requires a valid host and ws/wss scheme, with default ports 80/443. Reject user-info, fragment, port 0, and out-of-range ports.

ws:// does not enable TLS even with a configured SslContext. wss:// uses the custom context or a lazily created default JDK client context. Default trust comes from the JVM and TLS enables hostname verification. A configured context enables TLS TCP, requiring InetSocketAddress for the hostname. Server does not generate certificates/contexts.

<a id="41-connectattempt-的完成协议"></a>

### 4.1 ConnectAttempt completion protocol

Internal one-shot ConnectAttempt holds the selected EventLoop, completion CAS, Channel, successful Connection/failure cause, synchronous latch, and optional callback. Shared sync/async coordination avoids divergent semantics.

```text
Create attempt and add to pending
→ create/attach Channel
→ transport completes with application pipeline assembled
→ claimSuccess CAS
→ create Connection and invoke onConnected
→ store success, remove from pending, wake waiter/invoke onSuccess
```

A winning failure CAS stores the cause, marks cancellation, closes the attached Channel, removes pending, and notifies the result. If cancellation precedes Channel attachment, attach checks cancellation and closes it, preventing resource escape.

Claiming success deliberately precedes onConnected. Otherwise Client.close could report failure after business code had received the Connection. Once claimed, onConnected exceptions go only to ConnectionHandler.onException, never ConnectCallback.onFailure.

<a id="42-中断和回调线程例外"></a>

### 4.2 Interruption and callback thread exceptions

An interrupted synchronous await closes the attached Channel even if success was just claimed: the caller exits exceptionally and cannot take the success value. Restore the interrupt flag and throw NetworkException without manufacturing a second asynchronous result.

Normal asynchronous results use the selected EventLoop; pre-attempt validation failure calls onFailure on the caller thread. A terminated borrowed EventLoop that rejects notification requires one current-thread fallback. Higher layers therefore cannot assume onFailure always has a Channel EventLoop context or unconditionally call synchronous connect/close inside callbacks.

ConnectCallback failures receive diagnostics only, not ConnectionHandler.onException: the former handles an attempt's result; the latter belongs to an established connection lifecycle.

<a id="5-配置快照与握手参数"></a>

## 5. Configuration snapshots and handshake parameters

Builders permit repeated build. Each instance snapshots handler, pipeline list, options, and transport settings. Without external groups, instances create separate resources; supplied groups are explicitly shared. Snapshots do not deeply copy handler, SslContext, or lambda-captured state. Builders are not concurrent configurators.

Null handler/pipeline/group/factory/option/value arguments are rejected immediately. Missing required configuration at build throws IllegalStateException; invalid paths, nonpositive sizes, and wrong SSL context modes throw IllegalArgumentException.

```java
public final class WebSocketConnectOptions {
    public WebSocketConnectOptions(HttpHeaders headers, String subprotocol);
    public static WebSocketConnectOptions headers(HttpHeaders headers);
    public static WebSocketConnectOptions of(HttpHeaders headers, String subprotocol);
    public static WebSocketConnectOptions defaults();
    public HttpHeaders headers();
    public String subprotocol();
}
```

Copy HttpHeaders at construction and return a fresh copy from headers(). Defaults are empty headers and null subprotocol; options can safely serve several connects. Server does not negotiate subprotocols by default; a client requesting one fails if the server omits it. Advanced servers may configure native Netty WebSocket handlers through the pipeline; no Auth/Router/HTTP API is added.

<a id="51-快照的深度和可复用范围"></a>

### 5.1 Snapshot depth and reuse

Changing builder options or appending pipelines after build affects only later builds, never existing Server/Client configuration. Handler, SslContext, and captured objects remain shared references; snapshot does not copy their mutable internals.

Each Channel invokes configurers to create its codecs. Create non-Sharable decoders inside the configurer instead of adding one external instance to every connection. Applications verify @Sharable and concurrency safety when sharing handlers.

WebSocketConnectOptions separately copies headers. Changing either original headers or a headers() result does not change the options. This enables independent attempts to reuse them, without implying that Server interprets or authenticates the headers.

<a id="6-pipeline-与超时"></a>

## 6. Pipeline and timeouts

```text
TCP: [SslHandler / TLS handshake observer] → ConnectionLifecycle → user pipeline → ConnectionHandler adapter
WS:  [SslHandler / TLS handshake observer] → HTTP codec / HTTP aggregator / WS protocol / frame aggregator
     → WS handshake observer → ConnectionLifecycle → binary decoder/encoder → user pipeline → ConnectionHandler adapter
```

Run configurers anew per Channel, in registration order for pipeline(a).pipeline(b). Create separate non-Sharable codecs. Outbound processing traverses user encoders in reverse, then wraps ByteBuf as BinaryWebSocketFrame. Inbound aggregation retains content while the Netty decoder releases the original frame.

Protocol-specific observers consume TLS/WS events. ConnectionLifecycle precedes user handlers and observes actual inactivity; the business message/error adapter is last. TCP includes no business framing. IdleStateHandler events reach onEvent using native types. Custom asynchronous handlers maintain propagation and ordering; do not delete/reorder internal `managame-*` handlers or fabricate lifecycle/handshake events.

Internal names support native insertion, such as HTTP Upgrade validation after `managame-http-aggregate` and TLS configuration on `managame-tls`. Use sslContext(...) for TLS to participate in establishment semantics; manually inserting SslHandler is not auto-detected.

| Parameter | Current default and configuration |
| --- | --- |
| TCP connect | Netty CONNECT_TIMEOUT_MILLIS, 30 seconds; option |
| TLS handshake | SslHandler default 10 seconds; native pipeline handler |
| WS client handshake | WebSocketClientProtocolHandler default 10 seconds |
| WS server establishment | At most 10 seconds from Channel active, including silent/no-Upgrade peers; EventLoop timer canceled on success/closure |
| HTTP Upgrade body | HttpObjectAggregator 64 KiB, independent of business messages |
| Binary WebSocket message | 1 MiB for frame payload and aggregate; builder-adjustable |
| NIO / socket options | Netty defaults; native option / childOption |

Reject Text with close code 1003. Oversized frames/aggregates and WS violations close without onMessage. Netty protocol handlers process Ping/Pong/Close. Server pipeline initialization failure diagnoses and closes only that Channel; Client reports attempt failure.

<a id="61-装配时点与事件方向"></a>

### 6.1 Assembly timing and event direction

NetworkChannelInitializer adds transport handlers, ConnectionLifecycle, payload adapters, user configurers, and finally ConnectionHandlerAdapter during Channel initialization. Only after assembly succeeds does it mark initialization complete. Connection creation requires that marker, channel activation, every registered handshake prerequisite, and the owner's success claim. Configurers can configure assembled native handlers before handshake completion; assembly and connection delivery remain distinct.

Inbound events generally run head to tail; writes tail to head. Main internal order (brackets optional):

```text
managame-write-errors
→ [managame-tls → managame-tls-handshake]
→ [managame-http → managame-http-aggregate
   → server managame-websocket-path → managame-websocket
   → managame-websocket-aggregate → managame-websocket-handshake]
→ managame-transport
→ [managame-binary-in → managame-binary-out]
→ user codecs/event handler
→ managame-connection
```

For WS, user encoders create ByteBuf, binary-out wraps BinaryWebSocketFrame, then WS/TLS encodes it. TCP adds neither binary adaptation nor a business length header.

Native addBefore/addAfter insertion must preserve handshake, lifecycle, and release logic. Swallowing handshake/inactive events or deleting internal handlers violates prerequisites and is not supported arbitrary pipeline rewriting.

<a id="62-为什么区分生命周期-gate-与末端-adapter"></a>

<a id="62-why-lifecycle-gate-and-terminal-adapter-are-separate"></a>

<a id="62-内部职责与扩展边界"></a>

### 6.2 Internal responsibilities and extension boundaries

NetworkPipeline and TransportGate have been removed. Assembly order and lifecycle coordination remain necessary, but neither component interprets concrete protocol events or infers endpoint roles from nullable arguments.

| Internal component | Responsibility |
| --- | --- |
| NetworkServer / NetworkClient | Select explicit server/client transport factories; own listening, pending attempts, resource closure, and admission |
| [NetworkChannelInitializer](../../game-network/src/main/java/cn/managame/network/netty/NetworkChannelInitializer.java) | Assemble shared stages in order; mark initialization complete only after every configurer succeeds |
| [ChannelTransport](../../game-network/src/main/java/cn/managame/network/netty/ChannelTransport.java) | Internal protocol/payload assembly boundary: `addProtocolHandlers` adds protocol handlers and registers handshake prerequisites; `addPayloadHandlers` adds payload adapters after the lifecycle handler. Plain TCP requires no extra handshake |
| [TlsTransport](../../game-network/src/main/java/cn/managame/network/netty/TlsTransport.java) / [WebSocketTransport](../../game-network/src/main/java/cn/managame/network/netty/WebSocketTransport.java) | Own protocol handlers, handshake event translation, protocol-specific deadlines/rejection, and payload adaptation; TLS wraps TCP or WS |
| [ConnectionLifecycle](../../game-network/src/main/java/cn/managame/network/netty/ConnectionLifecycle.java) | Coordinate initialization, activation, handshake prerequisites, one terminal establishment outcome, actual disconnect, and application error forwarding |
| [ConnectionEstablishment](../../game-network/src/main/java/cn/managame/network/netty/ConnectionEstablishment.java) | Owner-specific success claim/result/cleanup; ConnectAttempt implements client completion, Server supplies admission and pending removal |
| ConnectionHandlerAdapter | Create NettyConnection, invoke application callbacks, release borrowed messages, and isolate callback errors |

ConnectionLifecycle has no TLS/HTTP/WS dependency and does not inspect ConnectAttempt. Its position before user handlers ensures a decoder cannot hide actual inactivity. The terminal adapter stays after user codecs to receive decoded business messages. Combining them would lose one of these event positions or mix application decoding with transport observation.

All prerequisites are registered during transport assembly, before initialization is marked complete. Each one-shot handshake token can complete only its own prerequisite; duplicate completion cannot satisfy another protocol. Creation requires initialization, activation, all prerequisites, and a successful owner claim. Failure/closure is terminal: late completion cannot revive a connection. Clear owner references before business delivery, retain the claimed owner locally until onConnected returns, then deliver success even if onConnected closed the connection.

Server snapshots its transport composition at build time; start only creates infrastructure and binds the listener. Its private AcceptedConnection owns pending membership and the temporary close listener. A short pending lock orders success claim/removal against stopping admission: a prior success claim leaves the cancellation set, while an earlier close prevents delivery. Neither callbacks nor channel closure execute under this lock. Terminal establishment removes the temporary listener, so successful channels do not retain server cleanup ownership. Client similarly adds/removes attempts under its pending lock, reports rejection outside it, and passes the completed attempt directly to its removal callback without a self-reference array; completion clears that callback reference.

For example, an additional internal stream handshake can add its own observer, register a prerequisite, and translate its protocol's completion/failure into that prerequisite or lifecycle failure. It need not add branches to ConnectionLifecycle. The observer owns its timeout and cancels it on completion/disconnect. TLS and WS follow this pattern; WS bounds silent server peers for 10 seconds from channelActive, separately from business heartbeat/read-idle policies.

This boundary is package-private, not a new public transport registry or a promise to support UDP/QUIC. Public pipeline(...) remains for native handler/codec customization; merely adding a handler does not register an establishment prerequisite. New transports still need defined message boundaries, ownership, rejection, configuration, and contract tests. No extra Maven artifacts or public internal-access bridges are introduced.

<a id="63-写入错误的路由"></a>

### 6.3 Write error routing

voidPromise avoids exposing/maintaining a completion Future per send. Its failures may originate at the pipeline head; traversing a WS protocol handler directly can trigger its default closure for ordinary outbound errors.

After establishment, managame-write-errors uses ConnectionLifecycle's directly held ChannelHandlerContext to redirect these errors into the application path, preserving user pipeline/onException handling without looking up a handler by name. It neither swallows errors nor disables invalid inbound WS-frame closure. Pre-establishment errors still fail the handshake.

A custom native handler may itself close Channel. Network's ordinary-error policy cannot undo that action; integrators must inspect their exceptionCaught implementations.

<a id="7-资源与关闭"></a>

## 7. Resources and closure

Server closes the listening Channel, cancels undelivered handshakes, then shuts down owned boss/worker groups. Client rejects new attempts, cancels pending, then shuts down its owned group. Successful connections are not a business collection; owned group shutdown naturally closes associated Channels, while applications separately close successful connections on external groups.

Callers always manage external groups and SslContext. No automatic Epoll/KQueue detection. Native transport requires matching group and channelFactory.

start/close are synchronous infrastructure operations prohibited on related boss/worker/client EventLoops to avoid self-wait deadlock. Repeated close is a no-op; concurrent repeated close is not a barrier proving another call finished cleanup. Use Netty shutdownGracefully(0, 5 seconds), attempting all owned cleanup. Preserve interruption and report NetworkException after cleanup.

Connection.close remains nonblocking and callable from any callback; it differs from synchronous Server/Client close.

<a id="71-默认资源与注入资源"></a>

### 7.1 Default and injected resources

| Assembly | Resource lifecycle | Application responsibility |
| --- | --- | --- |
| Default NIO groups throughout | Each Server/Client owns its resources | Close the entry point |
| External group | Borrowed; never shut down by framework | Close successful connections, then group when appropriate |
| Only boss or worker injected | Manage each by origin | Own injected part; framework reclaims created part |
| Native transport | Never auto-selected | Supply matching group, channelFactory, dependencies |

Server.build neither listens nor starts default groups; start establishes listening synchronously. Client has no extra start. Repeated build instances have independent snapshots; only explicitly injected objects are shared.

Run synchronous infrastructure operations outside related EventLoops. Connection.close is valid in onConnected/onMessage/onException. Do not put Server.close in its own onDisconnected callback: it may wait for the current EventLoop to exit.

<a id="72-关闭与成功交付的交叉"></a>

### 7.2 Closure intersecting successful delivery

Client.close cancels unfinished attempts but preserves already claimed success. Subsequent owned-group shutdown may invalidate that successful Connection; handle its normal lifecycle.

With external groups, Client closure does not traverse successful connections. Higher layers wanting batch closure must hold and explicitly close that batch. Internal pending exists only during establishment and is not a connection inventory.

<a id="8-失败与兼容性"></a>

## 8. Failure and compatibility

| Scenario | Representation |
| --- | --- |
| Invalid arguments, missing required configuration, lifecycle misuse | Java argument/state exceptions above; async entries call onFailure except null callback |
| bind/connect/TLS/WS/pipeline initialization failure | NetworkException preserving cause |
| Client close cancels pending | IllegalStateException |
| Established Decoder/I/O/Handler error | Original Throwable → onException |
| onException / ConnectCallback throws | Final System.Logger diagnostics |
| INACTIVE / NOT_WRITABLE | WriteStatus; caller keeps ownership |

Old ConnectionListener, ConnectionKey, CloseInfo, NettyConnector, NettyAcceptor, tryWrite, and TestKit paths are obsolete without compatibility layers. game-rpc now integrates through ConnectionHandler/ConnectCallback and provides real TCP integration tests.

<a id="81-后续实现不应重新引入的隐式行为"></a>

### 8.1 Implicit behavior that must not return

New codecs must preserve three-state write acceptance and ownership. New transports must define establishment thresholds, timeouts, message boundaries, and protocol rejection. Asynchronous integrations must not block an EventLoop waiting for itself; attribute wrappers must not silently change post-closure semantics.

Heartbeats, IdleStateHandler, custom HTTP Upgrade checks, and authentication use native Netty integration or higher-layer composition; there is no separate framework DSL. A unified capability first needs observable behavior and resource responsibilities, then changes to both specifications. Convenience alone does not justify default reconnection, automatic closure, or business send queues.

Runnable entry: [NetworkEchoExample](../../game-network/src/main/java/cn/managame/network/example/NetworkEchoExample.java) and its test below. Diagrams, reference-count explanations, and tables here explain design; they are not standalone runnable programs.

<a id="9-验证与边界"></a>

## 9. Validation and limits

- [NetworkContractTest](../../game-network/src/test/java/cn/managame/network/netty/NetworkContractTest.java): real TCP/TLS/WS/WSS, write order, lifecycle, backpressure, attributes, reference counting, business exceptions, snapshots, external resources.
- [WebSocketContractTest](../../game-network/src/test/java/cn/managame/network/netty/WebSocketContractTest.java): fragments, controls, Text/oversize rejection, exact paths, header snapshots, untrusted TLS certificates, validation.
- [ConnectRaceTest](../../game-network/src/test/java/cn/managame/network/netty/ConnectRaceTest.java): pending closure, interrupt restoration, concurrent completion, close inside onConnected, server closure during initialization, and separation of pending versus established connections on external groups.
- [ConnectionLifecycleTest](../../game-network/src/test/java/cn/managame/network/netty/ConnectionLifecycleTest.java): independent handshake prerequisites, initialization failure, late completion after failure/closure, owner cancellation, transport-independent write-error routing, and silent WS timeout/cancellation.
- [NetworkEchoExampleTest](../../game-network/src/test/java/cn/managame/network/example/NetworkEchoExampleTest.java): compile/run the complete example.

Run `mvn -pl game-network -am test` for module tests and `mvn clean verify` for the repository. Tests generate temporary certificates with the current JDK keytool, limit Netty default threads to 2, and force the Windows JDK Selector wakeup pipe to TCP using a test-only unusable unixdomain.tmpdir to avoid intermittent AF_UNIX connect failure. Production code does not change JVM properties.

Public-network/native-transport/production-capacity certification and cross-language interoperability remain unverified. game-rpc supplies real TCP integration tests; automatic RPC-to-Runtime integration remains unimplemented.

<a id="91-易错契约的测试定位"></a>

### 9.1 Tests for error-prone contracts

| Contract | Test class and method |
| --- | --- |
| Four transports and ordered writes | NetworkContractTest.roundTripAndOrderedWrites |
| Rejection ownership, backpressure, send failure | NetworkContractTest.ownershipBackpressureAndOutboundFailure |
| Handler exceptions, ordinary events, inbound retain | NetworkContractTest.handlerExceptionsEventsAndRetain |
| onConnected before success; callback failure cannot create a second result | NetworkContractTest.asyncSuccessRunsAfterConnectedAndCallbackFailureIsNotConnectFailure |
| Borrowed groups stay open; no blocking own EventLoop | NetworkContractTest.externalGroupsKeepEstablishedConnectionsAndRejectBlockingCalls |
| Snapshots and per-Channel pipeline order | NetworkContractTest.snapshotsAndPerChannelPipelineOrder |
| Ordinary outbound errors do not auto-close either TCP/WS peer | NetworkContractTest.outboundFailuresDoNotAutoCloseEitherPeer |
| Server closure cancels pending handshakes but preserves successful connections on external groups | ConnectRaceTest.serverCloseCancelsHandshakeButKeepsEstablishedExternalConnections |
| Server closure during initialization prevents late delivery | ConnectRaceTest.serverCloseDuringInitializationPreventsLateDelivery |
| Pending closure notifies once | ConnectRaceTest.closeCancelsPendingHandshakeExactlyOnceOnEventLoop |
| Interrupted wait reclaims resources and restores flag | ConnectRaceTest.interruptCancelsHandshakeAndRestoresFlag |
| Success/close race and close inside onConnected | ConnectRaceTest.successAndCloseRaceHasOneOutcomePerAttempt / onConnectedCloseStillReportsSuccessfulConnect |

These methods are regression entry points. Revisit relevant contracts when changing timing, ownership transfer, pipeline order, or CAS claim points. A working TCP echo alone does not prove all Network behavior.
