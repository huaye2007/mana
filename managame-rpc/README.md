English | [中文](README.zh-CN.md)

# managame-rpc

Calls and notifies between the processes of a game server cluster, over TCP. It stands on its own, on
managame-network and managame-common. What messages mean, how they are encoded and which thread handles them is
the application's.

- **`RpcNode`** is this process; a process that talks to no other needs none. Every node may dial others, and a
  node that listens may also be dialed. Between two nodes only one side dials; a node that does not listen can
  only dial.
- **`RpcPeer`** is another node, one to one with the process running it, known by its node id whichever side
  dialed. Once connected, both sides call and notify each other alike over the same connections. A peer may have
  several connections.

## Quick start

Node 1 is a world server and listens; node 2 is a game server that does not listen and dials node 1.

```java
// node 1
RpcNode world = RpcNode.builder()
        .nodeId(1).listen(7001)
        .handler((rpcPeer, connection, msg) -> {
            if (msg instanceof RpcRequest request && request.command() == RANK_OF)
                RpcResponse.of(request.requestId(), rankOf(request.body())).writeTo(connection);
        })
        .start();

// node 2
RpcNode game = RpcNode.builder().nodeId(2).start();
RpcPeer worldPeer = game.createPeer(1, new InetSocketAddress("10.0.0.1", 7001));
worldPeer.call(RpcRequest.of(RANK_OF, playerId, body), (request, response) -> {
    if (response.isSuccess()) show(request.routeKey(), response.body());
    else log.warn("rank failed: " + response.code());
});
```

Node 1 can call node 2 back over the connections node 2 dialed: `world.peer(2).notify(RpcNotify.of(MAIL, playerId, body))`.

## Messages

| Record | Fields |
| --- | --- |
| `RpcRequest` | `requestId`, `deadline`, `command`, `routeKey`, `businessType`, `businessId`, `metadata`, `body` |
| `RpcNotify` | `command`, `routeKey`, `businessType`, `businessId`, `metadata`, `body` |
| `RpcResponse` | `requestId`, `code`, `metadata`, `body` |

- Every field but `requestId`, `deadline` and `code` is the application's and travels as is: `command` says what
  is asked, `businessType` and `businessId` whom it is about, `metadata` (common's `Metadata`) anything else, such
  as a trace id or the caller's identity. The node itself reads only `routeKey`, to pick the connection.
- `requestId` and `deadline` are the calling node's. `RpcRequest.of(...)` leaves them 0 and the call assigns
  both. The callee answers with an `RpcResponse` under the same id.
- `deadline` is a `System.nanoTime()` value of the process holding the request, when the caller stops waiting.
  It travels as the time left, so the two clocks need not agree. A request that arrives expired is dropped; the
  callee can check `request.isExpired()` before running a queued request.
- `RpcResponse.of(requestId, [metadata,] body)` is a success (code 0); `RpcResponse.failure(requestId, code)` a
  failure, with a positive code.
- Bodies are Netty `ByteBuf`s; the three records are reference-counted through them, like Netty's own messages.

## Calls

| Method | Outcome |
| --- | --- |
| `peer.call(request, callback)` | `callback.onResponse(request, response)` exactly once, within the node's timeout |
| `peer.call(request, timeout, callback)` | the same, within `timeout` |
| `peer.notify(notify)` | no answer; returns network's `WriteStatus`: `ACCEPTED`, `NOT_WRITABLE` when the connection is busy, `INACTIVE` when no connection is up, the peer was removed or the node closed |
| `node.broadcast(peers, notify)` | one notify to each peer, all sharing one body; returns how many accepted it |

### Which connection

A peer has as many connections as the dialing side opens, each in a slot of its own; both sides know every
connection's slot from its handshake.

1. A call or notify goes on the connection of the slot `floorMod(routeKey, connections)`. As long as it is up,
   messages with one key take it, so they reach the peer in the order they were sent: with the player id as the
   key, one player's messages keep their order.
2. While that connection is down, its keys go on the next slot's connection that is up. Only the keys of the lost
   slot move. When the slot comes back its keys return, and at that moment a message on the stand-in connection
   may still be overtaken by one on the slot's own.
3. With no connection up, a call fails with `RPC_UNAVAILABLE` and a notify returns `INACTIVE`.

A busy connection does not hand its keys to another, which would reorder them each time it becomes busy: a call
fails with `RPC_UNAVAILABLE` and a notify returns `NOT_WRITABLE`.

### Callbacks

- The callback gets back the request it answers, with the id and deadline the call assigned but without its body.
  Its command says how to decode the answer, and its route key, business and metadata are what the application
  builds its context from, without capturing anything. Decode in the callback, then hand the work to the thread
  the application picks:

  ```java
  peer.call(RpcRequest.of(RANK_OF, playerId, body), (request, response) -> {
      Object rank = response.isSuccess() ? protocol.decode(request.command(), response.body()) : null;
      executorOf(request.routeKey()).execute(() -> showRank(request.routeKey(), rank, response.code()));
  });
  ```

  An application usually writes such an adapter once and passes it to every call; it is also where to measure
  calls, as it is made when the call starts.
- A callback never runs on the calling thread inside `call`, not even for a call that fails before it is sent:
  that one runs on one of the node's dialing EventLoops, picked by the route key. It must not block, and an
  exception it throws is only logged.
- **Waiting calls belong to the peer**, under request ids of its own, not to any connection. An answer ends its
  call on whichever connection of the instance it comes, so a call outlives the connection it went out on: when
  that connection drops while others stay up, the callee's answer takes one of them. **When the peer's last
  connection closes**, as when its process crashed or stopped, the waiting calls fail at once with
  `RPC_UNAVAILABLE` rather than wait out their timeouts: answers written while no connection is up are lost
  anyway. A call ends when its answer arrives, when it times out, when the peer's last connection closes, when a
  new instance of the peer replaces the one asked, or when the peer is removed or this node closed.
- Timeouts fire on the node's time wheel, which ticks every 10 ms, so a call times out up to a tick late.
- Nothing about the network throws. The callback receives the callee's answer, which may carry a failure code of
  the callee's, or one of these:

| Code | When |
| --- | --- |
| `RPC_UNAVAILABLE` | No connection is up, the connection is too busy, `maxPendingCalls` calls wait already, the peer's last connection closed while the call waited, a new instance of the peer replaced the one asked, or the peer was removed or this node closed |
| `RPC_TIMEOUT` | No answer within the timeout (5 seconds by default), also when the request or its answer was lost with a connection that closed while another stayed up |
| `RPC_PROTOCOL_ERROR` | The answer was larger than the callee's largest frame |
| `RPC_HANDLER_ERROR` | The callee's handler threw |
| `HANDLER_NOT_FOUND` | The callee has no handler |

**A failed call is not a call that did not run.** After `RPC_TIMEOUT` the request may well have reached the peer
and run. Make an operation across servers idempotent before retrying it, for example with an operation id in the
metadata that the callee remembers.

## Handling messages

Every request and notify from a peer goes to the application's one `RpcHandler`:

```java
RpcHandler handler = (rpcPeer, connection, msg) -> {
    switch (msg) {
        case RpcRequest request -> {
            Object query = protocol.decode(request.command(), request.body());   // decode here
            executor.execute(() -> {                                              // answer later, elsewhere
                if (request.isExpired()) return;                                  // the caller gave up
                RpcResponse.of(request.requestId(), protocol.encode(handle(query))).writeTo(connection);
            });
        }
        case RpcNotify notify -> handle(rpcPeer, protocol.decode(notify.command(), notify.body()));
        default -> {}
    }
};
```

- `rpcPeer` is the node that sent the message, for instance to call it back; `connection` is the network
  `Connection` it came on. Answer a request with an `RpcResponse` under its `requestId` written to that
  connection, at once or later from any thread. Only the first answer counts; the caller drops later ones.
- **`response.writeTo(connection)`** writes the answer even when the connection is busy: the work behind it is
  done, and dropping it would leave the caller unsure whether it was. When that connection has closed by then, it
  writes the answer on another connection up of the same running instance of the peer. It returns `ACCEPTED`, or
  `INACTIVE` and releases the answer when that instance has no connection up, as while its only connection is
  dialed again; the caller's call failed with `RPC_UNAVAILABLE` when that last connection closed. An answer larger
  than this node's largest frame goes out as `RPC_PROTOCOL_ERROR` instead.
- Handshakes, heartbeats and answers to this node's own calls never reach the handler: the node handles them,
  and each answer goes to its call's callback.
- The handler runs on the connection's EventLoop, in the order messages arrived on it, and must not block.
- When it throws, a request not yet answered fails with `RPC_HANDLER_ERROR`; for a notify the exception is logged.
  A node without a handler answers requests with `HANDLER_NOT_FOUND` and drops notifies.

## Reference counting

Bodies are Netty `ByteBuf`s and are never copied on the way. The three records follow Netty's rules:

1. **What is handed to the node becomes the node's**: the request passed to `call`, the notify passed to `notify`
   or `broadcast`, the answer `writeTo` writes. The node releases them once sent, also when they cannot be sent
   or the call throws for a bad argument. Do not use them afterwards.
2. **What the node hands over lives for the method**: the message in `handleMessage` and the answer in
   `onResponse` are released when the method returns. To keep one longer, for example to use it on another
   thread, `retain()` it first and `release()` it when done.
3. **The request in a callback has an empty body**: it was released once sent; every other field is there.
4. **Retain a received message before passing it on**, for example a gateway forwarding a request to the next
   node: rules 1 and 2 each release it once.

## Peers and membership

The application drives membership from its own configuration or service discovery.

- **`node.createPeer(id, address[, connections])`** dials a node with one or more connections and returns its
  peer at once. Each connection is dialed again whenever it fails or closes, from 100 ms doubling up to 10 seconds
  by default (`redialDelay`).
- **A node that dials in** joins by the id in its handshake. When all its connections are gone, its peer stays
  known, and the node dialing in again rejoins the same `RpcPeer` instance.
- **`node.removePeer(id)`** forgets a peer, dialed or dialing in: its waiting calls fail with `RPC_UNAVAILABLE`,
  dialing stops and its connections close. The node dialing in afterwards joins as a new peer. Peers that dial in
  stay known until removed, so a cluster whose nodes come and go under new ids removes them as service discovery
  sees them leave.
- **`node.peer(id)`** is the peer with that id or null; **`node.peers()`** a snapshot of all.

### Listener

`RpcListener` has two methods, both empty by default:

| Method | When |
| --- | --- |
| `onConnected(peer, restarted)` | Every connection of a running instance of the peer completed its handshake |
| `onDisconnected(peer)` | The peer's last connection closed, also when it was removed or a new instance took over |

A peer that loses some of its connections stays connected, with no event; its keys take the connections left.
`peer.isConnected()` is true while at least one connection is up. Calls for one peer never overlap and come in
order, on the EventLoop of the connection that changed, and must not block. A listener may call, notify and answer
any peer: calls, notifies and answers take no lock, so a listener never waits for another peer's.

### Starting up

A call to a peer not yet connected fails at once with `RPC_UNAVAILABLE`, off the calling thread; nothing is
queued. A node that must reach another before it serves, such as a game server registering with the world server
before letting players in, starts that work from its listener:

```java
public void onConnected(RpcPeer peer, boolean restarted) {
    if (peer.nodeId() == WORLD) executor.execute(this::registerAndOpen);
}
```

### Restarts

Each running instance of a node has an **incarnation**: its start time in milliseconds times 1000 plus a random
number, sent in every handshake. `node.incarnation()` is this node's, `peer.incarnation()` the peer's.

- The listener tells a restart from a reconnect: `onConnected(peer, restarted)` has `restarted` true when the
  incarnation differs from the one seen before. After a restart, what this node knew about the peer, such as the
  players on a game server, is gone; after a reconnect it still holds.
- **One instance of a peer is connected at a time.** A newer instance replaces the connected one: the old one's
  connections close and its waiting calls fail with `RPC_UNAVAILABLE` at once, as it can no longer answer them.
  Two processes started with one id therefore do not share the traffic. An older instance dialing while a newer
  one is connected is refused. This relies on the machines' clocks roughly agreeing.

### What a node is for

A node does not know service types; those are the application's. Each node puts what its peers should know in its
handshake metadata, and the application groups peers:

```java
static final MetadataKey<Integer> SERVICE = MetadataKey.intKey(1);

RpcNode game = RpcNode.builder().nodeId(101)
        .metadata(Metadata.builder().put(SERVICE, GAME).build())
        .start();

// on the world server: group peers by what they say they are
Map<Integer, Set<RpcPeer>> byService = new ConcurrentHashMap<>();
RpcListener grouping = new RpcListener() {
    public void onConnected(RpcPeer peer, boolean restarted) {
        byService.computeIfAbsent(peer.metadata().get(SERVICE), s -> ConcurrentHashMap.newKeySet()).add(peer);
    }
    public void onDisconnected(RpcPeer peer) { byService.get(peer.metadata().get(SERVICE)).remove(peer); }
};
```

Which node of a group serves a key is the application's too: modulo, hashing, or a lookup such as "the game
server this player is on".

### Who dials whom

Two nodes must not dial each other: the node dialing in is refused when the other dials it, and `createPeer` for a
node that dials in throws `IllegalStateException`. When both listen, agree on a rule every node follows, such as
"lower tiers dial upper ones" (gate → game → world) or "the lower id dials".

### Going offline

There is no goodbye in the protocol. A node that goes offline as planned says so itself, in a notify of the
application's or through service discovery, so that its peers stop sending it new work; it finishes what it has,
answers what it was asked, and then calls `node.close()`.

- **`node.close()`** stops listening and dialing, fails every waiting call with `RPC_UNAVAILABLE` and closes every
  connection, so that peers see the node leave. Calls made afterwards fail too, and answers still to come no
  longer reach their callers. What was written before, such as the last answers, is flushed as each connection
  closes; only what a busy connection's socket cannot take at that moment is lost. It blocks until done; calling it
  again does nothing.

## Admission

An `RpcAuthenticator` decides from each handshake whether the node on the other side may join, on every connection
in either direction; false closes the connection, and a node refused while dialing in leaves no peer behind. The
handshake is plain data, so a secret in its metadata keeps out mistakes, such as a test server dialing a live
cluster, not attackers.

```java
RpcNode.builder()
        .metadata(Metadata.builder().put(CLUSTER, "live").build())
        .authenticator((connection, nodeId, metadata) -> "live".equals(metadata.get(CLUSTER)))
```

## Back pressure and limits

- A connection with more data queued than its high water mark is busy. The mark is
  `DEFAULT_WRITE_BUFFER_WATER_MARK` (1 MiB low, 4 MiB high) unless a bootstrap sets Netty's `WRITE_BUFFER_WATER_MARK`.
  A busy connection refuses new calls (`RPC_UNAVAILABLE`) and notifies (`NOT_WRITABLE`) at once, so that a slow
  peer cannot fill this node's memory, but takes answers written with `writeTo`. Messages from other threads
  count by their size while they wait for the connection's EventLoop: the node sets an estimator that knows calls,
  notifies and answers, unless a bootstrap sets Netty's `MESSAGE_SIZE_ESTIMATOR`.
- **A callee pushes back by pausing reads.** The handler returns at once and the socket is read empty, so TCP
  alone never slows a peer that sends faster than this node's application handles. When its queues run full, the
  application turns off Netty's own switch on the connection the messages come on, and turns it back on once it
  has caught up:

  ```java
  ((NettyConnection) connection).channel().config().setAutoRead(false); // true again to resume
  ```

  The peer's connection then fills and turns busy: its notifies return `NOT_WRITABLE` and its calls fail with
  `RPC_UNAVAILABLE`. Pause briefly: a side that reads nothing for three heartbeat intervals closes the connection.
- At most `maxPendingCalls` calls to one peer wait for their answer at a time (4096 by default); more fail with
  `RPC_UNAVAILABLE` until some end. Each peer has a table of waiting calls with a reference for each call allowed,
  about 16 KiB at the default.
- Each node sends and receives frames up to its `maxFrameLength`, and the nodes of a cluster use the same value: a
  larger frame arriving closes the connection. A call or notify larger throws `IllegalArgumentException`, after its
  release; an answer larger goes out as `RPC_PROTOCOL_ERROR` instead.

## Handshake and heartbeat

- **Handshake.** Each side sends a handshake with its protocol version, node id, incarnation and metadata; the
  dialing side sends first, naming also the connection's slot and how many connections it opens. Until the
  handshake completes, only a handshake is accepted; any other frame closes the connection. A connection is
  refused and closed, with a warning logged, when:
  - the two speak different protocol versions;
  - the dialed node is not the one `createPeer` named (it is then dialed again);
  - the node dialing in claims this node's id, names a slot beyond its connections, or is one this node dials;
  - this node is closed;
  - the authenticator refuses it;
  - it is an older instance of a peer while a newer one is connected.
- **Heartbeat.** It is the dialing side's: every heartbeat interval (5 seconds by default) it sends a heartbeat,
  which the other side answers at once. Every interval of its own, each side looks at whether anything arrived
  since it last looked; nothing three times in a row closes the connection, such as one to a machine that went
  down without closing it. The nodes of a cluster therefore use the same interval. A connection this node dialed
  is then dialed again. A connection that never completes its handshake is closed by the same rule. Reads are
  only marked, with no timestamp per message.

One time wheel per node, ticking every 10 ms, does all of its timing: each call's timeout and each connection's
heartbeat. The heartbeat itself runs on the connection's EventLoop.

## Observability

- `peer.pendingCount()`: the calls to the peer waiting for their answer.
- `peer.connectionCount()`, `peer.isConnected()`, `peer.incarnation()`, and the listener's connections and restarts.
- Every call's outcome reaches its callback with the request it answers; an application measures latency, timeouts
  and failures by peer and command in its own callback adapter, for whatever monitoring it uses.

## Threads

| What | Runs on |
| --- | --- |
| `RpcHandler.handleMessage`, `RpcAuthenticator` | the connection's EventLoop, in arrival order |
| `RpcListener` | the EventLoop of the connection that changed |
| `RpcCallback` | the EventLoop of the connection the answer came on; the node's time wheel thread when the call timed out; a dialing EventLoop of the node, picked by the route key, when the call failed before it was sent; the EventLoop of the peer's last connection when that closed; the EventLoop of the connection that brought a new instance of the peer; the thread that removed the peer or closed the node. Never the calling thread inside `call` |
| `call`, `notify`, `broadcast`, `writeTo`, `createPeer` | any thread |
| `removePeer`, `close` | any thread but the node's EventLoops, and for `close` its time wheel (they block until connections close; there they throw `IllegalStateException`) |

None of them may block: business logic belongs on the application's own threads, such as its `GameRuntime`.

## Configuration

| Builder method | Default | Meaning |
| --- | --- | --- |
| `nodeId(int)` | required | This node's id, unique in the cluster |
| `metadata(Metadata)` | empty | What this node tells every peer in the handshake |
| `listen(port)` / `listen(InetSocketAddress)` | none | Lets other nodes dial this one; port 0 binds an ephemeral port (`node.port()`) |
| `handler(RpcHandler)` | requests fail with `HANDLER_NOT_FOUND` | Handles what peers send |
| `listener(RpcListener)` | none | Hears peers connect, disconnect and restart |
| `authenticator(RpcAuthenticator)` | admit all | Decides who may join |
| `bootstrap(Bootstrap)` / `serverBootstrap(ServerBootstrap)` | the node's own EventLoops | Netty bootstraps for dialing and listening: EventLoops, channel type, socket options. The handler is the node's; what is unset is filled in on a copy |
| `redialDelay(min, max)` | 100 ms, 10 s | How long a dialed connection waits before dialing again |
| `timeout(Duration)` | 5 seconds | How long a call waits for its answer unless it says otherwise, up to a 10 ms tick more |
| `heartbeatInterval(Duration)` | 5 seconds | How often a dialed connection sends a heartbeat; three intervals without data close a connection. The same on every node of a cluster |
| `maxFrameLength(int)` | 8 MiB | The largest frame this node sends and receives, its length field included. The same on every node of a cluster |
| `maxPendingCalls(int)` | 4096 | How many calls to one peer may wait for their answer at a time |

`start()` listens when asked to and returns the running node. A failed bind releases what it started and throws
`NetworkException`.

The bootstraps are Netty's, so whatever Netty offers is set there: EventLoops shared with the application's other
servers, epoll or io_uring channels, socket buffers, write water marks. EventLoops the application passes stay
its own: closing the node closes its connections on them, not the EventLoops.

## Wire protocol

Frames are length-prefixed (4 bytes, big-endian, excluding itself). Inside, big-endian, a type byte, then:

| Frame | Layout |
| --- | --- |
| Handshake (0) | version byte, nodeId int, incarnation long, slot unsigned short, slots unsigned short, metadata |
| Heartbeat (1) | nothing |
| Request (2) | requestId int, time left in ms int (0 for none), command int, routeKey long, businessType byte, businessId long, metadata, body |
| Response (3) | requestId int, code int, metadata, body |
| Notify (4) | command int, routeKey long, businessType byte, businessId long, metadata, body |

Metadata is its length as an unsigned short, then its bytes. Handshakes and heartbeats are the node's own and never
reach the handler; `command` is the application's alone, so the whole `int` range is its own. The accepting side
echoes the dialing side's slot and slots. An unknown frame type, a frame longer than this node receives and one
cut short of its fields close the connection. This node speaks protocol version 1.

One codec per connection, after flush consolidation, does the framing both ways: an outgoing message is a header
buffer with the length followed by the body as it is; an incoming body is a slice of what was read.

## Build and test

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

Install managame-common and managame-network first. The tests run nodes over real loopback connections with
Netty's leak detection at its strictest, covering:
- answers at once, later from another thread, and after a retain;
- every field reaching the callee, the callback getting its request back, and deadlines;
- failure codes, node and call timeouts, only the first answer counting, and callbacks off the calling thread;
- notifies, broadcasts, and calls and notifies both ways over the connections the dialer opened;
- order by route key over several connections, a key taking another connection while its own is down, and an
  answer coming back on another connection after its request's dropped;
- calls failing at once when the peer's last connection closes, and the answer written meanwhile lost;
- the listener: connected once every connection is, disconnected once none is; peers grouped by handshake metadata;
- a restart told from a reconnect through a proxy that cuts connections, the old instance's calls failing at once,
  and a newer instance replacing an older one;
- handshakes: two nodes dialing each other, a node answering under another id, protocol versions, a frame before
  the handshake, a frame longer than the largest, a slot beyond the connections, a connection that never shakes
  hands, and the authenticator in both directions;
- heartbeats keeping an idle connection up, a dialer dropping a peer that stops answering, the accepting side
  closing a dialer that goes silent, and answering heartbeats;
- the largest frame; a busy connection; the pending-call limit;
- two listeners calling each other's peers while both peers join, without deadlock, and an answer written just
  before the node closes still reaching its caller;
- removing peers, closing nodes, blocking calls refused on the node's EventLoops and time wheel, throwing
  callbacks and listeners, and misuse;
- the pending-call table on its own: ids, the limit, failing all, and every call ending exactly once under
  contention.
