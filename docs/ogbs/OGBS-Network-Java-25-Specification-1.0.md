# OGBS Network Java 25 Development Specification 1.0

**[English](OGBS-Network-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Network-Java-25-Specification-1.0.zh-CN.md)

Document type: **Java Development Specification**. Standard: [OGBS Network Specification](OGBS-Network-1.0.md). Status: V1 Draft, current game-network baseline.

This document defines the public Java API, defaults, exceptions, threads, resource ownership and extension points. Implementations must satisfy both documents in behavior, not only in signatures. Code that violates the contract is fixed; design changes update both specifications.

Maven: `cn.managame:game-network:1.0.0-SNAPSHOT`, JDK 25 without preview. Netty is managed by the root BOM (currently 4.1.135.Final). No game-core dependency.

<a id="1-包结构"></a>

## 1. Package structure

The binding is a thin Netty facade: native pipeline handlers are the extension mechanism, and ConnectionHandler receives decoded messages and lifecycle events. Endpoints keep no connection registry.

| Package | Public API |
| --- | --- |
| cn.managame.network.connection | Connection, ConnectionHandler, WriteStatus |
| cn.managame.network.connector | ConnectCallback, WebSocketConnectOptions |
| cn.managame.network.error | NetworkException |
| cn.managame.network.netty | NetworkServer, NetworkServerBuilder, NetworkClient, NetworkClientBuilder |
| cn.managame.network.http | HttpServer, HttpServerBuilder, HttpResponseCallback |

Everything else (NettyConnection, NetworkChannelInitializer, WebSocketTransport, ConnectionHandlerAdapter, HttpServerTransport, payload adapters) is package-private. Attributes use Netty AttributeKey directly. Runnable examples live in [game-demo](../../game-demo/README.md) under cn.managame.demo.examples.network; contract tests stay in game-network. Module entry: [game-network](../../game-network/README.md).

<a id="native-http-server-api"></a>

### 1.1 Independent HTTP server

Implements [N-HTTP-01–08](OGBS-Network-1.0.md#http-server-profile). HttpServer owns its own ServerBootstrap, listener and pipeline; it does not wrap NetworkServer or use Connection/ConnectionHandler. Messages are native FullHttpRequest/FullHttpResponse. There is no HTTP client or HTTP/2 API.

| Entry | Signature or setting | Contract |
| --- | --- | --- |
| HttpServer | static HttpServerBuilder builder(); void start(); SocketAddress localAddress(); void close() | AutoCloseable; synchronous one-shot listener; localAddress is null before a bind attempt |
| Required | bindAddress(SocketAddress) | Listener address |
| Synchronous fallback | handler(Function<FullHttpRequest, FullHttpResponse>) | Returns one complete response for requests that reach the end of the pipeline; default empty 404 |
| Asynchronous fallback | asyncHandler(BiConsumer<FullHttpRequest, HttpResponseCallback>) | Completes later through the callback (§1.2); the last handler/asyncHandler call wins |
| Native pipeline | pipeline(Consumer<ChannelPipeline>) | Per-channel configurers, in registration order; addLast installs HTTP handlers after aggregation and before the fallback, addFirst installs caller-created TLS |
| Request limits | maxContentLength(int); maxInitialLineLength(int); maxHeaderSize(int) | Positive; defaults 1 MiB body, 4096-byte line, 8192-byte headers |
| Inbound timing | readTimeoutMillis(long) | Default 30,000 ms; 0 disables; negative rejected; closes after that long without inbound bytes |
| Business execution | executorGroup(EventExecutorGroup) | Optional, borrowed; every executor must be an OrderedEventExecutor |
| Transport | bossGroup, workerGroup, channelFactory, option, childOption | Transport chosen for the platform by default (see NetworkTransports), owned boss (1 loop) and owned worker (Netty default size); injected groups are borrowed; channelFactory defaults to the group's transport |
| Construction | HttpServer build() | Immutable snapshot; no default group is started before start() |

Errors: null arguments → NullPointerException; invalid limits or unordered executors → IllegalArgumentException; missing required fields, repeated start, start after close → IllegalStateException; bind failure → NetworkException with the cause, after cleanup. A pipeline-initialization failure closes only that accepted channel.

Pipeline: `[caller SslHandler] → http-read-timeout → http-codec → http-validation → http-keep-alive → http-aggregation → user handlers → http-application`. The `http-` names are reserved; extensions must not remove or reorder them.

Protocol handling:
- HTTP/1.1 and HTTP/1.0 are accepted. HTTP/1.1 requires exactly one Host. HTTP/1.0 may omit Host and is served as a single request: the response carries `Connection: close` and the connection closes after it, even if the request asked for keep-alive. Other versions → 505.
- More than one Host → 400. CONNECT → 405, Upgrade → 400, malformed line/headers → 400, body over the limit → 413, unsupported Expect → 417. These empty protocol-error responses close the connection.
- Responses are always written as HTTP/1.1. The server removes Transfer-Encoding/trailers from fallback responses and sets Content-Length from the actual body; HEAD/304 keep an explicit non-negative length without a body, 204 has no length/body, 205 has length 0.
- `Connection: close` on the request or response closes the connection after the write and suppresses later pipelined requests. A write failure closes without retry.
- A handler throw, a null or 1xx response, or a framing failure sends an empty 500 and logs the original exception at ERROR; no detail is sent. Recognized I/O, decoder-limit and read-timeout errors log a DEBUG summary; unknown pipeline errors stay ERROR.

Threads: by default everything runs on the channel EventLoop. With executorGroup, the HTTP base and fallback of a connection run on one ordered executor. Extensions after http-codec must then use the same group, e.g. `pipeline(p -> p.addLast(group, "auth", new AuthHandler()))`; initialization rejects a different executor there, and build rejects `childOption(SINGLE_EVENTEXECUTOR_PER_GROUP, false)`. TLS/raw handlers before http-codec may stay on the EventLoop. Create non-sharable handlers inside the configurer.

Ownership:
- Synchronous fallback: the request is borrowed until the function returns. To echo, return `new DefaultFullHttpResponse(HTTP_1_1, OK, request.content().retainedDuplicate())`. The returned response always transfers to the server.
- Native extensions (N-HTTP-08): a forwarding handler calls `ctx.fireChannelRead(request)` without releasing; a consuming handler releases the request itself. SimpleChannelInboundHandler auto-releases, so forwarding from it needs retain. A native responder writes one correctly framed FullHttpResponse with `ctx.writeAndFlush`; no fallback response follows. Native CorsHandler and HttpContentCompressor work in this pipeline.

Lifecycle: the read timeout measures inbound inactivity (including idle keep-alive and partial bodies); it is not a handler deadline, and disconnect does not interrupt a handler. start/close must not be called from the server's own boss/worker/HTTP executors. close marks the server terminal, closes the listener and shuts down only owned groups with `shutdownGracefully(0, 5 s)`. It never shuts down an injected executor and does not wait for business work. With a borrowed worker group, accepted connections continue until their own closure. Repeated close is a no-op.

Out of scope: HTTP/2, built-in routing, JSON, multipart, CORS/compression policy. Body limits do not bound executor queues, connection count or response buffering.

Sources: [HttpServer](../../game-network/src/main/java/cn/managame/network/http/HttpServer.java), [HttpServerBuilder](../../game-network/src/main/java/cn/managame/network/http/HttpServerBuilder.java), [HttpServerTransport](../../game-network/src/main/java/cn/managame/network/http/HttpServerTransport.java). Tests: [HttpServerTest](../../game-network/src/test/java/cn/managame/network/http/HttpServerTest.java), [HttpServerExampleTest](../../game-demo/src/test/java/cn/managame/demo/examples/network/HttpServerExampleTest.java).

<a id="http-async-response"></a>

### 1.2 Asynchronous response callbacks

`asyncHandler(BiConsumer<FullHttpRequest, HttpResponseCallback>)` creates one callback per request that reaches the fallback. The handler may complete it inline or hand it to another task (for example a Runtime route); Network does not depend on Runtime.

```java
boolean onResponse(FullHttpResponse response);
boolean onFail(Throwable cause);
```

- Both methods are thread-safe. The first call returns true and claims the completion, even if the channel is already closed; true does not confirm delivery. Later calls return false.
- Every non-null onResponse transfers one reference; duplicate and undeliverable responses are released. null throws NullPointerException without claiming.
- onFail sends an empty 500 and closes if still possible, logging the cause. A handler throw is treated as onFail. An invalid winning response (1xx, bad HEAD/304 length) is released and becomes 500/close.
- The request is borrowed only until the handler returns. Decode what you need first, or retain/copy buffers and release them yourself.
- Never completing the callback leaves the connection waiting. Network adds no business timeout, cancellation or retry; disconnect does not claim the callback.

Completion runs on the connection's ordered HTTP executor (inline when already there). Executor rejection releases the response and closes the connection. Until the final LastHttpContent of the current response is written, later pipelined input is held before validation/aggregation, so an automatic 100/400/413/417 cannot overtake the pending response; AUTO_READ is paused after the current body and restored only if the framework paused it. Held input is released on close.

The read timeout keeps running while a response is pending. A 30-second idle close can therefore happen before a long task finishes; its later onResponse just releases the response. Disable or raise the timeout for long operations.

Example: [HttpAsyncServerExample](../../game-demo/src/main/java/cn/managame/demo/examples/network/HttpAsyncServerExample.java) and [its test](../../game-demo/src/test/java/cn/managame/demo/examples/network/HttpAsyncServerExampleTest.java).

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

Connection delegates state and addresses to the Channel (addresses may be null). `write` rejects null, checks active then writable, and calls `writeAndFlush(message, voidPromise())`, so every write flushes; the interface offers no deferred flush, and an application that wants several writes to share one syscall arranges that in the pipeline (for example Netty's FlushConsolidationHandler). Only INACTIVE/NOT_WRITABLE guarantee that the caller still owns the message. Write failures are routed to onException (§6.3); there is no per-write Future.

```java
ByteBuf buffer = ...;
if (connection.write(buffer) != WriteStatus.ACCEPTED) buffer.release();
```

All callbacks for one connection run serially on its EventLoop; one handler instance may serve many EventLoops, so keep per-connection state in attributes. Never block the EventLoop. Errors go to onException without automatic closure; a failure inside onException is only logged.

<a id="21-引用计数的逐步解释"></a>
<a id="22-属性与回调状态"></a>

### 2.1 Reference counting and attributes

Inbound ReferenceCounted messages are released after onMessage returns or throws. To echo or keep a buffer, retain it first; after ACCEPTED Netty owns that share, otherwise release it. For asynchronous work, retain before leaving the callback and let the final consumer release it. onEvent does not auto-release.

Attributes follow Netty semantics: `remove` is `getAndSet(null)`, `set(key, null)` is allowed, values are neither released nor cleared on close. Clean up in onDisconnected if needed.

## 3. Server

```java
public final class NetworkServer implements AutoCloseable {
    public static NetworkServerBuilder builder();
    public void start();
    public SocketAddress localAddress();
    public void close();
}
```

start binds synchronously. Repeated start or start after close → IllegalStateException; bind failure → NetworkException and the instance is closed. localAddress is null before start; port 0 reports the bound port.

| Builder method | Default / meaning |
| --- | --- |
| bindAddress(SocketAddress) | Required |
| handler(ConnectionHandler) | Required |
| pipeline(Consumer&lt;ChannelPipeline&gt;) | Appended per call |
| webSocket(String path) / webSocket(String path, int maxMessageSize) | Enable WS; default maximum message 1 MiB |
| bossGroup / workerGroup | Owned `NetworkTransports.newEventLoopGroup(1)` / `newEventLoopGroup(0)` by default (Epoll when available, otherwise NIO) |
| channelFactory | Matches the boss group's transport by default (EpollServerSocketChannel or NioServerSocketChannel) |
| option / childOption | ServerBootstrap options |
| build() | Validate and snapshot; does not listen |

The WS path is a single path starting with `/`, with no scheme, authority, query, fragment or wildcard. A request query is ignored for matching; another path is rejected with 404.

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

| Builder method | Default / meaning |
| --- | --- |
| handler(ConnectionHandler) | Required |
| pipeline(Consumer&lt;ChannelPipeline&gt;) | Appended in order |
| webSocket() / webSocket(int maxMessageSize) | WS mode; default 1 MiB |
| eventLoopGroup | Owned `NetworkTransports.newEventLoopGroup(0)` by default |
| channelFactory | Matches the group's transport by default (EpollSocketChannel or NioSocketChannel) |
| option | Bootstrap option |
| build() | Snapshot; each build is an independent client |

One client can connect to many targets. TCP accepts only SocketAddress and WS only URI (mismatch → IllegalStateException). A WS URI needs a host and ws/wss scheme (default ports 80/443); user-info, fragment, port 0 or out-of-range ports are rejected.

TLS is configured only by adding a native SslHandler, which must be the single first handler. wss:// requires it and ws:// rejects it; TCP uses TLS when it is present. The caller configures trust, hostname verification and timeouts — see §6.4.

<a id="41-connectattempt-的完成协议"></a>
<a id="41-connectattempt-completion-protocol"></a>
<a id="42-中断和回调线程例外"></a>

### 4.1 Connection result

Each call uses one native `Promise<Connection>` (on ImmediateEventExecutor, so it still notifies after a borrowed loop terminates). Bootstrap runs on the selected EventLoop. When the transport and all handshakes are ready, the adapter checks the client is open, claims the result with `setUncancellable()`, creates the Connection, calls onConnected, and then completes success. Failure uses tryFailure; a listener closes the channel on failure or cancellation, including a channel created after cancellation.

- onConnected always runs before onSuccess / the synchronous return. Exceptions in onConnected go to onException; closing inside it still reports success.
- ConnectCallback runs on the selected EventLoop. Rejections before an attempt starts (bad input, closed client) call onFailure on the caller thread; if a terminated borrowed loop rejects the notification, it runs on the current thread. A null callback throws NullPointerException. Callback exceptions are only logged.
- Synchronous connect from one of the client's own EventLoops → IllegalStateException. An interrupted wait cancels the attempt, restores the interrupt flag and throws NetworkException; if success was already claimed, the undeliverable Connection is closed after onConnected returns.

<a id="5-配置快照与握手参数"></a>

## 5. Configuration snapshots and handshake parameters

Each build snapshots handler, configurers, options and transport settings; later builder changes affect only later builds. Handlers and captured objects are shared, not copied. Builders are not thread-safe. Null arguments are rejected immediately; missing required settings → IllegalStateException; invalid paths and non-positive sizes → IllegalArgumentException.

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

Headers are copied on construction and on `headers()`; one options object can serve many connects. The client fails when a requested subprotocol is not echoed; the server does not negotiate subprotocols. WS handshake timeouts are Netty's native defaults (currently 10 s); TLS timeouts belong to the SslHandler.

<a id="51-快照的深度和可复用范围"></a>
<a id="6-pipeline-与超时"></a>
<a id="61-装配时点与事件方向"></a>

## 6. Pipeline and timeouts

```text
[caller SslHandler]
→ network-write-errors
→ [network-http → network-http-aggregate
   → server network-websocket-path → network-websocket
   → network-websocket-aggregate → network-websocket-handshake]
→ [network-binary-in → network-binary-out]
→ user codecs/handlers
→ network-connection
```

Configurers run per channel in registration order. `network-` names are reserved: do not remove or reorder them, and do not swallow handshake or inactive events. For WS, user encoders produce ByteBuf and binary-out wraps it in BinaryWebSocketFrame; TCP adds no framing. Text frames close with 1003; oversized frames/messages and protocol violations close without onMessage. Ping/Pong/Close are handled by Netty.

| Parameter | Default and configuration |
| --- | --- |
| TCP connect | CONNECT_TIMEOUT_MILLIS 30 s; option |
| TLS handshake | SslHandler default 10 s |
| WS client handshake | Netty default, currently 10 s, from channelActive (includes TLS) |
| WS server establishment | Same native deadline, from channelActive through TLS and Upgrade |
| HTTP Upgrade body | 64 KiB |
| WS binary message | 1 MiB; builder |

Timers run on the EventLoop and are not exact wall-clock bounds when the loop is blocked.

<a id="62-为什么区分生命周期-gate-与末端-adapter"></a>
<a id="62-why-lifecycle-gate-and-terminal-adapter-are-separate"></a>
<a id="62-内部职责与扩展边界"></a>

### 6.2 Delivery and disconnect

The terminal adapter delivers a connection when: assembly finished, channelActive reached it, the SslHandler handshake succeeded (if present), the WS handshake succeeded (if WS), and the endpoint is still open. It then creates the Connection and calls onConnected in the same event, so a first message right after the handshake is always delivered after onConnected. TLS/WS completion events are consumed and do not reach onEvent. A pre-delivery close or failure produces one client failure (or server diagnostics) and no business callbacks.

After delivery, channelInactive calls onDisconnected once. Messages decoded at EOF may reach onMessage while `isActive()` is false. Ordered offloaded handlers must forward reads before inactive.

Only a native SslHandler added at initialization is recognized as a handshake prerequisite. Dynamic TLS insertion, STARTTLS, SNI handlers that install SslHandler later, and nested TLS are not supported.

<a id="63-写入错误的路由"></a>

### 6.3 Write error routing

After delivery, `network-write-errors` forwards outbound errors past the WS protocol handler to user handlers and onException, instead of letting the WS handler close the connection. Errors before delivery fail the handshake. A custom handler that closes the channel itself is outside this policy.

<a id="native-tls-configuration"></a>

### 6.4 Native TLS configuration

Add one fresh SslHandler per channel, first in the pipeline. Share one SslContext.

```java
NetworkServer server = NetworkServer.builder()
        .bindAddress(new InetSocketAddress(8443))
        .pipeline(p -> p.addFirst("ssl", serverContext.newHandler(p.channel().alloc())))
        .handler(handler)
        .build();

String host = "localhost";
int port = 8443;
NetworkClient client = NetworkClient.builder()
        .pipeline(p -> {
            SslHandler ssl = clientContext.newHandler(p.channel().alloc(), host, port);
            var parameters = ssl.engine().getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.engine().setSSLParameters(parameters);
            ssl.setHandshakeTimeoutMillis(10_000);
            p.addFirst("ssl", ssl);
        })
        .handler(handler)
        .build();
```

Passing a host to newHandler does not enable hostname verification; set it explicitly as above. For WSS add `webSocket("/game")` / `webSocket()` and connect to `wss://localhost:8443/game`. A missing handler for wss://, TLS with ws://, several SslHandlers or a misplaced one is a configuration error: the client reports NetworkException (cause IllegalArgumentException), the server closes that channel. A failed or timed-out handshake creates no Connection.

<a id="7-资源与关闭"></a>
<a id="71-默认资源与注入资源"></a>
<a id="72-关闭与成功交付的交叉"></a>

## 7. Resources and closure

| Assembly | Lifecycle |
| --- | --- |
| Default groups | Owned; close() shuts them down with `shutdownGracefully(0, 5 s)`, which closes their channels |
| Injected group | Borrowed; never shut down; existing channels stay open after close() |
| Native transport | `NetworkTransports` selects Epoll on Linux when the netty-transport-native-epoll library loads, otherwise NIO; game-network carries the linux-x86_64 and linux-aarch_64 native libraries as runtime dependencies. An injected NIO group keeps NIO channels even where Epoll is available; `NetworkTransports.socketChannelFactory/serverChannelFactory(group)` return the matching type |

close marks the endpoint closed; it never iterates connections or attempts. Readiness that observes a closed endpoint is rejected (the client reports IllegalStateException). With borrowed groups, unfinished handshakes end through their own result or timeout — do not disable native timeouts and rely on close to cancel. start/close must not run on the endpoint's own EventLoops (do not call Server.close from onDisconnected). Repeated close is a no-op. Connection.close is non-blocking and allowed in any callback.

<a id="8-失败与兼容性"></a>
<a id="81-后续实现不应重新引入的隐式行为"></a>

## 8. Failure and logging

| Scenario | Representation |
| --- | --- |
| Invalid argument, missing configuration, lifecycle misuse | Java argument/state exceptions; async entries call onFailure (except null callback) |
| bind/connect/TLS/WS/pipeline initialization failure | NetworkException with cause |
| Attempt observes a closed client | IllegalStateException |
| Established decoder/I/O/handler error | Original Throwable → onException |
| onException / ConnectCallback throws | Logged |
| INACTIVE / NOT_WRITABLE | WriteStatus; caller keeps ownership |

There is no reconnection, automatic closure policy or send queue; heartbeats, idle detection and authentication use native handlers or higher layers.

Logging uses the System.Logger `cn.managame.network` for server establishment failures and transport errors after the lifecycle ended:

| Type (after unwrapping up to 8 DecoderException layers) | Level |
| --- | --- |
| ClosedChannelException, SocketException, PrematureChannelClosureException | DEBUG disconnect summary |
| SSLException, WebSocketHandshakeException, CorruptedFrameException, TooLongFrameException | DEBUG protocol-rejection summary |
| Anything else | ERROR with stack |

DEBUG summaries contain only context, category, remote address and exception type. Source: [NetworkSupport](../../game-network/src/main/java/cn/managame/network/netty/NetworkSupport.java).

<a id="9-验证与边界"></a>
<a id="91-易错契约的测试定位"></a>

## 9. Validation and limits

| Test | Covers |
| --- | --- |
| [NetworkContractTest](../../game-network/src/test/java/cn/managame/network/netty/NetworkContractTest.java) | TCP/TLS/WS/WSS round trips, write order, first messages inside onConnected, ownership, backpressure, exceptions, snapshots, borrowed groups |
| [WebSocketContractTest](../../game-network/src/test/java/cn/managame/network/netty/WebSocketContractTest.java) | Fragments, control frames, Text/oversize rejection, paths, header snapshots |
| [ConnectRaceTest](../../game-network/src/test/java/cn/managame/network/netty/ConnectRaceTest.java) | Result races, interruption, close during connect, borrowed-group close |
| [ConnectionSetupTest](../../game-network/src/test/java/cn/managame/network/netty/ConnectionSetupTest.java) | Handshake/first-message ordering, write-error routing, establishment deadlines |
| [NativeTlsTest](../../game-network/src/test/java/cn/managame/network/netty/NativeTlsTest.java) | TLS placement, trust/hostname failures, handshake timeout, plaintext rejection |
| [DisconnectOrderingTest](../../game-network/src/test/java/cn/managame/network/netty/DisconnectOrderingTest.java) | EOF delivery before disconnect, release on callback failure, ordered offloading |
| [EstablishmentLoggingTest](../../game-network/src/test/java/cn/managame/network/netty/EstablishmentLoggingTest.java) | Log classification |
| [HttpServerTest](../../game-network/src/test/java/cn/managame/network/http/HttpServerTest.java) | HTTP/1.1 and 1.0, pipelining, framing, ownership, async completion, timeouts, lifecycle |
| [NetworkEchoExampleTest](../../game-demo/src/test/java/cn/managame/demo/examples/network/NetworkEchoExampleTest.java) | Runnable example |

Run `mvn -pl game-network -am test`, or `mvn clean verify` for the repository. Tests generate temporary certificates with keytool and limit Netty threads.

<a id="92-突发连接与资源规模"></a>

### 9.2 Known limits

- Each client built without eventLoopGroup creates its own group (2 × CPU loops by default). Reuse a client or share a group instead of building many clients.
- There is no connection admission limit, total connect deadline or per-attempt cancel handle; burst control belongs to the application. CONNECT_TIMEOUT_MILLIS covers only the socket connect.
- WS URIs are resolved with the JDK blocking resolver on the selected EventLoop; slow DNS can stall that loop.
- Keep onConnected and other callbacks short; they delay other channels on the same loop.
- Production capacity, native transports and cross-language interoperability are not verified.

<a id="10-stats"></a>

## 10. Stats

Implements [Network §10](OGBS-Network-1.0.md#10-observability). `NetworkServer.stats()` and `NetworkClient.stats()` return `cn.managame.network.connection.NetworkStats`, a record of ten `long` components in the order `liveConnections, accepted, established, closed, establishmentFailures, messagesRead, writesAccepted, writesInactive, writesNotWritable, handlerExceptions`. A client's `accepted` is always 0.

Counts live in one package-private holder per listener or client, shared with every connection it owns: `LongAdder` for the monotonic counts, because they are incremented from every EventLoop, and a single `AtomicLong` for `liveConnections`, which also decreases. A snapshot sums the adders and reads the live count, so it is cheap and lock-free; it is not a barrier, and `established`, `closed` and `liveConnections` can be observed one apart while a connection is being handed over.

The increment points are single and exact, which is what makes the counts comparable: accept in the listener's channel initializer, establishment failure once per channel behind the same guard that suppresses duplicate diagnostics, established where the Connection is created and handed to `onConnected`, closed where `onDisconnected` is invoked for an established connection, one read per delivery to `onMessage`, one per `Connection.write` outcome by returned `WriteStatus`, and one per throwable that reaches `onException` or the outside-lifecycle log. A refused write is counted before the caller sees the status, so no refusal is invisible.

game-network reports a record and nothing more: it does not depend on game-core and so does not know the metrics contract. Adapting a snapshot to a registry is [Spring §5](OGBS-Spring-Java-25-Specification-1.0.md#5-observability); an application that collects differently calls `stats()` itself. No reading is configurable and none can be turned off; the cost when nobody collects is the adders already incremented on the paths above. HTTP listener readings are not reported in V1.

Validation: [NetworkStatsTest](../../game-network/src/test/java/cn/managame/network/netty/NetworkStatsTest.java) covers accept, establishment, live count, a delivered message, an accepted and an inactive write across a server/client pair, and a channel that fails establishment in pipeline assembly.
