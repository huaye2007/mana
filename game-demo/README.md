# game-demo

English | [简体中文](README.zh-CN.md)

A runnable JDK 25 / Maven 3.9 game-server project integrating all three components:

- **game-network-netty**: game clients connect directly to the game server over TCP.
- **game-runtime**: player commands run in order; internal RPC callbacks re-enter the caller's player route.
- **game-rpc-netty**: internal communication between game servers, on a separate port.

```text
Game client -- game-network TCP --> Game server A -- game-rpc --> Game server B
                                    game-runtime                 game-runtime
                                    player wallets               player wallets
```

## Build and run

Run from the repository root:

```sh
mvn -pl game-demo -am verify
java -jar game-demo/target/game-demo-0.1.0-SNAPSHOT.jar
```

The default mode starts two game servers on ephemeral loopback ports, connects a game client directly to server `20`, demonstrates three wallet requests, and then makes a separate internal RPC call from server `21` to server `20`. All resources close when the demo finishes. Each player's wallet starts with 100 gold:

```text
Game client connected directly via game-network
player=10001 spent=30 gold=70 trace=20260913
player=10001 spent=50 gold=20 trace=20260913
Spend rejected: Game error 1002, args=[30, 20]
Server 21 -> server 20 grant RPC: player=20001 gold=110 trace=20260914
```

The runnable JAR loads runtime dependencies from the adjacent `target/lib/` directory. Copy this directory together with the JAR. Keeping original dependency JARs also preserves the RPC Netty provider's service descriptor.

### Windows

If JDK selector initialization reports `Unable to establish loopback connection` / `Invalid argument: connect`, use an existing short directory for its local socket files:

```powershell
java "-Djdk.net.unixdomain.tmpdir=game-demo/target" -jar game-demo/target/game-demo-0.1.0-SNAPSHOT.jar
```

Maven tests apply this option automatically on Windows, following the repository's network/RPC test configuration. Add the same JVM option before `-jar` in the following commands when needed.

### Separate processes

Start a game server (client TCP port `7000`, internal RPC port `7070`, node ID `20`):

```sh
java -jar game-demo/target/game-demo-0.1.0-SNAPSHOT.jar server 7000 7070 20
```

Run an ordinary game client in a second terminal:

```sh
java -jar game-demo/target/game-demo-0.1.0-SNAPSHOT.jar client 7000
```

Optionally start a temporary game server `21` to demonstrate an internal RPC call:

```sh
java -jar game-demo/target/game-demo-0.1.0-SNAPSHOT.jar peer 7070 20
```

All arguments after the mode are optional; the values shown are the defaults. `peer` uses node ID `21`, so choose a different target ID and run one instance of this mode at a time. Multiple ordinary game clients can connect without RPC node identities. Press Enter in the game-server terminal to drain accepted runtime commands and close both network endpoints.

Each server owns its local in-memory player wallets. Repeated clients use the remaining balance; restarting that server resets its state. Each server is independent: the same player ID on a different server starts with a separate wallet.

In an IDE, import the root Maven project, select JDK 25, and run `cn.managame.demo.launcher.GameDemo`.

## Architecture

One Maven project, organized by responsibility:

| Package/class | Responsibility |
|---|---|
| protocol/client | Client DTOs and ClientCommands |
| protocol/rpc | Internal service DTOs and RpcCommands |
| protocol | Generic command declarations, TCP envelope and call failures |
| network / codec | TCP framing and header validation |
| serialization | One Fory implementation with both catalogs' fixed type IDs |
| client / DemoClient | Request correlation and notification dispatch |
| server / DemoServer | Compose handlers, state, Runtime and transports |
| server / ServerIngress | Separate client and internal exposure lists |
| server / runtime | Player scheduling and generic command admission |
| server / network, rpc | Transport-specific dispatch and encoding |
| server / support | Context sending, metadata, routes and failure mapping |
| server / gameplay | WalletHandler handles clients; WalletRpcHandler grants rewards; Wallets owns shared state |

Client and internal messages describe different business operations. No client DTO is converted into an internal request. DemoServer injects one Wallets instance into both handlers, whose operations on the same player run on the same Runtime route.

## Protocols and calls

| Ingress | Operation | command | Request | Response/notification |
|---|---|---|---|---|
| Client TCP | Spend gold | 1001 | SpendGoldReq | SpendGoldRes |
| Client TCP | Read wallet | 1002 | GetWalletReq | GetWalletRes |
| Push to client | Wallet update | 2001 | — | WalletChangedNotify |
| Internal RPC | Grant reward | -1001 | GrantGoldReq | GrantGoldRes |

Client commands are positive; internal commands are negative; zero is reserved. CommandBinding permits nonzero IDs. TCP rejects negative ingress declarations; RPC rejects positive declarations. Both check declarations against Runtime and reject duplicate IDs before allocating transports.

Client DTOs belong to protocol.client.ClientProtocol. This demo has no login: PlayerRequest exposes playerId and traceId supplied in the client request, without conversion or dispatch methods.

```java
var spend = client.call(ClientCommands.SPEND, new SpendGoldReq(7, 10, 123));
var wallet = client.call(ClientCommands.WALLET, new GetWalletReq(7, 124));
```

Internal DTOs belong to protocol.rpc.RpcProtocol. GrantGoldReq contains only the reward amount; RPC routeKey supplies the player and RPC metadata supplies traceId.

```java
server.rpc().connectPeer(peerId, peerAddress);
var reward = server.rpc().call(peerId, RpcCommands.GRANT, new GrantGoldReq(10),
        new Route(PlayerRoute.class, playerId),
        RpcOptions.builder().routeKey(playerId).putLong(RpcProtocol.TRACE_ID, traceId).build());
```

WalletRpcHandler validates positive amounts and integer overflow before increasing gold. Its business error is INVALID_GRANT=3001. WalletHandler uses client errors INVALID_REQUEST=1001 and NOT_ENOUGH_GOLD=1002. Invalid RPC bodies, player routes or tracing metadata return framework PROTOCOL_ERROR.

Both handlers use GameMessages.send(message) and sendError(code, args); the connection's Sender owns encoding. Add client features as positive commands with client handlers, and internal features as negative commands with internal handlers.

## Notifications

Server-to-TCP-client notifications require no request, response or pending entry. ClientCommands.NOTIFICATIONS maps notification objects to commands independently of Runtime requests.

```java
GameMessages.notify(new WalletChangedNotify(playerId, gold));
GameMessages.notify(clientConnection, new WalletChangedNotify(playerId, gold));
client.onNotify(WalletChangedNotify.class, message ->
        System.out.println("wallet changed: " + message.gold()));
```

The first overload uses the current client command connection; the second works outside a command context. Register one callback per type, preferably before connecting. Callbacks run on the I/O thread and must return promptly. RuntimeExceptions are logged without interrupting later responses. Valid unsubscribed messages are validated and discarded; unknown commands or malformed bodies close the connection and fail pending calls.

The result means write admission, not receipt. There is no acknowledgement, retry or replay. The facade rejects internal RPC connections with UnsupportedOperationException; RpcNode.send handles internal one-way messages.

## Wire format and serialization

Client TCP:

```text
length:int | command:int | requestId:int | code:int | flags:int | body:bytes
```

Length/header are big-endian; length excludes itself. Maximum frame: 4096 bytes; maximum body: 4076 bytes. Client command is positive.

- flags=0: request, positive requestId, code=0.
- flags=1: response, echoes command/requestId, code=0 for success or positive for failure.
- flags=2: notification, requestId=0 and code=0, bypasses pending calls.
- Client-to-server responses and notifications are rejected.

TCP and RPC both name their correlation field requestId and store it in CommandHandlerInvocation, exposed by CommandContext.requestId(); Req/Res bodies do not duplicate it. Their counters and pending calls are independent: an internal RPC triggered by a client request allocates its own ID. Use traceId for end-to-end tracing. CommandMetadata retains player routing/tracing extensions without duplicating correlation. Error handling reads invocation.requestId(), including failures before context creation. TCP/RPC wire bytes are unchanged.

Internal RPC retains its own envelope. game-rpc accepts nonzero signed int32 commands; game-demo restricts internal ingress to negative IDs. Request IDs remain positive for calls and zero for one-way sends.

MessageSerializer uses Fory 1.7.1 with cross-language/compatible modes, mandatory registration, no reference tracking and a thread-safe pool of four instances.

| Stable Fory ID | Type |
|---|---|
| 3 / 4 | SpendGoldRes / ErrorRes |
| 7 / 8 | GetWalletRes / WalletChangedNotify |
| 9 / 10 | SpendGoldReq / GetWalletReq |
| 11 / 12 | GrantGoldReq / GrantGoldRes |

ClientCommands.MESSAGE_TYPES and RpcCommands.MESSAGE_TYPES register their own types. Fory IDs are independent of command IDs: internal negative commands still use positive Fory IDs. IDs 1/2/5/6 are retired and must not be reused.

Decoding checks type, truncation and trailing bytes. RPC buffers are decoded while borrowed; outgoing buffers are released after call/reply. GamePacket defensively copies its body. Internal peers need coordinated upgrades: older game-rpc versions reject negative commands.

Tests and the executable JAR configure JDK 25 java.lang.invoke access. IDE/custom-classpath runs need `--add-opens=java.base/java.lang.invoke=ALL-UNNAMED`.

## Callbacks and shutdown

RpcRuntimeClient decodes borrowed results and dispatches admitted callbacks to the initiating player route. RPC owns pending calls and timeouts. Rejection or backend failure terminates the wait without fallback business callbacks.

Close stops command admission and outgoing RPC, fails pending calls with UNAVAILABLE, drains Runtime, then closes transports. The default drain timeout is 30 seconds; server.close(Duration) overrides it. RuntimeShutdownException leaves transports available for accepted replies; retry close after tasks finish.

The demo has no persistence, authentication, player placement or request deduplication. Canceling or timing out a wait does not undo a remote mutation. Reward grants are not retried automatically.

## Verification

`mvn -pl game-demo -am verify` runs foundation and demo regressions. The 64 demo tests cover ingress separation, positive/negative commands, client spending and internal rewards sharing player state, callbacks, shutdown, notifications and Fory. RPC wire vectors cover -1001 calls and Integer.MIN_VALUE notifications while rejecting zero commands. Netty paranoid leak detection is enabled.

See [architecture and integration notes (Chinese)](docs/integration-notes.zh-CN.md).
