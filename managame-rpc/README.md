English | [中文](README.zh-CN.md)

# managame-rpc

Calls and notifies between the processes of a game server cluster. It stands on its own, on managame-network and
managame-common: what a message means, how it is encoded and on which thread it is handled are up to the
application.

- **`RpcNode`** is this process. Any node may dial others; a node that listens may also be dialed. Between two
  nodes only one side dials. A node that does not listen only dials.
- **`RpcPeer`** is another node, known by its id, whichever side dialed. Once connected, both sides call and notify
  each other alike over the same connections. A peer may have several connections.

## Quick start

Node 1, a world server, listens; node 2, a game server, does not listen and dials it.

```java
// Node 1
RpcNode world = RpcNode.builder()
        .nodeId(1).listen(7001)
        .handler((rpcPeer, connection, msg) -> {
            if (msg instanceof RpcRequest request && request.command() == RANK_OF)
                RpcResponse.of(request.requestId(), rankOf(request.body())).writeTo(connection);
        })
        .start();

// Node 2
RpcNode game = RpcNode.builder().nodeId(2).start();
RpcPeer worldPeer = game.createPeer(1, new InetSocketAddress("10.0.0.1", 7001));
worldPeer.call(RpcRequest.of(RANK_OF, playerId, body), (request, response) -> {
    if (response.isSuccess()) show(request.routeKey(), response.body());
    else log.warn("rank failed: " + response.code());
});
```

Node 1 can call node 2 back over the connection node 2 dialed: `world.peer(2).notify(RpcNotify.of(MAIL, playerId, body))`.

## Messages

| Record | Fields |
| --- | --- |
| `RpcRequest` | `requestId`, `deadline`, `command`, `routeKey`, `businessType`, `businessId`, `metadata`, `body` |
| `RpcNotify` | `command`, `routeKey`, `businessType`, `businessId`, `metadata`, `body` |
| `RpcResponse` | `requestId`, `code`, `metadata`, `body` |

- Every field but `requestId`, `deadline` and `code` is the application's and travels as is. `command` says what
  is asked, `businessType` and `businessId` whom it is about, `metadata` is common's `Metadata` (a trace id, the
  caller's identity, and so on). The node itself reads only `routeKey`, to pick a connection.
- `requestId` and `deadline` are the calling node's: `RpcRequest.of(...)` leaves them 0 and the call assigns both,
  replacing whatever the request carried. The callee answers with an `RpcResponse` under the same id.
- `deadline` is a `System.nanoTime()` value of the process holding the request: when the caller stops waiting. It
  travels as the time left, so clocks need not agree. `request.isExpired()` tells the callee the answer is no
  longer awaited, for example before running a request that waited in a queue.
- `RpcResponse.of(requestId, [metadata,] body)` is a success (code 0), `RpcResponse.failure(requestId, code)` a
  failure with a positive code. `response.writeTo(connection)` sends it (see [Handling messages](#handling-messages)).
- The body is a Netty `ByteBuf`, and every record is reference-counted through it, like Netty's own messages.

## Calling

| Method | Answer |
| --- | --- |
| `peer.call(request, callback)` | `callback.onResponse(request, response)`, exactly once, within the node's timeout |
| `peer.call(request, timeout, callback)` | The same, within `timeout` |
| `peer.notify(notify)` | None; false when it could not be sent |
| `node.broadcast(peers, notify)` | A notify to each peer, sharing one body; returns how many it was sent to |

- **Order.** A call or notify takes the connection its `routeKey` picks, so messages with one key reach the peer in
  the order they were sent, as long as the peer's connections do not change. Use, say, a player id to keep one
  player's messages in order.
- **The callback** gets the request it answers, with the id and deadline the call assigned but without its body, so
  the application can carry on by the request's command, route key and business, for example on the executor that
  key maps to. It must not block; an exception it throws is logged.
- **Waiting calls are the peer's.** Each remembers the connection it went out on. An answer settles it, and so do
  its timeout, the close of that connection, the removal of the peer and the close of the node.
- Nothing about the network throws. The callback receives the callee's answer, which may carry a failure code of
  the callee's, or one of these:

| Code | When |
| --- | --- |
| `RPC_UNAVAILABLE` | The peer has no connection up or is closing, the connection is too busy to take the request, or it closed before the answer came |
| `RPC_TIMEOUT` | No answer within the timeout (5 seconds by default) |
| `RPC_PROTOCOL_ERROR` | The request, or its answer, is larger than the largest frame |
| `RPC_PEER_REMOVED` | The peer was removed, before or during the call |
| `RPC_NODE_CLOSED` | This node was closed, before or during the call |
| `RPC_HANDLER_ERROR` | The callee's handler threw |
| `HANDLER_NOT_FOUND` | The callee has no handler |

**A failed call may still have been carried out.** With `RPC_UNAVAILABLE` or `RPC_TIMEOUT` the request may have
reached the callee and run there. Design operations between servers to be idempotent, for example by carrying an
operation id in the metadata that the callee remembers, before retrying them.

## Handling messages

The node hands every request and notify a peer sends to the application's one `RpcHandler`:

```java
RpcHandler handler = (rpcPeer, connection, msg) -> {
    switch (msg) {
        case RpcRequest request -> {
            Object query = protocol.decode(request.command(), request.body());   // decoded here
            executor.execute(() -> {                                              // answered later, from another thread
                if (request.isExpired()) return;                                  // the caller has given up
                RpcResponse.of(request.requestId(), protocol.encode(handle(query))).writeTo(connection);
            });
        }
        case RpcNotify notify -> handle(rpcPeer, protocol.decode(notify.command(), notify.body()));
        default -> {}
    }
};
```

- `rpcPeer` is the node that sent the message, for calling it back. `connection` is network's `Connection` it came
  on. A request is answered with an `RpcResponse` under its `requestId`, written to that connection now or later,
  from any thread. Only the first answer counts; the caller drops later ones.
- **`response.writeTo(connection)`** writes the answer even when the connection is busy, since the work behind it
  is done and dropping it would leave the caller unsure. It releases the response when the connection has closed.
  A plain `connection.write(response)` works too, but it refuses a busy connection and leaves the release to you.
- An answer larger than the largest frame goes out as `RPC_PROTOCOL_ERROR` instead, so the connection survives.
- A request that has already expired when it arrives is dropped without reaching the handler.
- The handler runs on the connection's EventLoop, in the order messages arrived on it, and must not block.
- An exception from the handler fails a request with `RPC_HANDLER_ERROR`, unless it was answered already; for a
  notify it is logged. A node built without a handler fails requests with `HANDLER_NOT_FOUND` and drops notifies.

## Reference counting

Bodies are Netty `ByteBuf`s and are never copied on the way. The records follow Netty's rules:

1. **What you hand to the node becomes the node's.** That covers a request passed to `call`, a notify passed to
   `notify` or `broadcast`, and a response passed to `writeTo`. The node releases it once sent, and also when it
   cannot be sent or the call throws for a bad argument. Do not use it afterwards.
2. **What you write to a connection yourself becomes Netty's when the write is accepted.** A write that returns
   anything other than `ACCEPTED` stays yours to release, as in network.
3. **What the node hands you is valid while your method runs.** That covers the message in `handleMessage` and the
   response in `onResponse`. They are released when the method returns. To keep one longer, for example to use it
   on another thread, `retain()` it and `release()` it when done.
4. **The request in a callback has an empty body.** The request was released once sent; every other field is still
   there.
5. **To pass a received message on,** for example a gateway forwarding a request to the next node, `retain()` it
   first. Rules 1 and 3 each release it once.

## Peers and membership

The application drives membership from its own configuration or service discovery.

- **`node.createPeer(id, address[, connections])`** dials a node with one or more connections and returns its peer
  at once. Each connection is redialed whenever it fails or closes, from 100 ms apart, doubling up to 10 seconds by
  default (`redialDelay`).
- **Nodes dialing in** join by the id in their handshake. A peer stays known after its connections close, and the
  node dialing in again rejoins the same `RpcPeer` instance.
- **`node.removePeer(id)`** forgets a peer, dialed or dialing in: it fails the waiting calls, and later calls
  through the removed instance, with `RPC_PEER_REMOVED`, stops dialing and closes its connections. A node that
  dials in afterwards joins as a new peer.
- **`node.peer(id)`** is the peer with that id, or null; `node.peers()` is a snapshot of all of them.

### Starting up

Calls to a peer that has not connected yet fail at once with `RPC_UNAVAILABLE`; they are not queued. A node that
needs another before it can serve, such as a game server that registers with the world server before it lets
players in, starts that work from the listener:

```java
public void onConnected(RpcPeer peer, boolean restarted) {
    if (peer.nodeId() == WORLD) executor.execute(this::registerAndOpen);
}
```

### Restarts

Each running instance of a node has an **incarnation**, its start time in milliseconds times 1000 plus a random
number, sent in every handshake: `node.incarnation()` for this one, `peer.incarnation()` for the peer's.

- The listener tells a restart from a reconnect: `onConnected(peer, restarted)` has `restarted` true when the
  peer's incarnation differs from the one seen before. After a restart, whatever this node knew of the peer, such
  as the players on a game server, is gone; after a reconnect it still holds.
- **Only one instance of a peer is connected at a time.** A newer instance replaces the connected one, whose
  connections close and whose calls fail with `RPC_UNAVAILABLE`, so two processes started with the same id never
  share the traffic. An older instance dialing while a newer one is connected is refused. This relies on the
  machines' clocks roughly agreeing.

### What a node is for

The node does not know service types; that is the application's business. Each node puts what it wants peers to
know into its handshake metadata, and the application groups peers itself:

```java
static final MetadataKey<Integer> SERVICE = MetadataKey.intKey(1);

RpcNode game = RpcNode.builder().nodeId(101)
        .metadata(Metadata.builder().put(SERVICE, GAME).build())
        .start();

// On the world server: peers grouped by what they said they are.
Map<Integer, Set<RpcPeer>> byService = new ConcurrentHashMap<>();
RpcListener grouping = new RpcListener() {
    public void onConnected(RpcPeer peer, boolean restarted) {
        byService.computeIfAbsent(peer.metadata().get(SERVICE), s -> ConcurrentHashMap.newKeySet()).add(peer);
    }
    public void onDisconnected(RpcPeer peer) { byService.get(peer.metadata().get(SERVICE)).remove(peer); }
};
```

Which node of a group serves a key is the application's choice too: modulo, hashing, or a lookup such as "the
game server this player is on".

### Who dials whom

Two nodes must not dial each other; the second connection is refused. When both listen, pick a rule every node
follows, for example "the node of the lower layer dials the higher" (gateways dial game servers, game servers dial
the world server), or "the lower id dials the higher".

### The listener

`RpcListener` has three methods, each with an empty default:

| Method | When |
| --- | --- |
| `onConnected(peer, restarted)` | The peer's first connection completed its handshake |
| `onDisconnected(peer)` | Its last connection closed, also when it was removed or replaced by a newer instance |
| `onClosing(peer)` | It said it is closing: calls and notifies to it fail at once from now on; pick another node |

Calls for one peer never overlap and come in order. They run on an EventLoop and must not block.

## Closing and draining

- **`node.close(Duration drain)`** drains first, for a rolling restart or maintenance: it stops listening, says
  Goodbye to every peer, which then sends it nothing new (`peer.isClosing()`, `onClosing`), and waits until it has
  answered every request peers sent it and its own calls have settled, or `drain` has passed. Its own calls still go
  out meanwhile, so handlers can finish their work. Then it closes as below.
- **`node.close()`** closes at once: it fails every waiting call, and calls made afterwards, with
  `RPC_NODE_CLOSED`, stops listening and dialing, and closes every connection. Answers still to come no longer
  reach their callers.

Both block until done and are idempotent.

## Security

- **TLS.** `serverSsl(SslContext)` secures the connections other nodes dial in on, `clientSsl(SslContext)` the ones
  this node dials. A dialed connection checks the certificate against the host it dialed, as the client context
  is configured to. With client authentication on the server context, both sides prove who they are.
- **Admission.** An `RpcAuthenticator` decides from each Hello whether the node on the other side may join, on
  every connection in either direction; false closes the connection, and a node refused while dialing in leaves no
  peer behind. A secret in the handshake metadata only keeps out mistakes unless the connection runs over TLS.

```java
RpcNode.builder()
        .serverSsl(serverContext).clientSsl(clientContext)
        .authenticator((connection, nodeId, metadata) -> cluster.allows(nodeId, metadata.get(TOKEN)))
```

## Back pressure and frame size

- A connection may queue `DEFAULT_WRITE_BUFFER_WATER_MARK` (1 MiB low, 4 MiB high) before it counts as busy, unless
  the bootstraps set Netty's `WRITE_BUFFER_WATER_MARK`. A busy connection refuses new calls (`RPC_UNAVAILABLE`) and
  notifies (false) at once, so a slow peer cannot fill this node's memory, but takes answers through `writeTo`.
  Messages sent from other threads count by their size while they wait for the connection's EventLoop: the node
  sets Netty's `MESSAGE_SIZE_ESTIMATOR` to one that knows calls, notifies and answers, unless the bootstraps set
  one.
- A message larger than `maxFrameLength` fails alone before it is sent: the call with `RPC_PROTOCOL_ERROR`, the
  notify with false, an answer as `RPC_PROTOCOL_ERROR`. Every node of a cluster must use the same `maxFrameLength`.

## Handshake and liveness

- **Handshake.** Each side sends a Hello with the protocol versions it speaks, its node id, incarnation and
  metadata; the dialing side sends first. The two speak the highest version both do. Before the handshake
  completes, only a Hello is accepted; any other frame closes the connection. A connection is refused and closed,
  with a warning logged, when:
  - the two share no protocol version;
  - the dialed node is not the one `createPeer` named (it is then redialed);
  - the node dialing in claims this node's id, or this node dials it itself (`createPeer` for a node that dials in
    throws `IllegalStateException`);
  - this node is closing;
  - the authenticator refuses it;
  - it is an older instance of a peer while a newer one is connected.
- **Liveness.** Each side of a connection looks every third of the idle timeout at what passed since it last
  looked. Having written nothing, it sends a Ping, once it has sent its Hello. Having received nothing three times
  in a row, which is at least the idle timeout (15 seconds by default) and at most a third more, it takes the
  connection as dead and closes it, such as one to a machine that went down without closing it. Its calls fail
  with `RPC_UNAVAILABLE` at once instead of waiting for their timeout, and a connection this node dialed is
  redialed. The same rule closes a connection that never completes its handshake. A busy connection sends no
  Pings. Reads and writes are only marked, with no timestamp or Netty promise per message.

## Observability

- `peer.pendingCount()`: the calls to the peer waiting for their answer.
- `node.unansweredCount()`: the requests peers sent that this node has neither answered nor seen expire.
- `peer.connectionCount()`, `peer.isConnected()`, `peer.isClosing()`, `peer.incarnation()`, and the listener for
  connections, restarts and closing.
- An `RpcObserver` sees every call this node made end, answered or failed, with the request, the response and the
  time it took, right before the callback: enough for latency, timeout and failure metrics per peer and command,
  in whatever monitoring system the application uses.

## Threading

| What | Where it runs |
| --- | --- |
| `RpcHandler.handleMessage`, `RpcAuthenticator` | The connection's EventLoop, in arrival order |
| `RpcListener` | The EventLoop of the connection that came, went or said Goodbye |
| `RpcCallback`, `RpcObserver` | The connection's EventLoop for an answer or a closed connection; the node's timer thread for a timeout; the thread that removed the peer or closed the node; or the calling thread when the call fails before it is sent |
| `call`, `notify`, `broadcast`, `writeTo` | Any thread |
| `removePeer`, `close` | Any thread but the node's EventLoops and timer (they block until connections close; `IllegalStateException` there) |

None of the callbacks may block: business logic belongs on the application's own threads, for example its
`GameRuntime`.

## Configuration

| Builder method | Default | Meaning |
| --- | --- | --- |
| `nodeId(int)` | required | This node's id, unique in the cluster |
| `metadata(Metadata)` | empty | What this node tells every peer in the handshake |
| `listen(port)` / `listen(address)` | not listening | Lets other nodes dial this one; any address the server bootstrap's channel binds |
| `handler(RpcHandler)` | requests fail with `HANDLER_NOT_FOUND` | Handles what peers send |
| `listener(RpcListener)` | none | Hears peers connect, disconnect, restart and close |
| `authenticator(RpcAuthenticator)` | all admitted | Decides who may join |
| `observer(RpcObserver)` | none | Sees every call end |
| `serverSsl(SslContext)` / `clientSsl(SslContext)` | plain TCP | TLS for accepted and dialed connections |
| `bootstrap(Bootstrap)` / `serverBootstrap(ServerBootstrap)` | the node's own EventLoops | Netty's bootstraps for dialing and listening: EventLoops, channel type, socket options. The handlers are the node's; what is left unset the node fills in on a copy |
| `redialDelay(min, max)` | 100 ms, 10 s | How long a dialed connection waits before dialing again |
| `timeout(Duration)` | 5 seconds | How long a call waits for its answer unless it says otherwise |
| `idleTimeout(Duration)` | 15 seconds | How long a connection may receive nothing before it is closed, at most a third longer; each side looks every third of it and Pings when it wrote nothing since. The same on every node |
| `maxFrameLength(int)` | 8 MiB | Largest frame, its length field included. The same on every node |

`start()` listens when asked to and returns the running node. A bind failure throws `NetworkException` after
releasing what was started.

### Netty's own configuration

The bootstraps are Netty's, so anything Netty offers goes there: EventLoops shared with the application's other
servers, epoll or io_uring channel types, socket buffers, write water marks. EventLoops the application sets stay
its own and are not shut down on close; the node closes its connections on them. With Netty's local transport,
nodes run in one process without sockets, for example in tests:

```java
EventLoopGroup group = new MultiThreadIoEventLoopGroup(LocalIoHandler.newFactory());
RpcNode world = RpcNode.builder().nodeId(1)
        .serverBootstrap(new ServerBootstrap().group(group).channel(LocalServerChannel.class))
        .listen(new LocalAddress("world"))
        .start();
RpcNode game = RpcNode.builder().nodeId(2)
        .bootstrap(new Bootstrap().group(group).channel(LocalChannel.class))
        .start();
game.createPeer(1, new LocalAddress("world"));
```

## Wire format

Frames are length-prefixed (4 bytes, big-endian, excluding itself). Inside, big-endian, a type byte, then:

| Frame | Layout |
| --- | --- |
| Hello (0) | lowest version byte, highest version byte, nodeId int, incarnation long, metadata |
| Request (1) | requestId int, time left in ms int (0 for none), command int, routeKey long, businessType byte, businessId long, metadata, body |
| Notify (2) | command int, routeKey long, businessType byte, businessId long, metadata, body |
| Response (3) | requestId int, code int, metadata, body |
| Ping (4) | nothing |
| Goodbye (5) | nothing |

Metadata is its length as an unsigned short, then its bytes. Hello, Ping and Goodbye are the node's own and never
reach the handler; `command` is the application's alone, so the whole `int` range is its own. A later version may
append to a Hello, which this one ignores. An unknown frame type, a frame longer than `maxFrameLength` and one
cut short of its fields close the connection. This node speaks protocol version 1.

One codec per connection, after TLS and flush consolidation, does the framing both ways: an outgoing message is
a header buffer, its length included, followed by the body as it is; an incoming body is a slice of what was read.

## Build and tests

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

Install managame-common and managame-network first. The tests run nodes over real loopback connections, with
Netty's leak detection at its strictest. They cover:
- calls answered at once, later from another thread, and with a retained request;
- every field reaching the callee, the callback getting its request back, and the deadline;
- failure codes, timeouts per node and per call, and only the first answer counting;
- notifies, broadcasts, and calls and notifies both ways over the connection the dialer opened;
- ordering by route key over several connections;
- an application grouping peers by their handshake metadata;
- a restart told from a reconnect through a proxy that cuts connections, and a newer instance replacing an older;
- two nodes dialing each other, a node answering under another id, protocol versions, a frame before the Hello, a
  frame longer than the largest, a connection that never says Hello, the authenticator in both directions, and
  TLS;
- Pings keeping an idle connection up, and a silent peer being dropped with its calls failed at once;
- a peer that is unreachable until it starts;
- messages larger than a frame failing alone, and messages waiting for a held-up EventLoop making the connection
  busy by their size;
- the observer and the counts;
- draining before closing, removing peers in both directions, a peer going away, and closing the node;
- the application's own bootstraps with Netty's local transport;
- bodies released on every path, callbacks, listeners and observers that throw, and misuse.
