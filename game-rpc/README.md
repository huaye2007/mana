# game-rpc

[Package layout, responsibilities and import migration (Chinese)](../docs/package-layout.md)

English | [简体中文](README.zh-CN.md)

A Java RPC implementation built on game-network, using JDK 25 and Netty 4.2.15.Final. It manages NodeId → RpcPeer mappings, TCP connections, Call/Send, explicit replies, response matching, timeouts, Metadata, and message encoding/decoding.

game-rpc does not recognize Router services, look up application routes, or automatically forward or unwrap messages. Applications handle service discovery, message dispatch, and application object encoding/decoding.

**Implementation status (2026-09-12):** Java Binding Draft 0.14. 286 tests passed (Core: 251; Netty/TCP: 35). Supports error arguments, per-slot reconnection backoff, and optional read-idle closure on the receiving side.

RpcNode provides the public API and component lifecycle; RpcCalls manages calls, RpcMessages handles messages centrally, and RpcConnections manages connections and outbound connection state. RpcPeer stores only logical node identity, published connections, PendingCall, and lifecycle state, without addresses, reconnection timers, or connection callbacks. Each physical connection's handler also maintains handshake identity, with no separate Binding. Normal message handling does not look up connections by connectionId. RpcFuture continues to hold only the state of one call. Common message rules also apply to custom codecs. Handshake admission and encoding run outside the connection management lock; candidate validity is checked again after the lock is reacquired.

Custom RpcTransport implementations use `start(Listener)`, `connect(InetSocketAddress, ConnectCallback)`, and `write(Connection, ByteBuf)`. Use `checkLifecycleThread()` to check the thread before synchronous startup or shutdown. The default Netty provider has been updated accordingly.

## Documentation

- [Game RPC specification](docs/OGBS%20Game%20RPC%20Specification%20v1.md): language-independent behavior and the default wire format.
- [Java implementation specification](docs/java/Java%20Implementation%20Specification.md): project APIs, state ownership, concurrency, reference management, and acceptance criteria.
- [Wire vectors](docs/OGBS%20Game%20RPC%20v1%20wire%20vectors.json): shared data for byte-by-byte tests.

## Creating and connecting nodes

Adding game-rpc-netty automatically loads the default provider and codec.

~~~java
var rpc = RpcNode.builder()
        .nodeId(10)
        .listen("127.0.0.1", 7000)
        .handler(handler)
        .defaultTimeout(Duration.ofSeconds(3))
        .build();
rpc.start(); // Synchronously initialize the TCP client and bind listeners.
rpc.connect(20, "127.0.0.1", 7001); // Establish a direct connection in the background.
~~~

Heartbeats are enabled by default. For each physical connection, the initiating side sends the first PING 5 seconds after the handshake, then waits another 5 seconds after receiving PONG. A reply may take at most 15 seconds. The receiving side replies automatically on the original connection, without invoking the application handler or using an RpcFuture. A timeout closes and reconnects only that slot. Configure `.heartbeatInterval(Duration.ofSeconds(5))` and `.heartbeatTimeout(Duration.ofSeconds(15))` on the builder; the minimum is 100 ms. Custom codecs must support RpcHeartbeat PING/PONG. Upgrade older nodes before interoperating with them.

Idle events do not close connections by default. To clean up inactive connections on the receiving side through network read-idle detection, explicitly set `.readIdleTimeout(Duration.ofSeconds(30))` on the builder. `Duration.ZERO` keeps this feature disabled. It reuses game-network's READ_IDLE/onIdle and closes only the original inactive physical connection while retaining the peer. The timeout should cover the remote heartbeat interval, heartbeat reply timeout, and scheduling margin.

The first connection attempt is scheduled immediately. Subsequent attempts use independent exponential backoff with jitter for each slot, reset after a successful handshake. Default retry waits are 0.5–1 second, 1–2 seconds, and 2–4 seconds, increasing to a maximum of 15–30 seconds. Configure the initial and maximum upper bounds through `.reconnectDelay(Duration.ofSeconds(1))` and `.maxReconnectDelay(Duration.ofSeconds(30))`. The maximum must not be smaller than the initial value. Repeated connect calls do not bypass a pending retry.

To specify the connection count and receive a readiness notification, use this overload instead of the connect call above:

~~~java
rpc.connect(20, "127.0.0.1", 7001, 2, new ConnectCallback() {
    public void onSuccess(Connection connection) {
        // All slots for this node pair have become ready for the first time.
    }
    public void onFailure(Throwable failure) {
        // Removal, closure, or an explicit direction conflict; ordinary network failures retry after a delay.
    }
});
~~~

For a pair of distinct nodes, only one side may initiate connections. Physical connections carry traffic in both directions; the receiving side does not need acceptPeer/addPeer. peer(nodeId) looks up a registered adjacent node. Sending APIs do not implicitly create unknown targets. rpc.removePeer(nodeId) terminates that peer, and rpc.close() releases resources owned by the node.

## Call, Send, and replies

~~~java
ByteBuf body = Unpooled.wrappedBuffer(new byte[] {1, 2, 3});
try {
    var options = RpcOptions.builder()
            .routeKey(123L)
            .putLong((short) 1024, 456L)
            .timeout(Duration.ofSeconds(1))
            .build();
    rpc.call(20, 100, body, options, result -> {
        if (result.isSuccess()) {
            RpcResponse response = result.value();
            // Use the complete response header, Metadata, and raw body within this callback.
        } else {
            RpcError error = result.error(); // Framework and application errors share a non-null RpcError.
            var errorArgs = result.errorArgs(); // Immutable, empty for local failures.
            Throwable cause = result.cause(); // Optional local cause; never sent on the wire.
        }
    });
} finally {
    body.release();
}
~~~

~~~java
ByteBuf noticeBody = Unpooled.wrappedBuffer(new byte[] {4, 5});
try {
    boolean accepted = rpc.send(20, 101, noticeBody, RpcOptions.DEFAULT);
} finally {
    noticeBody.release();
}

// Reply explicitly in the receiving handler; the caller encodes and manages responseBody.
rpc.reply(connection, new RpcResponse(
        request.requestId(), 0, RpcMetadata.EMPTY, responseBody));

// Or reply with an application error and an ordered array of string arguments.
rpc.reply(connection, RpcResponse.error(request.requestId(), GameErrors.NOT_ENOUGH_GOLD, "1000", "300"));
~~~

The default codec encodes error arguments in the body without adding header or Metadata fields. The default is an empty array when there are no arguments. Error codes 1–1000 are reserved for the framework; application codes start at 1001. Every failure has a non-null result.error(); it is null only on success. Error responses with arguments require both codecs to support the new format. Success frames and error frames without arguments are unchanged.

Define application errors as immutable RpcError constants:

~~~java
public final class GameErrors {
    public static final RpcError NOT_ENOUGH_GOLD = new RpcError(1001, "Not enough gold");
    private GameErrors() {}
}
~~~

RpcError compares by code, so use `GameErrors.NOT_ENOUGH_GOLD.equals(result.error())` to check an error. message is a local description and is not transmitted. For an unknown received error code, message is empty and code is preserved. Application errors do not need registration with RpcNode.

Each peer retains at most 64 callbacks waiting for connection readiness. Additional connect calls with callbacks synchronously throw OVERLOADED. Ordinary network failures reconnect automatically, so repeated callback registration is unnecessary. Repeated connect calls without callbacks add no waiters.

Result, connection, and diagnostic callbacks run directly on the thread that produces the event. game-rpc does not switch callback threads through an internal thread pool. Applications are responsible for dispatching work to their own application threads, and callbacks should return promptly. Timeout notifications may run on the timer wheel thread. Removal/closure notifications run on the calling thread after resource cleanup and after leaving the lifecycle lock. Synchronous start/close calls on the node's own timer wheel or network EventLoop are rejected before changing state; applications must dispatch shutdown elsewhere. The first caller to acquire ownership of closure synchronously cleans up resources; concurrent or reentrant close calls return idempotently.

The caller encodes body/responseBody in the examples and manages its own references. Call automatically assigns a positive int requestId; Send notifications use 0. targetNodeId must be a registered direct neighbor. An unknown target reports UNAVAILABLE through the callback or returns false. routeKey selects only a connection slot within that peer; it does not look up a next hop.

The user entry point remains unified:

~~~java
public interface RpcHandler {
    void handleUserMsg(Connection connection, Object msg);
}
~~~

Ordinary requests arrive as complete RpcRequest objects, including requests with unknown commands. Responses are matched by the adjacent peer's RpcFuture before reporting the result. Returning from the handler does not automatically reply; users decide when to reply and how to order surrounding messages.

RpcRouteMessage is only a raw envelope, with sourceNodeId, targetNodeId, and ByteBuf inner. It is delivered unchanged to the handler, without decoding inner, checking the target, forwarding automatically, or completing an inner Response. Applications can explicitly send a complete message through `send(connectedNodeId, message, options)`. This entry point does not register a call. It uses only options.routeKey to select a connection; the message supplies all other headers.

## Stopping calls before closing transports

`rpc.stopCalls()` irreversibly stops new outbound calls and notifications, claims pending calls and completes them with `UNAVAILABLE`. It preserves connections and permits `reply(...)` for accepted inbound requests. The operation is idempotent; callbacks run on its caller outside internal locks. A response/timeout that already claimed a result may still be delivering its callback; stopCalls does not wait for it. It does not cancel remote business operations or stop inbound request dispatch.

For a game server: stop application admission, call stopCalls, drain the application runtime, then close RPC and client transports. Keep one adapter per node: stopping calls affects all outbound calls owned by that node. Full close remains available for immediate shutdown.

`RpcResult.errorArgs()` provides immutable remote arguments without checking value for null. It returns an empty list for local failures and success. `cause()` preserves local submission/scheduling exceptions when available; it is never encoded or attached to a remote response. The two-argument RpcResult constructor remains as a source-compatible convenience; recompile consumers after the record change.

## Memory and configuration

Sending uses the connection's active/writable state without maintaining an additional pending outbound byte budget. `RpcLimits` has four parameters: frame, Metadata, body, and Pending limits. The previous final `maxOutboundBytes` parameter has been removed.

- RpcRequest.body, successful RpcResponse.body, and RpcRouteMessage.inner are borrowed within synchronous handlers/result callbacks. To hold them asynchronously, copy or retain them and eventually release them.
- encode/send/reply neither consume the caller's input reference nor change input indices. Envelope output may reference inner, so do not modify the underlying bytes before sending completes.
- RpcMetadata uses separate GC-managed heap storage and requires no explicit release. It provides putInt/putLong/putString/putBoolean and getters, with the internal implementation in RpcMetadataUtil.
- Each Metadata value is 0–127 bytes long. Inbound validation does not use ThreadLocal. The first 8 keys are checked for duplicates with local variables; additional fields allocate a bitmap for that validation as needed.
- Request IDs advance continuously within an RpcNode and are not reset when a peer is recreated. Isolation from old messages after a full wraparound or across runtime instances still requires external guarantees.
- maxPendingHandshakes defaults to 1024 and limits concurrent inbound/outbound handshakes; maxPeers defaults to 4096. Handshakes arriving before local start completes are rejected and retried by the initiator. If all initial inbound handshakes fail, the temporary peer slot is automatically reclaimed. A peer with any previously successful slot remains after disconnection; the application must call removePeer when it leaves the cluster.
- Each write flushes immediately by default. For higher message volumes, enable `consolidateFlush(true)` to consolidate flushes. Leaving it disabled retains immediate sending behavior.

## Build

From the workspace root:

~~~text
mvn -pl game-rpc/game-rpc-netty -am verify
~~~

To run only RPC tests while building dependencies:

~~~text
mvn -pl game-rpc/game-rpc-netty -am verify "-Dtest=Rpc*Test" "-Dsurefire.failIfNoSpecifiedTests=false"
~~~

Before building independently, install or publish game-network dependencies with the same version. Current validation covers functionality, concurrency, and ByteBuf reference management; there are no production throughput or latency stress-test conclusions yet.

## Performance baseline

After completing RPC verify above, run:

~~~text
python game-rpc/benchmarks/run.py --requests 200000 --warmup 50000 --repeats 3
~~~

The benchmark covers small/large packets, single/multiple connections, flush consolidation, and diagnostics enabled/disabled. It writes throughput, latency percentiles, process allocation, and GC data to `game-rpc/target/benchmark-<timestamp>.json`. Client, server, and measurement code run in the same JVM. This is a local TCP baseline with bounded concurrency; see the Java implementation specification for metric definitions and limitations.

## Linux Docker endurance and fault tests

Switch local Docker to its Linux engine and prepare a JDK 25 Maven image and the workspace's `game-network/.m2` dependency cache. Then run from the workspace root:

~~~text
python game-rpc/benchmarks/docker_stress.py --prepare --work game-rpc/target/docker-run/work --output game-rpc/target/docker-run/results
~~~

`--prepare` requires the work directory not to exist yet. The runner copies source code and the cache and runs RPC verify inside a container. The client and server run in two separate Linux containers communicating through a private Docker network. Test control ports are published only on the local loopback address. The containers and network created by the run are cleaned up afterward. Omit `--prepare` when using an existing Linux build.

The default run takes approximately 25 minutes. It covers small packets with immediate/consolidated flushes, large packets at two in-flight limits, application error string arrays, bulk disconnects, paused reads, process pauses and forced restarts, and paranoid leak detection. `--seconds` controls the main scenario duration, `--business-seconds` controls the low-concurrency large-packet and application-error duration, and `--leak-seconds` controls the leak-detection duration. Use `--only` to select scenarios. Fault scenarios should run for at least 120 seconds to allow time to observe recovery.

The results directory contains `summary.json`, logs from both ends of each scenario, and Docker resource samples. In normal scenarios, framework errors, content errors, duplicate callbacks, uncleared Pending entries, connections that fail to recover, or leak reports cause failure. Fault scenarios allow UNAVAILABLE/OVERLOADED/TIMEOUT, but must recover and clear Pending entries. The process exit code reflects the overall result, and failed scenarios retain their evidence. See the Java implementation specification for the distinction between these metrics and production capacity.

Command IDs are nonzero signed int32 values, including negative internal protocol IDs. Zero is invalid. Request IDs remain positive for calls and zero for one-way sends. Peers running older positive-only command validation must be upgraded before using negative commands.
