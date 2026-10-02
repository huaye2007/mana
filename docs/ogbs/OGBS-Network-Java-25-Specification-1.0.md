# OGBS Network Java 25 Development Specification 1.0

**[English](OGBS-Network-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Network-Java-25-Specification-1.0.zh-CN.md)

Document type: **Java Development Specification**. Standard: [OGBS Network Specification](OGBS-Network-1.0.md).

This document defines public Java APIs, defaults, exceptions, threads/resources, extension integration, and validation. Implementations must satisfy both documents, including behavior rather than signatures alone. Fix code that violates the contract; design changes update both specifications. Explicitly pending/unverified capabilities are not completed features.

Status: V1 Draft, current game-network implementation baseline. Shared semantics: [Network Specification](OGBS-Network-1.0.md).

Maven: `cn.managame:game-network:1.0.0-SNAPSHOT`. JDK 25 without preview. Root POM BOM manages Netty, currently 4.1.135.Final. Interfaces and Netty implementation share one artifact, without a game-core dependency.

<a id="1-包结构"></a>

## 1. Package structure

This binding is a thin Netty facade. Native pipeline handlers remain the extension mechanism; ConnectionHandler receives decoded messages and lifecycle events. Do not add endpoint-owned connection registries or batch connection management to support shutdown. The closure contract below makes this ownership boundary explicit.

| Package | Public API |
| --- | --- |
| cn.managame.network.connection | Connection, ConnectionHandler, WriteStatus |
| cn.managame.network.connector | ConnectCallback, WebSocketConnectOptions |
| cn.managame.network.error | NetworkException |
| cn.managame.network.netty | NetworkServer, NetworkServerBuilder, NetworkClient, NetworkClientBuilder |
| cn.managame.network.http | HttpServer, HttpServerBuilder, HttpResponseCallback |

NettyConnection, NetworkChannelInitializer, WebSocketTransport, ConnectionHandlerAdapter, and WS payload adapters remain package-private in netty. Server/Client entries share their implementation package to avoid exposing internal collaboration types merely for package separation.

Internal HTTP stays in the game-network artifact but uses the independent cn.managame.network.http package. HttpServer owns its ServerBootstrap, listener, resource lifecycle and request pipeline; it does not wrap NetworkServer or use Connection/ConnectionHandler. HttpServerTransport remains package-private. TCP/WS classes and their Upgrade codecs are unchanged. Business handlers belong to applications and the runnable HTTP example belongs to cn.managame.example.network in game-example. No new Maven module or dependency is required.

There is no attribute package or custom ConnectionKey: use Netty AttributeKey. Concrete NetworkServer/NetworkClient replace Acceptor/Connector. Module entry: [game-network](../../game-network/README.md). Runnable examples and their execution tests live in [game-example](../../game-example/README.md), under cn.managame.example.network, outside the game-network artifact. Example users must update their imports and module dependency; no old-package alias is retained. Component contract tests remain in game-network.

<a id="native-http-server-api"></a>

### 1.1 Independent HTTP/1.1 server

Implements [N-HTTP-01–08](OGBS-Network-1.0.md#http-server-profile). The following API is available; no framework HTTP client or HTTP/2 API is added. Native FullHttpRequest/FullHttpResponse avoid a duplicate message model. The TCP/WS APIs in §§2–8 remain separate.

| Entry | Signature or setting | Contract |
| --- | --- | --- |
| HttpServer | static HttpServerBuilder builder(); void start(); SocketAddress localAddress(); void close() | AutoCloseable; synchronous one-shot listener; localAddress is null before a bind attempt |
| Required configuration | bindAddress(SocketAddress) | Required listener address |
| Optional fallback | handler(Function<FullHttpRequest, FullHttpResponse>) | Returns one complete final response synchronously for requests forwarded by extensions; default empty 404 |
| Asynchronous fallback | asyncHandler(BiConsumer<FullHttpRequest, HttpResponseCallback>) | Receives a request-specific callback, completes on any thread; last handler/asyncHandler setting replaces the previous one |
| Completion callback | HttpResponseCallback: boolean onResponse(FullHttpResponse); boolean onFail(Throwable) | One thread-safe winner; each non-null response submission consumes one owned reference, including duplicate/late completion |
| Native pipeline | pipeline(Consumer<ChannelPipeline>) | Per-channel configurers run in registration order after aggregation, before the fallback; addLast installs HTTP handlers, addFirst installs caller-created TLS |
| Request limits | maxContentLength(int); maxInitialLineLength(int); maxHeaderSize(int) | Positive byte counts; defaults 1 MiB body, 4096-byte initial line, 8192-byte headers |
| Inbound timing | readTimeoutMillis(long) | Default 30,000 ms; zero disables, negative rejects; absence of inbound bytes closes the socket |
| Business execution | executorGroup(EventExecutorGroup) | Optional, borrowed; every executor must be an OrderedEventExecutor |
| Transport resources | bossGroup(EventLoopGroup); workerGroup(EventLoopGroup); channelFactory(ChannelFactory<? extends ServerChannel>) | Default NIO, owned boss with one loop and owned worker using Netty's default size; injected groups are borrowed |
| Native options | <T> option(ChannelOption<T>, T); <T> childOption(ChannelOption<T>, T) | Validate through Netty; native transport needs matching groups/factory |
| Construction | HttpServer build() | All fluent methods above return HttpServerBuilder; immutable configuration snapshot, no default groups started before start |

Null arguments reject with NullPointerException; invalid limits/unordered executors reject with IllegalArgumentException. Missing required fields and repeated start/start after close reject with IllegalStateException. Bind failure becomes NetworkException retaining its cause; cleanup is attempted and interruption preserved. Pipeline initialization failure closes that accepted channel and is diagnosed by Netty, without invalidating the bound listener. Builders are mutable assembly objects and not thread-safe; function/configurer/context/group objects are shared references across snapshots.

Pipeline: optional caller SslHandler first → http-read-timeout (when enabled) → http-codec → http-validation → http-keep-alive → http-aggregation → user HTTP handlers → http-application fallback. Configurers see the assembled HTTP base and append handlers with addLast; no http-application context exists until configuration finishes. addFirst remains available for TLS/raw-byte handlers. These http- names are reserved; extensions must not remove or reorder core handlers. HttpDecoderConfig supplies line/header limits; the HttpObjectAggregator subclass handles body limits/Expect; native HttpServerKeepAliveHandler applies framing-aware persistence to all responses, including extension responses. HTTP/1.0 and HTTP/2 reject with 505; CONNECT with 405, Upgrade with 400. Invalid decode/Host/line/headers produce 400; body overflow produces 413 and unsupported Expect produces 417. Empty protocol-error responses close. No HTTP/2 negotiation or shared WS pipeline is added.

By default HTTP processing and addLast extensions run on the channel EventLoop. executorGroup assigns the HTTP base and fallback to one ordered executor per connection. When offloading, extensions after http-codec must use that same group, for example pipeline(p -> p.addLast(group, "auth", new AuthHandler())); adding them without the group would switch back to EventLoop and break sequencing. Initialization rejects a differing context.executor() after http-codec, and build rejects childOption(SINGLE_EVENTEXECUTOR_PER_GROUP, false). TLS/raw handlers before http-codec may use EventLoop. Shared functions must support concurrent connections; create non-sharable native handlers inside each configurer. Applications select bounded execution resources and own shutdown. No business executor/timer is created implicitly. Deferred fallback responses use the explicit callback below; no CompletionStage, Future or streaming response API is introduced.

Native extension ownership follows N-HTTP-08: a forwarding ChannelInboundHandlerAdapter calls ctx.fireChannelRead(request) without releasing the transferred reference; a consuming adapter releases it after use. SimpleChannelInboundHandler auto-releases, so forwarding from it requires retain. Native responders use ctx.writeAndFlush(FullHttpResponse) and set valid Content-Length or native transfer framing themselves; no second fallback response is generated. KeepAlive policy honors request/response closure, and later pipelined requests are suppressed after closure selection. The server does not auto-release a request already consumed by an extension. Native CorsHandler may consume OPTIONS and produce preflight responses; HttpContentCompressor may transform fallback/native output and adjust wire framing. Authentication, routing, CORS and compression configuration remain application policy.

On the fallback-function path, the request is borrowed through function return. For an echo, return new DefaultFullHttpResponse(HTTP_1_1, OK, request.content().retainedDuplicate()); returning the content without retain invalidates the send when the request is auto-released. Returned response ownership always transfers to the server, including response-validation failure or a disconnect during processing. The server selects HTTP/1.1, removes response Transfer-Encoding/trailers and derives normal Content-Length from the actual body. It honors an explicit nonnegative length for HEAD/304, suppresses their transmitted bodies, removes 204 length/body, and sends 205 length zero. A request or response with Connection: close ends the connection after writing and suppresses later pipelined business work. Write failure closes without retry. Handler throws/null/1xx or response-framing failure attempts empty 500 and logs the original application exception at ERROR; no exception detail is sent. Recognized I/O, decoder boundary and read-timeout exceptions produce DEBUG connection summaries, while unknown pipeline errors remain ERROR.

ReadTimeoutHandler measures inbound inactivity, including idle Keep-Alive and partial bodies; it does not bound a handler or total request duration. Disconnect cannot roll back or interrupt a handler. start/close reject calls from related boss/worker/HTTP executors. Close marks the entry terminal, closes its listener and shuts down only owned boss/worker groups with shutdownGracefully(0, 5 seconds). It keeps no ChannelGroup or connection registry, never shuts down an injected HTTP executor, and does not wait for external business work. With borrowed worker resources, accepted sockets may continue serving after listener close until their own timeout/closure; manage them via application-owned native resources. Repeated close is a no-op, not a concurrent cleanup barrier.

Confirmed choice: HTTP/1.1 for ordinary internal endpoints, with independent implementation rather than adding HTTP branches to NetworkServer/ConnectionHandler. Reconsider HTTP/2 for an integration requirement or a measured connection/response-order bottleneck; high QPS alone is not evidence. Body limits do not bound executor queues, connection count or response buffering, and no production capacity is claimed. Built-in routing, JSON, compression, multipart and CORS remain outside this initial implementation.

Sources: [HttpServer](../../game-network/src/main/java/cn/managame/network/http/HttpServer.java), [builder](../../game-network/src/main/java/cn/managame/network/http/HttpServerBuilder.java), [transport](../../game-network/src/main/java/cn/managame/network/http/HttpServerTransport.java). Validation: [HttpServerTest](../../game-network/src/test/java/cn/managame/network/http/HttpServerTest.java), [HttpServerExample](../../game-example/src/main/java/cn/managame/example/network/HttpServerExample.java), [example test](../../game-example/src/test/java/cn/managame/example/network/HttpServerExampleTest.java). Tests cover real sockets, chunked input, persistent/pipelined response boundaries, HEAD/204/205/304, native TLS/plaintext rejection, rejection, 100/413 ordering with offloading, ownership, handler failure, inactivity, resource lifecycle, native routing/auth rejection/default 404, CORS preflight, gzip transformation and extension executor consistency. Production capacity remains unverified; repository-wide verification is currently blocked by pre-existing RPC test API mismatches, and the example module also contains a pre-existing RpcEchoExample.maxPendingCalls compilation mismatch. Compile/run the HTTP example separately while those unrelated errors remain.

<a id="http-async-response"></a>

### 1.2 Asynchronous response callbacks

`asyncHandler(BiConsumer<FullHttpRequest, HttpResponseCallback>)` replaces the synchronous fallback setting; a subsequent `handler(...)` replaces it in turn. Already built listeners retain their configuration snapshot. A callback object is created for each request reaching the fallback. The caller may complete it inside the handler or retain it for a Route/business task. Network does not depend on Runtime or choose Domain/RouteKey. [HttpResponseCallback](../../game-network/src/main/java/cn/managame/network/http/HttpResponseCallback.java) exposes only:

```java
boolean onResponse(FullHttpResponse response);
boolean onFail(Throwable cause);
```

Both methods are thread-safe. The first valid invocation returns true, claiming the sole completion, including when the channel is already inactive or scheduling later fails. True is neither write acceptance nor delivery confirmation. Later invocations return false; every non-null onResponse consumes one independent owned reference and releases duplicates/undeliverable responses. Never reuse a transferred reference without separately retaining it beforehand. Null response/cause throws NullPointerException without claiming completion. onFail attempts an empty 500 and closes when still deliverable, logging the cause without exposing it. A handler throw uses onFail; throwing after completion only logs and cannot produce a second response. An invalid winning response (including 1xx or malformed HEAD/304 length) is released and attempts an empty 500/close, retaining existing synchronous normalization. A synchronous handler returning null follows its existing 500/close path.

Requests are still borrowed only until the initial handler invocation returns. The callback stores HEAD/persistence flags and connection state, not the request. For Route dispatch, decode immutable inputs before returning or retain/copy needed buffers, releasing those application-owned references on rejection and after use. Response completion is not permission to keep accessing request data. Returning without completing the callback leaves the connection waiting; the application must eventually complete it or manage its own deadline. Network adds no automatic business cancellation or retry. Disconnect/close does not claim the callback; a later first completion may return true while releasing its undeliverable response.

Completion is marshalled to the connection's ordered HTTP executor; completion on that executor may run inline. Executor rejection releases a submitted response and requests connection closure. Caller-owned HTTP executors must remain available while their channels need pipeline cleanup. Before dispatching the next request, HttpServerTransport observes completion of the final outbound LastHttpContent write, including content produced by native compression and native application responses. It gates decoded input before validation/aggregation, so a later request's automatic 100/400/413/417 cannot overtake the pending response. It temporarily disables AUTO_READ after the current body ends, restores it only if the framework paused it, and releases already decoded/scheduled queued input on closure. User manual reads and executor queues remain externally managed; the transient input buffer is not a configurable hard memory limit. Native asynchronous extensions still release their own requests, write one correctly framed response on the ordered context, and use no fallback callback.

The existing readTimeoutMillis continues while waiting, including when automatic reads are paused. It measures inbound inactivity, not business execution or rollback. For example, after a callback is handed to a Route task, a 30-second inactivity closure can occur before that task finishes; its later onResponse releases the response without undoing the task. Disable or tune this I/O timeout explicitly for long operations. Listener closure with borrowed worker groups continues to allow existing connections/completions as in N-HTTP-06.

Example: [HttpAsyncServerExample](../../game-example/src/main/java/cn/managame/example/network/HttpAsyncServerExample.java) decodes UTF-8 within the borrowed scope and completes the callback from an application-owned executor. [HttpAsyncServerExampleTest](../../game-example/src/test/java/cn/managame/example/network/HttpAsyncServerExampleTest.java) exercises its full round trip. HttpServerTest additionally covers async cross-connection progress, pipeline/automatic-response ordering, request/response ownership, completion races, failure, late completion after owned shutdown, inactivity and Builder replacement. Existing TCP/WS and synchronous HTTP APIs remain source-compatible; no dependency or wire-profile change is made. Validation for this revision: 85 Network tests passed, including 25 HTTP tests; both HTTP example tests passed using an isolated JUnit launcher. Root clean verify passed Core/Network then failed at the existing RPC test compilation mismatch; no repository-wide success is claimed.

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

A Client can connect to several targets or repeatedly to one target. Sync/async share the same native Promise-based connection flow. Claim success before onConnected; deliver the final success callback afterward. Callback exceptions only log, producing neither a second result nor ConnectionHandler.onException.

Synchronous connect from any EventLoop of that Client immediately throws IllegalStateException. Interrupted waits restore the interrupt flag, cancel the attempt, close the Channel, and throw NetworkException. Even if success just won, close the connection the synchronous caller cannot receive.

Valid asynchronous attempt callbacks run on the selected Netty EventLoop. Pre-attempt rejection, including invalid input or closed Client, invokes onFailure directly on the caller thread. If a terminated external EventLoop rejects notification, notify once on the current thread as a fallback. A null callback directly throws NullPointerException.

| Client Builder method | Default / meaning |
| --- | --- |
| handler(ConnectionHandler) | Required |
| pipeline(Consumer&lt;ChannelPipeline&gt;) | Append in registration order |
| webSocket() / webSocket(int maxMessageSize) | WS mode; default 1 MiB |
| eventLoopGroup(EventLoopGroup) | Create standard NioEventLoopGroup() by default |
| channelFactory(ChannelFactory&lt;? extends Channel&gt;) | NioSocketChannel::new |
| option(ChannelOption&lt;T&gt;, T) | Native Bootstrap option |
| build() | Snapshot and create an independent Client |

TCP accepts only SocketAddress and WS only URI; mode mismatch throws IllegalStateException. URI requires a valid host and ws/wss scheme, with default ports 80/443. Reject user-info, fragment, port 0, and out-of-range ports.

TLS is configured only through native pipeline handlers; there is no sslContext(...) builder method or automatically created client context. After all pipeline configurers run, exactly one SslHandler may be present and it must be the first handler. wss:// requires it; ws:// rejects it instead of silently ignoring TLS. TCP uses TLS when that handler is present. The caller configures trust, certificates, client/server mode, peer host/port, hostname verification and handshake timeouts. See [native TLS configuration](#native-tls-configuration).

<a id="41-connectattempt-的完成协议"></a>

<a id="41-connectattempt-completion-protocol"></a>

### 4.1 Native connection result

NetworkClient uses one native Netty Promise<Connection> per call for the result, cancellation and synchronous await. It creates the Bootstrap and Channel on the selected EventLoop, serializing initialization and readiness/failure handling. Custom channel factories therefore run on that EventLoop and must not block it. There is no result-wrapper class, separate completion CAS, cancellation marker or attached-Channel field.

The Promise uses ImmediateEventExecutor so cleanup and notification listeners still execute when the channel executor has terminated. This creates no worker thread. Result listeners explicitly dispatch ConnectCallback to the selected EventLoop; rejected dispatch falls back to the current thread. A channel-bound Promise alone could lose these notifications when its executor rejects listener execution.

```text
Create an independent Promise (no endpoint registry)
→ run Bootstrap on the selected EventLoop
→ transport completes with application pipeline assembled
→ check the result is unfinished and Client/channel are open
→ claim delivery with Promise.setUncancellable()
→ create Connection and invoke onConnected
→ store success and wake waiter/invoke onSuccess
```

Failure uses Promise.tryFailure; interrupted waiting uses Promise.cancel(false). A result listener closes the Bootstrap channel for failure/cancellation. If a channel is created after cancellation, adding the listener to the completed Promise still closes it; initialization also rejects a completed result before business delivery. No endpoint registration/removal callback is needed.

After checking isDone, setUncancellable arbitrates only against the waiting thread cancellation. It is not a general success/failure lock: Netty still allows tryFailure on an uncancellable Promise. Initialization, channel readiness and transport failure handling are serialized on the EventLoop; no other thread may independently fail a successfully claimed result. onConnected runs before setSuccess. Its exceptions go only to ConnectionHandler.onException, and closing inside it cannot produce ConnectCallback.onFailure.

<a id="42-中断和回调线程例外"></a>

### 4.2 Interruption and callback thread exceptions

An interrupted synchronous await first cancels the Promise. If cancellation wins, the channel cleanup listener reclaims the underlying channel, including late creation. If success was already claimed, cancellation cannot win; an additional listener closes the successful Connection after onConnected returns. For example, interrupting a waiter while onConnected is blocked returns NetworkException immediately with the interrupt flag restored; releasing the callback then closes its undeliverable Connection. This produces no second result.

Normal asynchronous results use the selected EventLoop; pre-attempt validation failure calls onFailure on the caller thread. A terminated borrowed EventLoop that rejects notification requires one current-thread fallback. Higher layers therefore cannot assume onFailure always has a Channel EventLoop context or unconditionally call synchronous connect/close inside callbacks.

ConnectCallback failures receive diagnostics only, not ConnectionHandler.onException: the former handles an attempt's result; the latter belongs to an established connection lifecycle.

<a id="5-配置快照与握手参数"></a>

## 5. Configuration snapshots and handshake parameters

Builders permit repeated build. Each instance snapshots handler, pipeline list, options, and transport settings. Without external groups, instances create separate resources; supplied groups are explicitly shared. Snapshots do not deeply copy handler or lambda-captured state (including captured SslContext). Builders are not concurrent configurators.

Null handler/pipeline/group/factory/option/value arguments are rejected immediately. Missing required configuration at build throws IllegalStateException; invalid paths and nonpositive sizes throw IllegalArgumentException.

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

Copy HttpHeaders at construction and return a fresh copy from headers(). Defaults are empty headers and null subprotocol; options can safely serve several connects. Servers do not negotiate subprotocols or expose a subprotocol builder setting. Clients retain per-connect WebSocketConnectOptions for external WS services. A required subprotocol without a matching response fails client establishment; the server may already have completed Upgrade before receiving closure, so the two local results are not a transaction. Native handlers can inspect HTTP Upgrade in pipeline(...); the WS builders add no Auth/Router/HTTP serving DSL; the independent HTTP entry is defined in §1.1.

<a id="51-快照的深度和可复用范围"></a>

### 5.1 Snapshot depth and reuse

Changing builder options or appending pipelines after build affects only later builds, never existing Server/Client configuration. Handler and captured objects remain shared references; snapshot does not copy their mutable internals.

Each Channel invokes configurers to create its codecs. Create non-Sharable decoders inside the configurer instead of adding one external instance to every connection. Applications verify @Sharable and concurrency safety when sharing handlers.

WebSocketConnectOptions separately copies headers. Changing either original headers or a headers() result does not change the options. This enables independent attempts to reuse them, without implying that Server interprets or authenticates the headers.

WebSocket handshake timeouts use native Netty defaults directly, with no separate builder setting or framework default constant. Configure TLS timeouts through native SslHandler. The earlier server-subprotocol and custom WS-timeout builder entries are removed; callers must remove those calls and recompile.

<a id="6-pipeline-与超时"></a>

## 6. Pipeline and timeouts

```text
TCP: [caller SslHandler] → user pipeline → ConnectionHandler adapter
WS:  [caller SslHandler] → HTTP codec / HTTP aggregator / WS protocol / frame aggregator
     → WS handshake observer → binary decoder/encoder → user pipeline → ConnectionHandler adapter
```

Run configurers anew per Channel, in registration order for pipeline(a).pipeline(b). Create separate non-Sharable codecs. Outbound processing traverses user encoders in reverse, then wraps ByteBuf as BinaryWebSocketFrame. Inbound aggregation retains content while the Netty decoder releases the original frame.

The WS observer bounds the server establishment deadline and forwards the native successful handshake event. The terminal ConnectionHandlerAdapter handles TLS/WS completion events directly and delivers business callbacks; it exposes no intermediate readiness Future. Its temporary closeFuture listener handles pre-delivery closure even before inactive reaches the tail. TCP includes no business framing. IdleStateHandler events reach onEvent using native types. Custom asynchronous handlers maintain propagation and ordering; do not delete/reorder internal `network-*` handlers or fabricate lifecycle/handshake events.

Internal handler names use the component-based network- prefix, independent of the project name or Java package. Reserve these names for framework handlers; user handlers must have distinct names because Netty rejects duplicates. This replaces the former managame- prefix without aliases: callers using addBefore/addAfter/get by name must migrate. Handler order, events and wire behavior are unchanged. Internal names support native insertion, such as HTTP Upgrade validation after `network-http-aggregate`. TLS has a caller-chosen handler name: use `pipeline.addFirst("ssl", ...)` during initialization. The assembler discovers that SslHandler and waits for its handshakeFuture; TLS handshake events never reach ConnectionHandler.onEvent.

| Parameter | Current default and configuration |
| --- | --- |
| TCP connect | Netty CONNECT_TIMEOUT_MILLIS, 30 seconds; option |
| TLS handshake | SslHandler default 10 seconds; native pipeline handler |
| WS client handshake | Native WebSocketClientProtocolConfig default, currently 10 seconds; timer starts when channelActive initiates the handshake, including time waiting for TLS |
| WS server establishment | Read the native WebSocketServerProtocolConfig default deadline, currently 10 seconds; from channelActive through silent-peer waiting, TLS and Upgrade; cancel timer on success/closure |
| HTTP Upgrade body | HttpObjectAggregator 64 KiB, independent of business messages |
| Binary WebSocket message | 1 MiB for frame payload and aggregate; builder-adjustable |
| NIO / socket options | Netty defaults; native option / childOption |

Reject Text with close code 1003. Oversized frames/aggregates and WS violations close without onMessage. Netty protocol handlers process Ping/Pong/Close. Server pipeline initialization failure diagnoses and closes only that Channel; Client reports attempt failure.

<a id="61-装配时点与事件方向"></a>

### 6.1 Assembly timing and event direction

NetworkChannelInitializer.configure adds the write-error entry, optional WebSocket protocol/payload handlers, then runs user configurers. Configurers add SslHandler first, ahead of all existing handlers. The assembler validates its placement and WS URI scheme, adds the terminal adapter, supplies the configured SslHandler and WS mode to it, and marks initialization complete. Delivery requires successful assembly, activation reaching the adapter, all configured handshakes and endpoint admission. Handler construction order does not determine byte-processing order; final pipeline position does.

Inbound events generally run head to tail; writes tail to head. Main internal order (brackets optional):

```text
[caller SslHandler]
→ network-write-errors
→ [network-http → network-http-aggregate
   → server network-websocket-path → network-websocket
   → network-websocket-aggregate → network-websocket-handshake]
→ [network-binary-in → network-binary-out]
→ user codecs/event handler
→ network-connection
```

For WS, user encoders create ByteBuf, binary-out wraps BinaryWebSocketFrame, then WS/TLS encodes it. TCP adds neither binary adaptation nor a business length header.

Native addBefore/addAfter insertion must preserve handshake, lifecycle, and release logic. Swallowing handshake/inactive events or deleting internal handlers violates prerequisites and is not supported arbitrary pipeline rewriting.

<a id="62-为什么区分生命周期-gate-与末端-adapter"></a>

<a id="62-why-lifecycle-gate-and-terminal-adapter-are-separate"></a>

<a id="62-内部职责与扩展边界"></a>

### 6.2 Internal responsibilities and extension boundaries

Netty implements TLS/WS protocols; the adapter directly connects completion events to business delivery. There is no generic Transport, separate establishment protocol or lifecycle coordinator.

| Internal component | Responsibility |
| --- | --- |
| NetworkServer / NetworkClient | Configure listening/connection creation and owned EventLoopGroups; supply the open-endpoint check. Client also holds the native result Promise for each call, without a connection/attempt registry |
| [NetworkChannelInitializer](../../game-network/src/main/java/cn/managame/network/netty/NetworkChannelInitializer.java) | Assemble handlers/configurers, validate TLS placement and URI scheme, and supply the TLS reference and WS mode to the terminal adapter |
| [WebSocketTransport](../../game-network/src/main/java/cn/managame/network/netty/WebSocketTransport.java) | Assemble binary WS protocol/payload handlers; handle establishment deadlines and protocol rejection, forwarding native success events |
| [ConnectionHandlerAdapter](../../game-network/src/main/java/cn/managame/network/netty/ConnectionHandlerAdapter.java) | Directly check delivery prerequisites, create Connection, invoke business handlers and complete the client result on the EventLoop; release inbound references and isolate exceptions |

TCP delivers after assembly and channelActive reaching the tail. TLS checks on native SslHandshakeCompletionEvent and requires SslHandler.handshakeFuture success. WS uses the native WebSocket handshake success event; WSS requires both WS success and native TLS success. None creates an intermediate ready Promise, WS completion Promise or PromiseCombiner. Only the client retains a Promise<Connection> for the connection result; the server needs no result Promise.

The adapter checks the endpoint-supplied BooleanSupplier and, for clients, arbitrates against waiter cancellation with result Promise.setUncancellable. In the same event-handling call it creates Connection, invokes onConnected, then completes the client success result. There is no queued Connection creation after a readiness Future completes. TLS/WS handshake events are consumed at the tail rather than reaching ConnectionHandler.onEvent. Native user handlers may observe them but must preserve their order and forward them.

A temporary closeFuture listener is removed on business delivery or establishment failure. It handles pre-delivery closure, including before the adapter is added. Establishment failure closes the current Channel and yields one client failure or server diagnostics without business lifecycle callbacks. After delivery, only terminal channelInactive reports disconnection. Successful native protocol completion cannot override initialization failure.

For example, when Netty defers Promise listeners to bound recursion, a handshake success event followed immediately by the first binary message must still produce onConnected → onMessage. Direct event handling removes the intermediate notification window without buffering messages or adding a connection registry. See ConnectionSetupTest.nestedHandshakeNotificationDeliversFirstMessageBeforeReturning. NetworkContractTest.firstMessagesCanBeSentInsideOnConnected verifies immediate sends by both peers over real TCP/TLS/WS/WSS.

Netty fully implements TLS/WS; completion handling includes no RPC, login or business negotiation. Neither peer overrides the native WS handshake timeout, currently 10 seconds. The server reads that same deadline from native configuration to bound silent peers from channelActive, including TLS establishment; receiving Upgrade does not reset the total deadline. The client uses the native Netty WS handshake timer. A timeout bounds an unfinished handshake; successful handshakes deliver onConnected immediately. TCP connect and both peer TLS timeouts are independent; the first failure or expiry ends the attempt. Timers depend on EventLoop execution and are not strict wall-clock bounds when it is blocked; timeout/failure closes the channel.

There is no HashSet, ChannelGroup, shared admission monitor or cross-connection cancellation list. Per-channel processing stays on its EventLoop; native Promises hold individual connection results. Server synchronizes only administrative start/close, and Client CAS makes resource closure one-shot. These administration mechanisms do not participate in message delivery or connection registration. This removes global bookkeeping, not a claim of measured throughput improvement.

The terminal adapter receives user codec output before channelInactive, then calls onDisconnected once. EOF tail messages may therefore reach onMessage while Connection.isActive is false; borrowed references are still released when callbacks throw. Ordered offloaded handlers must forward reads/errors before inactive, and Netty dispatches the adapter on its Channel EventLoop. No timer bypasses that ordering. Swallowing inactive can prevent notification. A decodeLast exception emitted by Netty after inactive receives diagnostics only under the existing post-lifecycle rule.

There is no generic Transport interface or TLS wrapper. WebSocket keeps concrete assembly because the binary message profile requires HTTP Upgrade, frame aggregation and payload adaptation. Native SslHandler is the only user-installed handler recognized as an establishment prerequisite. Arbitrary business handshakes do not join readiness automatically. Dynamic TLS insertion, SNI handlers that later install SslHandler, STARTTLS and multiple nested TLS handlers are outside this initialization contract; they require a separate design before support is claimed.

<a id="63-写入错误的路由"></a>

### 6.3 Write error routing

voidPromise avoids exposing/maintaining a completion Future per send. Its failures may originate at the pipeline head; traversing a WS protocol handler directly can trigger its default closure for ordinary outbound errors.

After business delivery, network-write-errors uses the last protocol ChannelHandlerContext captured during assembly to forward errors after protocol handlers and through payload/user codecs to the terminal adapter. It preserves user pipeline/onException handling without looking up handlers by name or using another lifecycle controller. It neither swallows errors nor disables invalid inbound WS-frame closure. Pre-establishment errors still fail the handshake.

A custom native handler may itself close Channel. Network's ordinary-error policy cannot undo that action; integrators must inspect their exceptionCaught implementations.


<a id="native-tls-configuration"></a>

### 6.4 Native TLS configuration

Configure one fresh SslHandler per Channel before initialization finishes. Place it first so inbound bytes are decrypted before HTTP/WS/business decoding and outbound bytes are encrypted after encoding. TCP activation may precede TLS completion; it is not connection success. Netty may queue outbound handshake-related application writes until TLS finishes; they must not go onto the wire as plaintext.

The following builder fragments assume caller-created serverContext, clientContext and ConnectionHandler handler. The client captures the intended endpoint because remoteAddress may still be null during channel initialization. A builder used for multiple targets needs a configuration strategy that supplies the correct peer identity for each connection.

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

SslContext/SslHandler, InetSocketAddress and NetworkServer/NetworkClient are native Netty, JDK and game-network types respectively. The caller creates contexts with suitable keys and trust configuration; creating a client handler with a host alone is not a substitute for explicitly configuring hostname verification. No framework code changes trust settings or engine parameters. Add webSocket("/game") to the server and webSocket() to the client, then use wss://localhost:8443/game for WSS; both endpoints still explicitly add TLS.

A missing handler for wss://, TLS with ws://, multiple SslHandlers, or an SslHandler placed after another handler is a pipeline configuration error. The client reports NetworkException retaining IllegalArgumentException as its cause; the server diagnoses and closes the affected accepted channel. Handshake failure/timeout creates no Connection and produces no business onException/onDisconnected. Owned-group shutdown closes unfinished handshakes; borrowed-group handshakes continue to their next result or timeout and then reject delivery if the endpoint is closed. See NativeTlsTest and NetworkContractTest.roundTripAndOrderedWrites.

This replaces sslContext(...) and implicit WSS context creation. The migration is source-incompatible for callers of that method; replace it with explicit pipeline configuration. The onConnected-after-handshake contract is unchanged.

<a id="7-资源与关闭"></a>

## 7. Resources and closure

Server marks itself closed, closes the listener and shuts down owned boss/worker groups. Client marks itself closed and shuts down its owned group. Neither traverses connections or attempts. Owned-group shutdown naturally closes associated Channels. With borrowed groups, existing channels remain caller-owned; unfinished operations settle through their next result, native timeout or channel closure.

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

An open-endpoint check is the admission point for a ready connection. If close is observed first, Server rejects that channel and Client reports IllegalStateException. Delivery that has already passed this check may finish concurrently with close; the client still arbitrates against interruption/transport failure using its native Promise. Owned-group shutdown may invalidate a successfully delivered Connection, which follows normal disconnect handling.

With external groups, close returns without waiting for or cancelling handshakes. For example, Client.close while a TLS peer is silent leaves its Channel active until native handshake timeout or caller-driven closure; the eventual callback sees closed and reports failure once. Do not disable native timeouts and then rely on endpoint close as a cancellation mechanism. An outstanding TCP connect that fails before readiness may instead report NetworkException. Applications needing a batch shutdown manage their own connections/resources.

<a id="8-失败与兼容性"></a>

## 8. Failure and compatibility

| Scenario | Representation |
| --- | --- |
| Invalid arguments, missing required configuration, lifecycle misuse | Java argument/state exceptions above; async entries call onFailure except null callback |
| bind/connect/TLS/WS/pipeline initialization failure | NetworkException preserving cause |
| Readiness or pre-connect check observes closed Client | IllegalStateException; no immediate notification guarantee for an already in-flight borrowed-group operation |
| Established Decoder/I/O/Handler error | Original Throwable → onException |
| onException / ConnectCallback throws | Final System.Logger diagnostics |
| INACTIVE / NOT_WRITABLE | WriteStatus; caller keeps ownership |

Old ConnectionListener, ConnectionKey, CloseInfo, NettyConnector, NettyAcceptor, tryWrite, and TestKit paths are obsolete without compatibility layers. game-rpc now integrates through ConnectionHandler/ConnectCallback and provides real TCP integration tests.

<a id="81-后续实现不应重新引入的隐式行为"></a>

### 8.1 Implicit behavior that must not return

New codecs must preserve three-state write acceptance and ownership. New transports must define establishment thresholds, timeouts, message boundaries, and protocol rejection. Asynchronous integrations must not block an EventLoop waiting for itself; attribute wrappers must not silently change post-closure semantics.

Heartbeats, IdleStateHandler, custom HTTP Upgrade checks, and authentication use native Netty integration or higher-layer composition; there is no separate framework DSL. A unified capability first needs observable behavior and resource responsibilities, then changes to both specifications. Convenience alone does not justify default reconnection, automatic closure, or business send queues.

Runnable entry: [NetworkEchoExample](../../game-example/src/main/java/cn/managame/example/network/NetworkEchoExample.java) and its test below. Diagrams, reference-count explanations, and tables here explain design; they are not standalone runnable programs.

### 8.2 Establishment and late-error logging

NetworkSupport uses the System.Logger named cn.managame.network. Classify server establishment failures and late transport exceptions after the business lifecycle ends as follows. Client establishment failures still go to the connection result without an additional duplicate framework error log.

| Type | Diagnostics |
| --- | --- |
| ClosedChannelException, SocketException, PrematureChannelClosureException | DEBUG disconnect summary |
| SSLException, WebSocketHandshakeException, CorruptedFrameException, TooLongFrameException | DEBUG protocol-rejection summary |
| Other exceptions, including initialization/configuration and unknown errors | ERROR with the original Throwable and stack |

Unwrap at most 8 DecoderException layers for classification; never treat every DecoderException as normal invalid input. For example, DecoderException wrapping IllegalArgumentException remains ERROR. These are type-based categories, not proof of the root cause of each disconnection. DEBUG summaries contain only context, reason category, remote address and exception type, without a Throwable, exception message, peer headers or body. Skip summary formatting when DEBUG is disabled. At the default INFO level these recognizable establishment failures produce no error stacks; enable DEBUG for this logger to investigate handshake rejection.

For example, a raw TCP probe disconnecting from a WS port reclaims its Channel with only a DEBUG summary; a configuration exception during pipeline initialization keeps its ERROR stack. Late TLS/WS failures follow the same classification instead of returning as ERROR after closure. Established onException routing is unchanged; exceptions thrown by user onException or ConnectCallback remain ERROR. This policy controls only the framework logger, not Netty/application logging backends, and adds no global rate-limit state. Source: [NetworkSupport](../../game-network/src/main/java/cn/managame/network/netty/NetworkSupport.java); validation: EstablishmentLoggingTest.

<a id="9-验证与边界"></a>

## 9. Validation and limits

- [EstablishmentLoggingTest](../../game-network/src/test/java/cn/managame/network/netty/EstablishmentLoggingTest.java): stackless DEBUG summaries for disconnect/rejection, silence at INFO, original stacks for unknown errors and late TLS-error classification.
- [NetworkContractTest](../../game-network/src/test/java/cn/managame/network/netty/NetworkContractTest.java): real TCP/TLS/WS/WSS, write order, lifecycle, backpressure, attributes, reference counting, business exceptions, snapshots, external resources.
- [WebSocketContractTest](../../game-network/src/test/java/cn/managame/network/netty/WebSocketContractTest.java): fragments, controls, Text/oversize rejection, exact paths, header snapshots and validation.
- [ConnectRaceTest](../../game-network/src/test/java/cn/managame/network/netty/ConnectRaceTest.java): independent result races, interruption, closure inside onConnected, late readiness rejection, and external-group channels surviving endpoint close until their own outcome.
- [ConnectionSetupTest](../../game-network/src/test/java/cn/managame/network/netty/ConnectionSetupTest.java): handlerAdded failure, write-error routing past WS policy, server silent-peer deadline/cancellation using the native default, and native client timeout for silent Upgrade.
- [NativeTlsTest](../../game-network/src/test/java/cn/managame/network/netty/NativeTlsTest.java): explicit TLS placement/count, URI consistency, trust and hostname failures, handshake timeout/cancellation, TLS success without WS Upgrade success, and plaintext rejection before HTTP processing.
- [DisconnectOrderingTest](../../game-network/src/test/java/cn/managame/network/netty/DisconnectOrderingTest.java): EOF tail delivery before disconnect, borrowed-reference release on callback failure, tail output followed by decodeLast failure, duplicate disconnect suppression, and ordered offloaded decoding on a real TCP connection.
- [NetworkEchoExampleTest](../../game-example/src/test/java/cn/managame/example/network/NetworkEchoExampleTest.java): compile/run the complete example in game-example; run mvn -pl game-example -am test.

Run `mvn -pl game-network -am test` for module tests and `mvn clean verify` for the repository. Tests generate temporary certificates with the current JDK keytool, limit Netty default threads to 2, and force the Windows JDK Selector wakeup pipe to TCP using a test-only unusable unixdomain.tmpdir to avoid intermittent AF_UNIX connect failure. Production code does not change JVM properties.

Public-network/native-transport/production-capacity certification and cross-language interoperability remain unverified. game-rpc supplies real TCP integration tests; automatic RPC-to-Runtime integration remains unimplemented.

<a id="91-易错契约的测试定位"></a>

### 9.1 Tests for error-prone contracts

| Contract | Test class and method |
| --- | --- |
| Four transports and ordered writes | NetworkContractTest.roundTripAndOrderedWrites |
| Both peers send immediately inside onConnected over all four transports | NetworkContractTest.firstMessagesCanBeSentInsideOnConnected |
| Rejection ownership, backpressure, send failure | NetworkContractTest.ownershipBackpressureAndOutboundFailure |
| Handler exceptions, ordinary events, inbound retain | NetworkContractTest.handlerExceptionsEventsAndRetain |
| onConnected before success; callback failure cannot create a second result | NetworkContractTest.asyncSuccessRunsAfterConnectedAndCallbackFailureIsNotConnectFailure |
| Borrowed groups stay open; no blocking own EventLoop | NetworkContractTest.externalGroupsKeepEstablishedConnectionsAndRejectBlockingCalls |
| Snapshots and per-Channel pipeline order | NetworkContractTest.snapshotsAndPerChannelPipelineOrder |
| Ordinary outbound errors do not auto-close TCP/TLS/WS/WSS peers | NetworkContractTest.outboundFailuresDoNotAutoCloseEitherPeer |
| External Server close preserves established/handshaking channels and rejects later delivery | ConnectRaceTest.externalServerCloseKeepsChannelsAndRejectsLateHandshake |
| Server closure during initialization prevents late delivery | ConnectRaceTest.serverCloseDuringInitializationPreventsLateDelivery |
| EOF tail delivery, reference ownership, decodeLast failure, and ordered offloaded disconnect | DisconnectOrderingTest |
| External Client close does not traverse attempts; subsequent channel outcome reports once | ConnectRaceTest.externalClientCloseLeavesHandshakeToChannelOutcome / externalClientRejectsReadinessAfterClosure |
| Handshake and first-message ordering during nested Promise notifications | ConnectionSetupTest.nestedHandshakeNotificationDeliversFirstMessageBeforeReturning |
| Interrupted wait reclaims resources and restores flag | ConnectRaceTest.interruptCancelsHandshakeAndRestoresFlag |
| Interrupt after success claim; fallback after executor termination; late Channel creation | ConnectRaceTest.interruptDuringOnConnectedClosesUndeliverableConnection / terminatedExecutorReportsFailureOnCallingThread / interruptBeforeChannelCreationClosesLateChannel |
| Success/close race and close inside onConnected | ConnectRaceTest.successAndCloseRaceHasOneOutcomePerAttempt / onConnectedCloseStillReportsSuccessfulConnect |

These methods are regression entry points. Revisit relevant contracts when changing timing, ownership transfer, pipeline order, or success claim points. A working TCP echo alone does not prove all Network behavior.

<a id="92-突发连接与资源规模"></a>

### 9.2 Connection bursts and resource scale

A NetworkClient is a reusable connection factory, not one physical connection. Concurrent connectAsync calls use independent results and channels; there is no client-wide connection lock or single-target restriction. Each client built without eventLoopGroup(...) immediately constructs a separate NioEventLoopGroup. Netty 4.1.135.Final normally configures twice the available processor count, unless overridden by io.netty.eventLoopThreads. Worker threads start on demand, but group construction already allocates per-loop selectors and queues. Creating many default clients therefore multiplies infrastructure resources even before all their loops run. Reuse one client for compatible configuration or explicitly share an application-owned group among clients requiring different handlers/pipelines. Client.close does not shut down a borrowed group or close its channels; the application closes its connections and eventually the group. Sources: [NetworkClient](../../game-network/src/main/java/cn/managame/network/netty/NetworkClient.java) and [NetworkClientBuilder](../../game-network/src/main/java/cn/managame/network/netty/NetworkClientBuilder.java). Ownership regression: NetworkContractTest.externalGroupsKeepEstablishedConnectionsAndRejectBlockingCalls.

Connection admission has no framework concurrency limit, pacing, or bounded waiting queue. Each off-loop connectAsync call submits a task to the selected EventLoop. The default Netty event-loop pending-task limit is Integer.MAX_VALUE; submitting faster than processing can accumulate tasks and later create many sockets/handshakes. Channel writability regulates established outbound writes, not connection creation. Burst control belongs to the application admission layer under the existing no-registry contract. Small-loop concurrency alone does not bound the number of pending attempts or connections.

CONNECT_TIMEOUT_MILLIS applies to the socket connect stage, not elapsed time from the public call. It does not include an earlier wait for the submitted task to run, blocking name resolution, or the full TLS/WS establishment and application onConnected callback. Event-loop scheduling can also delay timeout delivery. For example, a 50ms socket timeout cannot finish an attempt while its connect task waits behind 250ms of other loop work. connectAsync returns void and exposes no per-attempt cancellation handle; a borrowed-group Client.close does not supply batch cancellation. The current implementation uses Bootstrap's default JDK-based blocking name resolver for unresolved addresses; WS URIs create unresolved addresses, so uncached/slow DNS may block the selected loop and other channels using it. Already-resolved TCP addresses avoid this resolution step. Native socket timeout and TLS/WS deadlines remain separate as specified in §6. Sources: NetworkClient.validate/startConnect and §4's result flow. This describes current implementation limits, not an added total-deadline or resolver API.

Pipeline initialization, ConnectionHandler callbacks, and normal ConnectCallback delivery run on EventLoops. Heavy onConnected work delays both client success notification and other connections on that loop. Keep them short and dispatch application work using its own bounded execution mechanism. Share a reusable TLS context and create a fresh SslHandler per channel as in §6; context/key-store construction inside each pipeline configurer adds avoidable handshake-path cost. Server option(SO_BACKLOG, ...) can adjust the listener backlog, while worker groups, socket resources, CPU, and application processing remain separate limits. Backlog and timeout values need deployment measurements, not a universal higher default.

Current repository regression tests cover small concurrent races and all four transports; they are not a repeatable capacity benchmark. A local no-payload burst can verify independent callbacks and cleanup without establishing TLS/WSS capacity, business throughput, public-network behavior, or sustained churn limits. Capacity validation should separately vary reused clients versus shared-group clients, connection count/rate, TCP/TLS/WS/WSS, slow handshakes, rejection and close races. Record success/failure counts, callback uniqueness, establishment percentiles, event-loop delay, heap/direct-memory use, threads, sockets and post-close reclamation. Any future per-attempt cancellation, total deadline, resolver customization, or connection-admission capability remains a proposed extension; select its observable behavior and ownership before changing public APIs or the standard.
