English | [中文](README.zh-CN.md)

# managame-network

A general-purpose game server network component on Netty 4.2. It provides **TCP, WebSocket (ws) and secure
WebSocket (wss)** servers (TLS over TCP as well), an **HTTP/1.1 (and HTTPS)** server for a game server's internal
use, and a reconnecting **TCP client**. Every connection is a `Connection`; the application receives its lifecycle
and messages through a `ConnectionHandler`, and the Netty pipeline in between accepts any `ChannelHandler` to
add, reuse or customize.

## Quick start

Two steps: a `ConnectionInitializer` describes the pipeline of each connection, and a `NetworkServer` listens on
a port.

```java
// TCP: 4-byte length-prefixed framing plus your own codec
ConnectionInitializer tcp = ConnectionInitializer.tcp()
        .addLast(NetworkHandlers.lengthFieldFraming(1 << 20))
        .addLast("codec", MyCodec::new)
        .handler(gameHandler)
        .build();

// WebSocket: binary frames; each message reaches the following handlers as a ByteBuf
ConnectionInitializer ws = ConnectionInitializer.ws("/game")
        .addLast("codec", MyCodec::new)
        .handler(gameHandler)
        .build();

// WSS: certificates from PEM (PKCS#8 private key) or PKCS#12 / JKS
SslContext ssl = NetworkSsl.server(Path.of("server.p12"), "changeit".toCharArray());
ConnectionInitializer wss = ConnectionInitializer.wss("/game", ssl)
        .addLast("codec", MyCodec::new)
        .handler(gameHandler)
        .build();

// Listen: with its own threads (1 boss, Netty's default number of workers, TCP_NODELAY on)
NetworkServer tcpServer = NetworkServer.start(9000, tcp);
NetworkServer wssServer = NetworkServer.start(new InetSocketAddress("0.0.0.0", 9443), wss);

// Close: idempotent; stops listening. Accepted connections belong to the application, see "Threads and resources"
wssServer.close();
```

A `ConnectionInitializer` is a plain Netty `ChannelInitializer` holding only configuration. It can serve several
ports at once, be given to the application's own `ServerBootstrap.childHandler(...)`, or be tested with an
`EmbeddedChannel` without opening a socket.

Port 0 binds a random port, and `server.port()` returns the actual one; an `InetSocketAddress` picks the network
interface.

## Pipeline layout and handler customization

Each connection's pipeline has this fixed order (names in `PipelineNames`):

```
addFront… | ssl | http-codec, http-aggregator, ws-path, [ws-compression], ws-protocol, ws-aggregator,
ws-messages | addLast… | customize | connection → ConnectionHandler
```

| Method | Position | For |
| --- | --- | --- |
| `addFront(name, …)` | Before TLS / WebSocket, sees raw bytes | IP filtering, HAProxy protocol, traffic shaping, raw byte logging |
| `addLast(name, …)` | After TLS / WebSocket, before `connection` | Framing, codecs, heartbeat / idle detection, authentication |
| `customize(pipeline -> …)` | After every handler is installed, before `connection` | Insert around, replace or remove built-in handlers by their `PipelineNames` |

Each section keeps declaration order. A handler is given in one of two ways:

- **Shared instance**: `addLast("log", new LoggingHandler())`. It must be `@Sharable` and serves every
  connection; a non-`@Sharable` instance throws `IllegalArgumentException`.
- **Factory**: `addLast("frame", () -> new LengthFieldBasedFrameDecoder(...))` creates one per connection, for
  stateful handlers such as decoders.

### Reuse: HandlerChain

A `HandlerChain` is an ordered, named, immutable list of handler definitions. It can be added to any number of
`ConnectionInitializer`s, or to your own bootstrap's pipeline (a test client, for example):

```java
HandlerChain protocol = HandlerChain.builder()
        .addLast("decoder", MyDecoder::new)          // one per connection
        .addLast("encoder", MyEncoder.INSTANCE)      // @Sharable, shared
        .build();

ConnectionInitializer.tcp().addLast(NetworkHandlers.lengthFieldFraming(1 << 20)).addLast(protocol)...
ConnectionInitializer.ws("/game").addLast(protocol)...  // WebSocket frames messages itself, no length prefix
protocol.addTo(clientChannel.pipeline());               // a client reuses the same definitions
```

Built-in reusable chains (`NetworkHandlers`):

- `lengthFieldFraming(maxFrameLength)`: splits and frames messages with a 4-byte big-endian length prefix that
  excludes itself; names `frame-decoder` / `frame-encoder`.
- `readTimeout(Duration)`: closes a connection that receives nothing for that long (a heartbeat timeout); name
  `read-timeout`.

Handler names are unique within one `ConnectionInitializer`, and the built-in names in `PipelineNames` are
reserved.

## WebSocket

`WebSocketConfig` sets the path and the message form:

```java
WebSocketConfig config = WebSocketConfig.of("/game")
        .withMessages(WebSocketMessages.TEXT)   // BINARY (default) / TEXT / FRAMES
        .withMaxMessageSize(256 * 1024)          // 1 MiB by default; larger messages close with 1009
        .withSubprotocols("game.v1")
        .withCompression(true);                  // permessage-deflate
ConnectionInitializer.ws(config)...
```

- **BINARY / TEXT**: accept only data frames of that type. The payload reaches the `addLast` section as a
  `ByteBuf`, and a written `ByteBuf` is wrapped in a frame of that type, so TCP and WebSocket can share the same
  codec handlers. A frame of the other type closes the connection with 1003.
- **FRAMES**: no conversion; handlers read and write `WebSocketFrame`s.
- In every mode a written `WebSocketFrame` is sent as is.
- The path matches exactly (a query string is allowed); other paths get 404. The handshake request (URI, headers,
  subprotocol) is stored in `connection.get(NetworkAttributes.WEBSOCKET_HANDSHAKE)` before `onActive`, for
  reading a token or the real IP forwarded by a proxy.
- To refuse a connection during the handshake, `addLast` a handler for the `HandshakeComplete` user event that
  calls `ctx.close()` when validation fails; that connection never reaches `onActive`.
- Establishment is detected from Netty user events (`SslHandshakeCompletionEvent`, `HandshakeComplete`) reaching
  the `connection` handler at the end of the pipeline, so a custom handler must pass on the user events it does
  not handle with `ctx.fireUserEventTriggered(event)` (`ChannelInboundHandlerAdapter` does by default), or
  `onActive` never fires.

## Mapping channels to connections

How a Connection is created and found is decided by a `ConnectionMapper`; the framework keeps no Connection
itself:

- `create(channel)`: called once per channel when it is established; the result goes to `onActive`. It may build
  a new object or return an existing one (resuming a session after a reconnect, say). Throwing or returning null
  closes the connection without `onActive`.
- `get(channel)`: every later callback (`onRead` / `onEvent` / `onException` / `onInActive`) gets its Connection
  through it, so the mapping may change while the connection lives. Null skips that callback and releases the
  message; the connection stays open.
- `remove(channel)`: called right after `onInActive`, and must be implemented. Afterwards `get` should return
  null, so that nothing follows `onInActive`.

All three run on the channel's EventLoop. The framework keeps no connection state at all; whether a connection is
established or gone is whatever `get` returns: before `create` and after `remove` there is nothing to call back,
and an exception at such a time closes the channel directly.

The default `ConnectionMapper.attribute()` creates a `NettyConnection` per channel and stores it in
`NetworkAttributes.CONNECTION` (cleared after `onInActive`); a custom Netty handler can read it with
`ctx.channel().attr(NetworkAttributes.CONNECTION).get()`.

A custom mapping with your own session type, kept in your own table:

```java
class GameSession extends NettyConnection {          // NettyConnection can be extended; channel() gives the Channel
    long playerId;
    GameSession(Channel channel) { super(channel); }
}

class SessionTable implements ConnectionMapper {
    final Map<ChannelId, GameSession> sessions = new ConcurrentHashMap<>();
    public Connection create(Channel ch) { GameSession s = new GameSession(ch); sessions.put(ch.id(), s); return s; }
    public Connection get(Channel ch) { return sessions.get(ch.id()); }
    public void remove(Channel ch) { sessions.remove(ch.id()); }
}

ConnectionInitializer.tcp().connectionMapper(new SessionTable()).handler(gameHandler)...
```

You can also skip `NettyConnection` and implement `Connection` yourself.

## ConnectionHandler semantics

- All connections of one `ConnectionInitializer` share one `ConnectionHandler` instance; per-connection state
  goes in `Connection` attributes or your own Connection type. The Connection a callback receives is what the
  `ConnectionMapper` returned at that moment.
- Callbacks of one connection run in order on its EventLoop and must not block.
- `onActive`: after the TCP accept, the TLS handshake and the WebSocket handshake, as configured; a connection
  whose handshake fails never reaches the `ConnectionHandler`. `handshakeTimeout` (10 seconds by default) sets
  Netty's own TLS and WebSocket handshake timeouts. The WebSocket one starts only when the upgrade request
  arrives, so a client that connects and sends nothing is closed only by `NetworkHandlers.readTimeout(...)`,
  which production servers should configure.
- `onRead`: the message is released after the callback returns; `retain()` a `ByteBuf` you keep for later.
- `onInActive`: at most once for every connection that saw `onActive`; skipped only when the mapping no longer
  returns a Connection.
- `onEvent`: user events from the pipeline (such as `IdleStateEvent`), excluding TLS / WebSocket handshake
  events.
- `onException`: exceptions after `onActive`, including those thrown by the other callbacks. The connection is
  not closed automatically; the application decides.
- `Connection.write` returns `ACCEPTED` / `INACTIVE` / `NOT_WRITABLE` (over the write buffer's high-water mark)
  and never blocks.

## HTTP server

An HTTP/1.1 server for a game server's internal use, such as GM endpoints, payment callbacks and health checks.
As with long-lived connections there are two steps: an `HttpInitializer` describes the pipeline and a
`NetworkServer` listens.

```java
HttpInitializer admin = HttpInitializer.http()                // HTTPS: HttpInitializer.https(sslContext)
        .contextPath("/admin")                                 // only requests below /admin; others get 404
        .maxContentLength(256 * 1024)                          // body limit, 1 MiB by default; larger gets 413 and closes
        .addFront("ip", new RuleBasedIpFilter(...))            // raw byte level: IP allow lists and the like
        .handler(exchange -> {
            if (exchange.path().equals("/gm/kick")) {          // for /admin/gm/kick?id=1, path() is /gm/kick
                String body = exchange.request().content().toString(UTF_8);  // released after handle returns: copy what you need
                gameExecutor.execute(() -> exchange.respond(json(OK, kick(body))));   // answer later, from any thread
            } else {
                exchange.respond(HttpResponseStatus.NOT_FOUND);
            }
        })
        .build();
NetworkServer http = NetworkServer.start(8080, admin);
```

- `HttpHandler.handle(exchange)` runs on the connection's EventLoop and must not block. An `HttpExchange` is one
  request and its response:
  - `request()`: the aggregated request (`FullHttpRequest`), released after `handle` returns; `retain()` it or
    copy the data before using it asynchronously;
  - `path()`: the path without the context path and query, still encoded; with context path `/admin`,
    `/admin/users?id=1` gives `/users` and `/admin` itself gives `/`;
  - `respond(...)`: answers, from any thread; a second answer returns false. `Content-Length` is added from the
    content when missing (except for 204 / 304);
  - `remoteAddress()`: the peer's address.
- `contextPath` is the root by default, and a trailing `/` is ignored. Requests outside it get 404 without
  reaching the `HttpHandler`. Handlers in the `addLast` section are not limited by it and see every request.
- Every request must be answered exactly once; an exception from `handle` answers 500.
- Responses go out strictly in request order: once a request is read, the connection stops reading until its
  final response is written (interim responses such as 100 Continue do not count). Asynchronous answers and
  pipelined requests therefore never reorder.
- Keep-alive is handled by Netty's `HttpServerKeepAliveHandler` from the `Connection` headers of requests and
  responses; HTTP/1.0 is supported too.
- Handlers in the `addLast` section see `FullHttpRequest`s and may answer some themselves (a health check, say),
  but must write one final response for every request they consume, or the connection never reads the next one.
- HTTP/2 is not supported: internal services rarely gain from multiplexing, while h2c's two ways of starting plus
  ALPN over TLS would turn one protocol entry into three. The request handling interface does not depend on the
  protocol, so it can be added later without changing application code.

Pipeline layout, see `PipelineNames`:

```
addFront… | [ssl] | http-codec, http-flow, http-exchange, http-keep-alive, http-aggregator | addLast… | http-handler
```

## Client

A `NetworkClient` keeps a connection to one remote address up: it connects at once, and whenever connecting
fails or the connection closes it connects again, waiting twice as long after each failure up to a maximum, and
from the minimum again after a success. What each connection does is the handler you give it, normally a
`ConnectionInitializer`, so client connections follow the same `ConnectionHandler` semantics as server ones:
`onActive` on every connect, `onInActive` when it drops.

```java
ConnectionInitializer upstream = ConnectionInitializer.tcp()
        .addLast(NetworkHandlers.lengthFieldFraming(1 << 20))
        .addLast("codec", MyCodec::new)
        .handler(upstreamHandler)
        .build();
NetworkClient client = NetworkClient.connect(new InetSocketAddress("10.0.0.2", 7001), upstream);

client.isConnected();   // whether the connection is up now
client.close();         // stops reconnecting and closes the connection
```

- Retry delays run from 100 ms to 10 seconds by default; `connect(bootstrap, address, minRetry, maxRetry)`
  changes them.
- Your own `Bootstrap` sets options and threads. As with the server, only what it leaves unset is filled in, on a
  copy: one EventLoop of the client's own when there is no group (shut down on `close()`), the channel type
  matching the group, and `TCP_NODELAY`. One bootstrap can start several clients, for example several connections
  to the same address.
- The client keeps its current channel only to close it; the connection itself goes to the application through
  the `ConnectionHandler`.
- `ConnectionInitializer` implements only the server side of TLS and WebSocket. For a TLS or WebSocket client,
  pass your own `ChannelInitializer` (Netty's client `SslContext`, `WebSocketClientProtocolHandler` and so on).
- `close()` must not be called on the client's own EventLoop.

## Threads and resources

- The framework does not track accepted connections. The application keeps and closes them, for example
  registering them in `onActive`, removing them in `onInActive`, and closing what is left in its own table at
  shutdown.
- Every setting of a listening port (socket options, threads, channel type) uses Netty's own `ServerBootstrap`
  API, without a wrapper. `NetworkServer.start(bootstrap, address)` fills in only what the bootstrap leaves unset,
  on a copy, so the bootstrap you pass is never modified and can be reused:

  | Left unset | Filled in |
  | --- | --- |
  | Threads | 1 boss thread and Netty's default number (CPU × 2) of worker threads of its own, shut down by `close()` |
  | Channel type | Matching the threads: epoll when available, NIO otherwise |
  | `TCP_NODELAY` | `true` |

  ```java
  // Options only; the framework still creates and shuts down the threads
  NetworkServer server = NetworkServer.start(new ServerBootstrap()
          .option(ChannelOption.SO_BACKLOG, 1024)
          .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32 * 1024, 64 * 1024))
          .childHandler(game), new InetSocketAddress(9001));

  // Several ports (say TCP and WS together) sharing the application's threads
  EventLoopGroup boss = NetworkTransports.newEventLoopGroup(1, "net-boss");
  EventLoopGroup worker = NetworkTransports.newEventLoopGroup(8, "net-worker");
  ServerBootstrap shared = new ServerBootstrap().group(boss, worker).option(ChannelOption.SO_BACKLOG, 1024);
  NetworkServer tcpServer = NetworkServer.start(shared.clone().childHandler(tcp), new InetSocketAddress(9000));
  NetworkServer wsServer = NetworkServer.start(shared.clone().childHandler(ws), new InetSocketAddress(9001));
  ```

  `NetworkServer.start(port, initializer)` is the same as a bootstrap with only the child handler set, everything
  else at defaults.
- Threads the framework created are shut down by `close()`, which also closes the connections still on them;
  their `onInActive` has run when `close()` returns. With the application's threads, `server.close()` closes only
  the listening port: neither the threads nor the accepted connections, which the application closes.
- A `NetworkServer` is bound when `start` returns; there is no "not started" state. A bind failure throws
  `NetworkException` (and shuts down threads the framework created).
- `close()` must not be called on one of the server's EventLoops: Netty refuses to wait on the listener's own
  thread, and waiting for the server's own threads to stop from one of them would deadlock, so the framework
  refuses.

## Current scope

Implemented: TCP / TLS over TCP / ws / wss servers, HTTP/1.1 / HTTPS server, reconnecting TCP client. Not
included: HTTP/2, ready-made TLS and WebSocket clients, connection count and rate limits; these can be plugged in
with existing Netty handlers through `addFront` / `addLast` or your own `ChannelInitializer`.

Tests under `src/test/java/cn/managame/network` use real loopback connections to cover echo, lifecycle order,
handshake timeouts, 404 for other paths, wrong frame types, oversized messages, TLS failures, handler reuse and
validation and custom Connection mappings, and use an `EmbeddedChannel` to show a `ConnectionInitializer` works
without a socket. HTTP tests use the JDK's `HttpClient` and raw sockets for synchronous and asynchronous answers,
500, 413, pipelining order, a front handler answering itself, and HTTPS. Client tests cover a server that starts
late, reconnecting after the connection drops, and closing a client that never connected.

## Build

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

Tests open real loopback connections, so run them outside a sandbox; the `argLine` gives the JDK's Unix-domain
selector a writable directory.
