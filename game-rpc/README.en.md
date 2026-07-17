[中文](README.md) | English

# game-rpc

Internal (service-to-service) RPC framework built on Netty + game-serialization. It encodes/decodes API objects directly (no intermediate frame object), correlates requests/responses by requestId, supports the oneway, future and callback invocation styles, and ships with handshake negotiation, heartbeat keepalive, automatic reconnect, backpressure and in-flight limits, and per-service broadcast.

## Design Highlights

- **Peer-to-peer**: client and server share the `RpcContainer` base class. The connection direction does not matter — after the handshake, both sides can initiate `invoke`/`oneway` (the server can call back into connected clients).
- **Peer model**: one remote instance = one `RpcPeer` (`serviceName` + `serviceId`), which owns a group of connections (`ConnectionGroup`, connection chosen by ring modulo over `routeKey`) plus in-flight call management (`RpcInvokeManager`, requestId unique within the peer).
- **Zero-dependency boundary**: this module depends only on game-network-netty / game-serialization; it does not integrate a registry and does not bind a threading model. Callbacks fire on the IO thread — **dispatching to business thread groups is the host's responsibility**.

## Quick Start

### Server

No serializer needs to be passed in manually: `RpcServer` / `RpcClient` default to `SerializerManager.getInstance()`, a singleton pre-registered with JSON / Protobuf / Fory. To add or override a serialType, call `SerializerManager.getInstance().register(...)` at startup.

```java
RpcServer server = new RpcServer(
        new RpcServerConfig().port(9100),
        new RpcMessageHandler() {
            @Override
            protected void handleUserMsg(RpcConnection conn, RpcRequest msg) {
                // IO thread; msg.getBody() is byte[], restore it per msg.getSerialType()
                if (msg.getCommand() == 1) {
                    byte[] respBody = handleLogin(msg.getBody());
                    if (!msg.isOneway()) {
                        conn.writeMsg(RpcResponse.of(msg.getRequestId(), msg.getSerialType(), respBody));
                    }
                }
            }
        });
server.start();   // binds the port; on failure cleans up thread groups then throws GameRpcException
// ...
server.close();
```

When the business handler throws, the framework automatically replies `RpcResponse.error(requestId, 500, ...)` for **request-style** messages so the caller fails fast instead of waiting for the timeout (oneway has no reply).

### Client

```java
RpcClient client = new RpcClient(
        new RpcClientConfig().serviceName("gateway").serviceId("1"),
        new RpcMessageHandler() {   // receives server pushes (oneway / reverse invoke)
            @Override protected void handleUserMsg(RpcConnection conn, RpcRequest msg) { }
        });

ConnectionTargetConfig target = new ConnectionTargetConfig();
target.setServiceName("logic");
target.setServiceId("1");
target.setIp("127.0.0.1");
target.setPort(9100);
target.setConnectionSize(2);
client.connect(target);          // async connect + handshake; idempotent — repeated calls do not double connections
```

The three invocation styles:

```java
byte serial = SerializationType.JSON.typeId();

// 1) callback: response body auto-deserialized by generic type V, callback on the IO thread
client.invoke("logic", "1",
        RpcRequest.of(1).routeKey(playerId).serialType(serial).body(loginReq),
        new RpcCallback<LoginResp>() {
            @Override public void onSuccess(LoginResp resp) { /* ... */ }
            @Override public void onException(Throwable e) { /* ... */ }
        });

// 2) future: get the raw RpcResponse (incl. metadata). await may ONLY be called off the IO thread!
RpcFuture future = client.invoke("logic", "1",
        RpcRequest.of(1).routeKey(playerId).serialType(serial).body(loginReq));
RpcResponse resp = future.await(3000);   // blocking, business threads only

// 3) oneway: fire-and-forget, no reply, no retry
client.oneway("logic", "1",
        RpcRequest.oneway(2).routeKey(playerId).serialType(serial).body(event));

// Use this when the caller must detect no-connection/local-backpressure rejection.
// true means local channel acceptance, not successful remote business processing.
boolean accepted = client.tryOneway("logic", "1",
        RpcRequest.oneway(2).routeKey(playerId).serialType(serial).body(event));

// broadcast: one oneway to every instance of "logic", body serialized only once
client.broadcast("logic", RpcRequest.oneway(3).serialType(serial).body(notice));

// dynamic membership: remove an instance when it goes offline (stop reconnect + fail in-flight + close connections + reclaim peer)
client.disconnect("logic", "1");
```

## Behavior

| Capability | Description |
| --- | --- |
| Handshake | After connecting, the client sends `HANDSHAKE` (with identity/token); the server validates and replies `HANDSHAKE_ACK`; **only after receiving the ACK does the client attach the connection to the peer for routing**. Before completion the server rejects every business/heartbeat message; duplicate handshakes, token mismatches, and handshake timeouts close the connection. |
| Heartbeat | The client sends `PING` on write idle, the server replies `PONG`; either side declares the connection dead and closes it on read idle (`idleTimeoutSeconds`). Driven by `IdleStateHandler`; set to 0 to disable. |
| Reconnect | After a failed connect or a disconnect, reconnects automatically with exponential backoff (with jitter); controlled by `reconnectEnabled`. `disconnect`/`close` stops reconnecting. |
| Backpressure | When the outbound buffer hits the high watermark (`!isWritable`): invoke fails immediately, oneway/broadcast are dropped, counted in metrics — the off-heap memory is never blown up. |
| Soft in-flight limit | New invokes are rejected when a peer approaches `maxPendingPerPeer` (default 100000, 0 = unlimited), preventing OOM when the remote side hangs. Small concurrent overshoot is allowed to keep the hot path lock-free. |
| Timeout | Every invoke is registered on a hashed wheel timer (`RpcRequest.timeoutMillis` or `defaultTimeoutMillis`); on expiry the future/callback receives a `GameRpcException`. |
| Broadcast | `broadcast(serviceName, req)` picks one connection per instance of the service; the body is **encoded once**, then a `retainedDuplicate` of the frame is written per connection. |

## Configuration

`RpcClientConfig` key settings: `serviceName/serviceId` (own identity, used in the handshake), `authToken`, `connectionSize`, `connectTimeoutMillis`, `defaultTimeoutMillis`, `maxPendingPerPeer`, `heartbeatIntervalSeconds`, `idleTimeoutSeconds`, `reconnectEnabled`, `reconnect{Initial,Max}BackoffMillis`, `maxFrameLength`, `writeBufferWaterMark(low, high)`. `validate()` enforces constraints at construction time (e.g. `idleTimeoutSeconds > heartbeatIntervalSeconds`).

`RpcServerConfig` key settings: `port`, `bossThreads`, `workerThreads`, `backlog`, `handshakeTimeoutMillis`, `idleTimeoutSeconds`, `authToken`, `defaultTimeoutMillis`, `maxPendingPerPeer`, `maxFrameLength`.

The event loop groups and the `HashedWheelTimer` are created inside the `RpcServer` / `RpcClient` constructors and released in `close()` (never shared externally).

## Threading Model (Important)

- Callbacks of `RpcMessageHandler.handleUserMsg`, `RpcCallback` and `RpcFuture` all run on **Netty IO threads** (timeout callbacks on the **wheel timer thread**). **Never block inside them**; switching to a business thread group is the host's job.
- `RpcFuture.await()` **blocks the calling thread** and must **never be called on an IO thread** (self-deadlock). It is for business/test threads only; inside game logic prefer the callback style.

## Caveats / Known Trade-offs

- **oneway is fire-and-forget**: no retry or asynchronous write-failure reporting. Plain `oneway` drops on no connection or local backpressure (metrics only); use `tryOneway` when the caller must observe local rejection.
- **`RpcRequest` is mutable**: requestId is written by the framework at invoke time — **do not reuse the same instance concurrently across invokes**.
- **Routing is not consistent hashing**: `floorMod(routeKey, connectionCount)` — when the connection count changes (scaling/disconnect), the same routeKey may drift to another connection.
- **Command ranges**: business commands must be positive; `[-100, -1]` is reserved for framework-internal messages. Metadata keys `[1,99]` are reserved; business starts at `100` (`Metadata.KEY_BUSINESS_MIN`).
- **Auth**: handshake tokens use a constant-time byte comparison, but remain plaintext on the wire and there is **no TLS** — use within a trusted internal network.
- **Inbound backpressure**: autoread stays on and the framework does not limit inbound rate — the host must move `handleUserMsg` work off the IO thread and apply its own backpressure.

## Out of Scope (Left to the Host)

- **Service discovery**: `connect`/`disconnect` are the membership management entry points; the host bridges registry (e.g. game-registry) online/offline events to drive them.
- **Graceful shutdown draining**: `close()` stops listening first and then shuts down the thread groups, but does not wait for in-flight requests to complete (consistent with the "no zero-loss guarantee" trade-off).
