English | [中文](README.zh-CN.md)

# managame-rpc

Calls between the processes of a game server cluster. Requests are handled by the same `@HandleMethod`s as player
messages, and requests, results and failures all run through the runtime, so per-route ordering holds across
servers too.

- **`RpcNode`** is this process: it listens, connects to the peers it is configured with, and holds the handlers.
- **`RpcPeer`** is another process, known by its node id, whichever side opened the connection. A peer may have
  several connections: those this node opens to it and those it opens to this node. Every connection carries
  calls both ways.

Depends on managame-runtime (and through it managame-network and managame-common).

## Quick start

Node 1 is a rank server; node 2, a game server, connects to it with two connections.

```java
// Node 1
@Domain(RANK)
class RankService {
    @HandleMethod Integer rankOf(RankOf query) { return ranks.of(Context.current().businessId()); }
}
RpcNode rankNode = RpcNode.builder(rankRuntime)
        .nodeId(1).codec(codec).listen(7001)
        .register(new RankService())
        .start();

// Node 2
RpcNode gameNode = RpcNode.builder(gameRuntime)
        .nodeId(2).codec(codec)
        .peer(1, new InetSocketAddress("10.0.0.1", 7001), 2)
        .register(new Mailbox())
        .start();
```

A player's request on node 2, answered from the call's callback:

```java
@Domain(PLAYER)
class Ranking {
    @HandleMethod void rank(HandleContext request, AskRank ask) {
        gameNode.peer(1).call(new RankOf(),
                (Context c, Integer rank) -> request.reply(rank),
                (c, errorCode) -> request.fail(errorCode));
    }
}
```

Node 1 can call node 2 back over the connections node 2 opened, though node 2 does not listen:

```java
rankNode.peer(2).notice(GameContext.of(RANK, 0, ROLE, playerId), new Deliver(mail));
```

## Codec

The application chooses the encoding of messages and results with an `RpcCodec`:

```java
public interface RpcCodec {
    void encode(Object message, ByteBuf out) throws Exception;
    Object decode(ByteBuf body) throws Exception;   // the body is released afterwards; do not keep it
}
```

- It must be self-describing: the receiver picks the handler by the class of the decoded message, so the bytes
  carry the type (a type id plus payload, a protobuf `Any`, and so on).
- Every node of a cluster uses the same format. The codec is thread-safe: requests are encoded on the calling
  thread, results on the handler's thread, and decoding runs on the connection's EventLoop.

## Calling

| Method | Acts for | Answer |
| --- | --- | --- |
| `peer.call(request, onResult, onFailure)` | The running game task (`Context.current()`) | Callbacks |
| `peer.call(context, request, onResult, onFailure)` | `context`, from anywhere | Callbacks |
| `peer.call(request, onResult)` | The running game task | Failures are logged |
| `peer.notice(message)` | The running task, or nobody outside a task | None; false when not sent |
| `peer.notice(context, message)` | `context` | None; false when not sent |

- `onResult` and `onFailure` run as a task on the calling context's route, after the work that made the call and
  in order with the route's other tasks. When the calling context is a request, the callback can answer it.
- A call never throws for the network. A request the codec cannot encode throws what the codec threw, and calling
  outside a task without a context throws `IllegalStateException`.
- A callback the runtime refuses, because it was shut down, is logged and dropped.

Failure codes a call can receive:

| Code | When |
| --- | --- |
| `RPC_UNAVAILABLE` | The peer has no connection up, its connection could not take the request, or the connection dropped while the call waited |
| `RPC_TIMEOUT` | No answer within the node's timeout (5 seconds by default) |
| `RPC_NODE_CLOSED` | This node was closed |
| `RPC_PROTOCOL_ERROR` | The request or the result could not be decoded |
| `RPC_HANDLER_ERROR` | The peer could not encode its handler's result |
| `HANDLER_NOT_FOUND`, `INVALID_ROUTE_KEY`, `RUNTIME_EXECUTION_ERROR`, `RUNTIME_CLOSED`, `ROUTE_EXECUTOR_OVERLOADED` | What the peer's Dispatcher or runtime answered |

## Handling requests

- Handlers are `@HandleMethod`s in `@Domain` classes, registered with `RpcNode.Builder.register(...)`. The node
  keeps its own Dispatcher, separate from the one for players, so clients cannot reach internal handlers.
- A request carries the caller's context: domain, routing key, business type and id, and metadata. At the
  receiver it runs in its handler's domain, on the routing key that domain's resolver finds, acting for the
  caller's business identity with the caller's metadata. A player domain keyed by `RouteKeys.businessId()` thus
  runs a request on the player it was sent for.
- A non-null result is the answer; a handler taking `HandleContext` may answer later through it. Notices are
  dispatched without a way to answer.
- `HandleContext.source()` is an `RpcSource`: the caller's fields, `nodeId()`, and `peer()` to call the calling
  node back.
- `HandleInterceptor`s added with `RpcNode.Builder.intercept(...)` run before and after every handler, as they do
  on a Dispatcher (see managame-runtime); a code returned by `before` reaches the caller's failure callback. For
  example, only some nodes may send a request:

  ```java
  class TrustedNodes implements HandleInterceptor {
      public int before(HandleContext context, Method handler, Object message) {
          return trusted.contains(((RpcSource) context.source()).nodeId()) ? SUCCESS : NOT_TRUSTED;
      }
  }
  ```

## Peers and connections

- **Handshake.** Each side sends a `Hello` with its node id, the side that connected first. A connection carries
  calls once the other side's Hello has arrived. The connecting side checks that the node it reached is the one
  it was configured for and closes the connection otherwise; a Hello with this node's own id, or any frame before
  the Hello, also closes it.
- **Configured peers.** `.peer(id, address)` opens one connection, `.peer(id, address, connections)` several.
  Each is reconnected when it drops, with a delay from 100 ms doubling up to 10 seconds.
- **Peers that connect in** join by the id in their Hello and can be called like configured ones.
- **`node.peer(id)`** returns the peer for any id other than this node's, always the same instance. Before it has
  a connection, calls to it fail with `RPC_UNAVAILABLE`. A peer stays known after its connections drop.
  `isConnected()` and `connectionCount()` report its connections.
- **Choosing a connection.** A call takes the connection its calling context's `(domain, routingKey)` hashes to.
  Messages of one route therefore reach a peer in the order they were sent, as long as the peer's connections do
  not change, while different routes spread over the connections.
- **Dropped connections.** A connection that drops fails only the calls waiting on it, with `RPC_UNAVAILABLE`.
- **Liveness.** Either side of a connection that has written nothing for a third of the idle timeout sends an
  empty Ping, so a live connection is never silent for long. A connection that has received nothing for the whole
  idle timeout (15 seconds by default) is taken as dead and closed, such as one to a machine that went down
  without closing it: its calls fail with `RPC_UNAVAILABLE` at once instead of waiting for their timeout, and a
  connection this node opened is reconnected. The same rule closes a connection that never sends its Hello. A
  busy connection sends no Pings.

## Configuration

| Builder method | Default | Meaning |
| --- | --- | --- |
| `nodeId(int)` | required | This node's id, which its peers know it by |
| `codec(RpcCodec)` | required | Message encoding, the same on every node |
| `listen(port)` / `listen(address)` | not listening | Accepts peers' connections; a node that only calls need not listen |
| `peer(id, address[, connections])` | none | A node to connect to, with one or more connections |
| `register(Object...)` | none | Objects declaring `@HandleMethod`s for requests and notices |
| `intercept(HandleInterceptor...)` | none | Interceptors around every request and notice handler |
| `timeout(Duration)` | 5 seconds | How long a call waits for its answer |
| `idleTimeout(Duration)` | 15 seconds | How long a connection may receive nothing before it is closed; Pings go out after a third of it. The same on every node |
| `maxFrameLength(int)` | 8 MiB | Largest frame |

`start()` listens and connects, and returns the running node. A bind failure throws `NetworkException` after
releasing what was started.

## Wire format

Frames are length-prefixed (4 bytes, big-endian, excluding itself). Inside, big-endian, a type byte, then:

| Frame | Layout |
| --- | --- |
| Hello (0) | nodeId int |
| Request (1) | requestId int (0 for a notice), then the caller's domain int, routingKey long, businessType byte, businessId long, metadata length unsigned short, metadata bytes, then the body |
| Response (2) | requestId int, body |
| Failure (3) | requestId int, errorCode int |
| Ping (4) | nothing; accepted at any time, also before the Hello |

Bodies are what the codec wrote; they are not copied on the way.

## Closing

`close()` fails every waiting call with `RPC_NODE_CLOSED`, stops listening and closes all connections. Requests
already handed to the runtime still run, but their answers may no longer reach the callers. Close the node before
its runtime.

## Build and tests

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

Install managame-runtime first. Tests run two nodes over real loopback connections and cover calls routed for the
caller and answered on its route, answering a player from a callback, calls both ways over connections the other
side opened, ordering over several connections, interceptors, remote failure codes, timeouts, notices, a peer that is
unreachable until it starts, a peer answering with the wrong node id, Pings keeping an idle connection up, a peer
that falls silent being dropped with its calls failed at once, a connection that never sends its Hello, dropped
connections, closing the node, and misuse.
