English | [中文](README.zh-CN.md)

# managame-rpc

Calls between the processes of a game server cluster. It stands on its own, on managame-network and
managame-common: how a request is handled and on which thread an answer is used is up to the application.

- **`RpcNode`** is this process: it listens, connects to the peers it is configured with, and hands peers'
  requests to the application's `RpcHandler`.
- **`RpcPeer`** is another process, known by its node id, whichever side opened the connection. A peer may have
  several connections: those this node opens to it and those it opens to this node. Every connection carries
  calls both ways.

## Quick start

Node 1 is a rank server; node 2, a game server, connects to it with two connections.

```java
// Node 1
RpcNode rankNode = RpcNode.builder()
        .nodeId(1).codec(codec).listen(7001)
        .handler(request -> {
            if (request.message() instanceof RankOf query)
                executor.execute(() -> request.reply(ranks.of(query.playerId())));
            else
                request.fail(HANDLER_NOT_FOUND);
        })
        .start();

// Node 2
RpcNode gameNode = RpcNode.builder()
        .nodeId(2).codec(codec)
        .peer(1, new InetSocketAddress("10.0.0.1", 7001), 2)
        .start();

gameNode.peer(1).call(new RankOf(playerId), RpcCallback.of(
        rank -> executor.execute(() -> show(rank)),
        errorCode -> log.warn("rank failed: " + errorCode)));
```

Node 1 can call node 2 back over the connections node 2 opened, though node 2 does not listen:
`rankNode.peer(2).notice(new Deliver(mail))`.

## Codec

The application chooses the encoding of messages and results with an `RpcCodec`:

```java
public interface RpcCodec {
    void encode(Object message, ByteBuf out) throws Exception;
    Object decode(ByteBuf body) throws Exception;   // the body is released afterwards; do not keep it
}
```

- It must be self-describing: the receiver tells messages apart by what it decodes, so the bytes carry the type
  (a type id plus payload, a protobuf `Any`, and so on).
- Every node of a cluster uses the same format. The codec is thread-safe: requests are encoded on the calling
  thread, results on the answering thread, and decoding runs on the connection's EventLoop.

## Calling

| Method | Answer |
| --- | --- |
| `peer.call(request, callback)` | `callback.onResult(result)` or `callback.onFailure(errorCode)` |
| `peer.call(request, metadata, key, callback)` | The same, sending `metadata` and taking the connection `key` picks |
| `peer.notice(message)` / `peer.notice(message, metadata, key)` | None; false when it could not be sent |

- **`RpcCallback`** has `onResult` and `onFailure`; exactly one runs, once. `RpcCallback.of(onResult, onFailure)`
  builds one from two lambdas. Callbacks run on a network thread (the connection's EventLoop, the timeout thread,
  or the calling thread when the call fails at once), so they must not block: hand longer work to your own
  executor. An exception from a callback is logged.
- **`metadata`** is common's `Metadata`, delivered with the request: a trace id, the caller's identity, and so on.
- **`key`** picks the connection: messages sent with one key reach the peer in the order they were sent, as long as
  the peer's connections do not change. Use, say, a player id to keep one player's messages in order.
- A call never throws for the network; a request the codec cannot encode throws what the codec threw.

Failure codes a call can receive:

| Code | When |
| --- | --- |
| `RPC_UNAVAILABLE` | The peer has no connection up, its connection could not take the request, or the connection dropped while the call waited |
| `RPC_TIMEOUT` | No answer within the node's timeout (5 seconds by default) |
| `RPC_NODE_CLOSED` | This node was closed |
| `RPC_PROTOCOL_ERROR` | The request or the result could not be decoded |
| `RPC_HANDLER_ERROR` | The peer's handler threw, or its result could not be encoded |
| `HANDLER_NOT_FOUND` | The peer has no handler, or its handler says so |
| Any other | What the peer's handler failed the request with |

## Handling requests

The `RpcHandler` given to the builder receives every request and notice as an `RpcRequest`:

| Method | |
| --- | --- |
| `peer()` | The node that sent it, for calling it back |
| `message()` | The decoded message |
| `metadata()` | What the sender attached |
| `expectsAnswer()` | False for a notice |
| `reply(result)` / `fail(errorCode)` | Answers once, from any thread, also after the handler returned; false for a notice or a second answer |

- The handler runs on the connection's EventLoop and must not block: hand the request to wherever the
  application runs its logic, and answer from there.
- An exception from the handler fails the request with `RPC_HANDLER_ERROR`; a result the codec cannot encode
  fails it the same way.
- A node built without a handler fails requests with `HANDLER_NOT_FOUND` and drops notices.

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
| `handler(RpcHandler)` | requests fail with `HANDLER_NOT_FOUND` | Handles peers' requests and notices |
| `listen(port)` / `listen(address)` | not listening | Accepts peers' connections; a node that only calls need not listen |
| `peer(id, address[, connections])` | none | A node to connect to, with one or more connections |
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
| Request (1) | requestId int (0 for a notice), metadata length unsigned short, metadata bytes, body |
| Response (2) | requestId int, body |
| Failure (3) | requestId int, errorCode int |
| Ping (4) | nothing; accepted at any time, also before the Hello |

Bodies are what the codec wrote; they are not copied on the way.

## Closing

`close()` fails every waiting call with `RPC_NODE_CLOSED`, stops listening and closes all connections. Requests
already handed to the handler may still be answered, but their answers may no longer reach the callers.

## Build and tests

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

Install managame-common and managame-network first. Tests run two nodes over real loopback connections and cover
answers given at once and later, metadata, failure codes (refusals, unknown messages, a handler that throws, an
unencodable result, a node without a handler), timeouts, notices, calls both ways over connections the other side
opened, ordering by key over several connections, callbacks that throw, Pings keeping an idle connection up, a
peer that falls silent being dropped with its calls failed at once, a connection that never sends its Hello, a
peer that is unreachable until it starts, a peer answering with the wrong node id, dropped connections, closing
the node, and misuse.
