# game-network

English | [简体中文](README.zh-CN.md)

A game server networking component built with Java 25 and Netty 4.2.15.Final. Current version: 0.1.0-SNAPSHOT.

## Interfaces and implementations

| Public interface | Concrete class | Protocol entry point |
|---|---|---|
| NetworkServer: start / stop | TcpNetworkServer | listen(host, port) |
| NetworkServer: start / stop | WsNetworkServer | listen(host, port); uses WSS when sslContext is configured |
| NetworkServer: start / stop | HttpNetworkServer | listen(host, port), with native HTTP handlers |
| NetworkClient: init / destroy | TcpNetworkClient | connect(host, port, callback) |
| NetworkClient: init / destroy | WsNetworkClient | connect(uri, callback); the URI selects WS or WSS |

Connection and NetworkHandler remain public interfaces. Protocol-specific connection methods belong to the concrete clients. The shared NetworkClient interface defines only the common lifecycle, without connect overloads that do not apply to every client.

game-network-api depends only on the JDK. game-network-netty provides the concrete implementations above and native extensions. An HTTP client is not currently provided.

## Multi-protocol gateway

~~~java
NetworkResources resources = NetworkResources.builder()
    .ioThreads(4).httpThreads(2)
    .shutdownTimeout(Duration.ofSeconds(5)).build();

TcpNetworkServer tcp = TcpNetworkServer.builder()
    .resources(resources).listen("0.0.0.0", 7000)
    .handlerFactory(EchoHandler::new)
    .pipeline((connection, pipeline) -> pipeline.addLast(
        new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4),
        new LengthFieldPrepender(4)))
    .build();

WsNetworkServer ws = WsNetworkServer.builder()
    .resources(resources).listen("0.0.0.0", 7001)
    .handlerFactory(EchoHandler::new)
    .webSocketServer(config -> config.websocketPath("/game"))
    .build();

HttpNetworkServer http = HttpNetworkServer.builder()
    .resources(resources).listen("0.0.0.0", 8080)
    .contextPath("/game")
    .httpPipeline(pipeline -> pipeline.addLast(new HealthHandler()))
    .build();
~~~

The application manages startup and shutdown through a List<NetworkServer>. On exit, stop all servers and destroy all clients before closing resources. See [GatewayExample](game-network-netty/src/test/java/cn/managame/network/tests/GatewayExample.java) for a runnable example with complete initialization and failure cleanup. Run its main method in an IDE with JDK 25 and press Enter to stop. Passing a PEM certificate chain and private key as two arguments also starts a WSS server.

Each server handles its own protocol and can listen on multiple addresses through repeated listen calls. Use listen(name, address) for named listeners and boundAddresses() to look up the actual address bound when using port 0. Combine multiple servers for multiple protocols. TCP and WS/WSS share the real-time IO group; HTTP uses a separate HTTP IO group in NetworkResources, and HTTP messages do not pass through NetworkHandler.

HTTP `contextPath("/game")` sets a common application path for all listeners on that server. For an external request to `/game/health?detail=1`, the application HTTP handler receives `request.uri()` as `/health?detail=1`. Both `/game` and `/game/` map to `/`. Matching uses the original path and complete path segments and is case-sensitive. Unmatched paths such as `/game2/health` return 404 without reaching application handlers. Query parameters and the remaining path are not URL-decoded.

The default contextPath is `""`; like `"/"`, it represents the root path and leaves the URI unchanged. A trailing `/` in the configuration is removed. Non-root paths must start with `/`. Segments support English letters, digits, and `-._~`, including multi-level paths such as `/my-game/api`. Empty segments, `.` / `..` segments, percent encoding, query parameters, and fragments are rejected.

If a gateway or Nginx preserves the `/game` prefix when forwarding, configure `/game` on the backend. If the proxy removes the prefix, use the root path. The component handles inbound paths only: it does not rewrite response `Location`, Cookie Path, or page links, and does not infer a prefix from forwarding headers.

Configuring a server sslContext on WsNetworkServer makes its listeners use WSS. To serve both WS and WSS, create two WsNetworkServer instances and share resources. When resources is omitted, the component owns its resources and closes them on stop; explicitly supplied resources are borrowed.

## Clients with multiple targets

~~~java
TcpNetworkClient tcpClient = TcpNetworkClient.builder()
    .resources(resources).handlerFactory(ClientHandler::new)
    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
    .pipeline((connection, pipeline) -> pipeline.addLast(
        new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4),
        new LengthFieldPrepender(4)))
    .build();

WsNetworkClient wsClient = WsNetworkClient.builder()
    .resources(resources).handlerFactory(WsClientHandler::new).build();

tcpClient.init();
wsClient.init();

tcpClient.connect("127.0.0.1", 7000, callback);
tcpClient.connect("10.0.0.8", 7000, anotherCallback);
wsClient.connect(URI.create("ws://127.0.0.1:7001/game"), wsCallback);
wsClient.connect(URI.create("wss://gateway.example/game"), secureCallback);
~~~

The client lifecycle is build → init → repeated connect calls → destroy. init initializes the required IO resources, resolver, and Bootstrap without opening a remote connection. Repeated init calls do not recreate the Bootstrap. Calling connect before initialization throws synchronously. A destroyed client cannot be initialized again; create a new client instead. Connection.close closes only that connection, and the client can continue connecting to other targets.

TcpNetworkClient creates one Bootstrap during init and configures it with the entire EventLoopGroup. All connect calls share that instance, and Netty assigns the EventLoop. Each target is supplied to connect. The shared Bootstrap does not store a call's address, callback, or temporary attributes, and is neither created nor cloned per connection. The ChannelInitializer creates a separate result Promise on each Channel, and a listener on the native connection Future continues processing the user initialization result.

WsNetworkClient also creates one shared Bootstrap during init, configured with the entire EventLoopGroup. Each connect first calls Bootstrap.register so Netty assigns the Channel and EventLoop. After registration succeeds, the pipeline is configured for that call's URI on its EventLoop, followed by Channel.connect. Address resolution reuses Netty's native ResolveAddressHandler, and native channelActive triggers the WS/WSS handshake. The URI and callback belong only to that call and are not stored in the shared Bootstrap. The default SslContext is created during init and reused; a user-supplied SslContext reference is saved at build time.

A client does not store a single target address. A normally submitted connect reports exactly one result to ConnectCallback through a Netty Promise. Invalid arguments, calls before initialization or after destruction, and EventLoop submission rejection throw synchronously. The component adds neither a connection count limit nor an overall connection timer. Configure TCP timeouts through ChannelOption.CONNECT_TIMEOUT_MILLIS, and DNS, TLS, and WS timeouts through the corresponding native Netty APIs. WS/WSS success waits for the native handshake. WSS verifies certificates and hostnames by default; configure a custom trust chain through WsNetworkClient.Builder.sslContext.

Success also requires NetworkHandler.onConnected to return normally. If it throws, the client reports the original exception through ConnectCallback.onFailure and closes the connection; the server logs the initialization failure and closes the connection. Failed initialization delivers no application messages and does not call the application's onDisconnected. Native channelInactive still propagates through the Pipeline.

Callbacks for normally registered connections run on the network EventLoop. For TCP failures before registration, such as Channel creation failures, the failure notification uses the executor of Netty's native Future. Callbacks should avoid blocking. Do not call Server.start, Client.init, Server.stop, Client.destroy, or resources.close from their owning network threads.

## Connection and the native Pipeline

Connection.type() returns ConnectionType.TCP, WS, or WSS. The type is fixed at creation and does not change with handshake state. HTTP uses native handlers and does not create a Connection. NettyConnection holds a Channel and an immutable ConnectionType. id() directly returns a String from channel.id().asLongText(). State, remote address, attributes, and close delegate to native methods. write directly calls writeAndFlush without maintaining a connection state machine, write counters, locks, or message queues.

A false return from write means the Channel was already inactive before the call and the message was not taken over. A true return means the message was passed to the native write path; it does not mean the peer received it, nor does it guarantee atomic admission against a concurrent close. To obtain the native write result, use NettyAccess.channel(connection).writeAndFlush(message), which returns a ChannelFuture. Choose only one write path to avoid transferring the same message reference twice.

NetworkHandlerBridge is an ordinary SimpleChannelInboundHandler in the Pipeline. It invokes the user's NetworkHandler directly and follows native serial event propagation. Reference-counted arguments to onMessage are borrowed references, released by native automatic release after the callback returns. Echo example:

~~~java
public void onMessage(Connection connection, Object message) {
    ReferenceCountUtil.retain(message);
    if (!connection.write(message)) ReferenceCountUtil.release(message);
}
~~~

Users supply application protocol decoders and encoders. The component does not automatically encode String, byte[], or application objects, and assumes no TCP message boundaries. Without an application decoder, TCP delivers ByteBuf chunks and WS/WSS delivers native frames.

Inbound order is shown below; outbound messages traverse encoders in the reverse Pipeline direction:

~~~
TCP: user Decoder / Encoder → NetworkHandlerBridge → NetworkHandler
WS/WSS: TLS (WSS) → native HTTP / WebSocket handlers → user Decoder / Encoder → bridge
~~~

For example, a TCP application pipeline with a length field:

~~~java
.pipeline((connection, pipeline) -> pipeline
    .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4))
    .addLast("frameEncoder", new LengthFieldPrepender(4))
    .addLast("messageDecoder", new GameMessageDecoder())
    .addLast("messageEncoder", new GameMessageEncoder()))
~~~

GameMessageDecoder and GameMessageEncoder are native Netty handlers implemented by the application. NetworkHandler.onMessage then receives application objects. Writing an application object with connection.write first passes it through GameMessageEncoder to produce a ByteBuf, then through LengthFieldPrepender to add the length. A WS application encoder should produce WebSocketFrame objects, and its decoder should parse application objects from frames. WS frame aggregation is disabled by default; explicitly set WS_AGGREGATION or install an aggregator when fragmented frames need aggregation.

The pipeline configuration adds native handlers directly, and the component appends the network.handler bridge. Modify the pipeline at runtime through the native ChannelPipeline. NettyAccess.editPipeline is only a convenience wrapper around EventLoop.submit; it does not protect pipeline nodes or replay events consumed by user handlers.

The five concrete server/client classes implement the public interfaces directly and each use Bootstrap and ChannelGroup. There is no ComponentSupport, common lifecycle superclass, background shutdown task, connection counter, or separate termination Future.

Each concrete class initializes its native pipeline through its own private initPipeline method; ProtocolSupport and install entry points have been removed. TCP configures user handlers and the bridge. WS/WSS configures protocol handlers directly in the corresponding class, and HttpNetworkServer handles HTTP configuration similarly. Users can still insert handlers before or after codecs with native addBefore / addAfter.

Connection.close delegates directly to native Channel.close, including native TLS/WS handling. Server.stop and Client.destroy wait directly for ChannelGroup closure before releasing owned resources. STOP_TIMEOUT and DESTROY_TIMEOUT control these waits; after a timeout, call again to continue waiting for closure. They do not additionally wait for application callbacks to return. Connection tasks still queued on an EventLoop report that the client has been destroyed; with shared resources, callbacks may occur after destroy returns. Channel attributes are not automatically cleared after closure, and boundAddresses retains successfully bound addresses for lookup.

## Configuration

On servers, option(ChannelOption, value) configures listening Channels and childOption configures accepted Channels. On clients, option configures connections. Unspecified options retain native Netty defaults. The component adds no inbound/outbound backlog limits or size calculation interfaces.

Select component behavior through option(NetworkOptions.KEY, value); see the [implementation record](docs/implementation-status.md) for defaults. WS builders provide webSocketServer / webSocketClient and sslContext / tlsHandler. The HTTP builder provides contextPath, httpPipeline, httpDecoder, and httpAggregation (0 enables streaming). Setting the same configuration entry again replaces its previous value. The configuration snapshot is fixed after build.

Internal Settings stores only read-only ChannelOption / NetworkOption snapshots and reads defaults. The concrete TCP/WS classes own user handlers and pipeline configuration. TLS and WS protocol customizers belong only to their corresponding WS classes and builders, while HTTP retains its own configuration. Each common builder superclass has a separate file and shares only common configuration, keeping protocol-specific fields out of the common options layer.

Component options are checked for applicability at build time. For example, setting WS_AGGREGATION on TCP or START_TIMEOUT on a client fails immediately. Native ChannelOption behavior is left to Netty.

| Component | Supported NetworkOptions |
|---|---|
| TCP Server | READ_IDLE / WRITE_IDLE / ALL_IDLE, START_TIMEOUT, STOP_TIMEOUT |
| WS Server | TCP Server options, plus WS_AGGREGATION / WS_UPGRADE_AGGREGATION |
| HTTP Server | START_TIMEOUT, STOP_TIMEOUT |
| TCP Client | READ_IDLE / WRITE_IDLE / ALL_IDLE, DESTROY_TIMEOUT |
| WS Client | TCP Client options, plus WS_AGGREGATION / WS_UPGRADE_AGGREGATION |

STOP_TIMEOUT / DESTROY_TIMEOUT control the wait for each component's ChannelGroup to close. NetworkResources.Builder.shutdownTimeout controls the subsequent wait for owned EventLoopGroup termination, with a default of 5 seconds. These waits are timed separately. resources.close(Duration) overrides the timeout for a particular resource closure; close can be called again after a timeout. NetworkResources does not close borrowed EventLoopGroup instances.

## Build and validation

Use JDK 25 and Maven 3.9. From the game-network project directory:

~~~sh
mvn verify
~~~

If your local mirror is unavailable, use the project's Maven Central settings and local cache:

~~~sh
mvn -s .mvn/settings.xml -gs .mvn/settings.xml "-Dmaven.repo.local=.m2" verify
~~~

JARs are generated under each module's target directory. Test sources cover native delegation, protocol integration, Bootstrap reuse, and isolation between concurrent connections to multiple targets. The default verify runs functional regression tests. See [operations and validation](docs/operations.md) for standalone capacity tests and the Docker Linux entry point, and the [implementation record](docs/implementation-status.md) for environments and measurements.

Windows test processes use the module target directory for JDK local socket temporary files, without changing global properties in the component runtime. See [JDK networking properties](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/doc-files/net-properties.html) for the relevant properties.

- [Game Network Specification](docs/Game%20Network%20Specification.md): merged Draft 0.7, currently the sole language-independent general specification.
- [Java / Netty implementation design](docs/java-netty/implementation-design.md): concrete classes and native adaptation design.
- [Implementation and validation record](docs/implementation-status.md): defaults and validation scope.
- [Integration and operations](docs/operations.md): native monitoring handlers, DNS TCP fallback, Windows / Linux CI, Docker, and capacity test commands.
- [Linux validation report](docs/linux-validation-2026-09-06.md): 37 functional tests across two platforms and short capacity runs with 10,000 TCP, 2,000 WS, and 1,000 WSS connections.

The component currently does not provide automatic reconnection, RPC, player sessions, asynchronous NetworkHandler, custom drain modes, UDP/KCP/QUIC/HTTP2, or HTTP and WS routing on a shared port.
