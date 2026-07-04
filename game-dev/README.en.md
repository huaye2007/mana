[中文](README.md) | English

# game-dev

A complete, runnable game server host: it assembles game-network (Netty transport), game-runtime (thread groups / commands / events / timers), game-serialization (Apache Fory) and game-jpa (async persistence) into an out-of-the-box single-process game server, and doubles as the reference template for "how a host should wire these frameworks together".

## Architecture

```
Client ──TCP──▶ Netty IO threads
                 │  IdleStateHandler + IdleKickHandler   read-idle kick
                 │  GamePacketEncoder / GamePacketDecoder frame codec + Fory deserialization
                 │  GameHandler (INetworkHandler)         connect/disconnect/exception
                 ▼
           GameRouterManager.handleGameMsg               login gate → route by command
                 │ wraps GameCommandTaskRunnable
                 ▼
           ExecutorGroupRegistry                          LOGIN / PLAYER executor groups
                 │ serial per routerKey (virtual-thread workers)
                 ▼
           @GameController business handlers              read/write game-jpa caches/repositories
                 │ reply / push / broadcast                EventBus.publishEvent
                 ▼                                          ▼
           session.writeMsg (write back on IO thread)  @EventHandler listeners
```

- **IO threads only do codec work and routing checks**; all business runs in executor groups. The same routerKey (e.g. the same player) is strictly serial, so business code needs no locks.
- **Replies/pushes return as soon as the session write is issued**; write failures are dropped by the network layer — forwarding failures are never retried or buffered.

## External Frame Format

```
bodyLength(int) | command(int) | seq(int) | code(int) | flags(byte) | body(bytes)
```

| Field | Description |
|---|---|
| bodyLength | body byte count, capped at 1 MiB (`GamePacketConstant.MAX_BODY_LENGTH`); exceeding it disconnects |
| command | business command number; `1~999` is reserved for server-initiated system commands (e.g. kick-offline=1), business starts from `1000` |
| seq | client request sequence number, echoed back in the reply for correlation; always `0` for server-initiated pushes |
| code | error code (see table below), `0`=success; body is empty when non-zero |
| flags | reserved (compression/encryption markers) |
| body | Fory-serialized business object; all external DTOs live in the `cn.managame.dev.message` package and are registered wholesale at startup by `ForyMessageRegistrar` (Fory requires class registration by default — unregistered types throw outright, closing the arbitrary-class deserialization attack surface) |

### Error Codes (`GameErrorCode`)

| code | Meaning |
|---|---|
| 0 | OK |
| 1 | INTERNAL_ERROR: handler threw an unrecognized exception |
| 2 | UNKNOWN_COMMAND: command not registered |
| 3 | BAD_REQUEST: body deserialization failed or arguments invalid |
| 4 | NOT_LOGGED_IN: called a command outside the LOGIN group without logging in |
| 5 | SERVER_BUSY: worker queue full, task dropped (terminal, no retry) |
| 6 | DUPLICATE_LOGIN: kick reason — same account logged in elsewhere |

Clients are never silently hung: unknown commands, bad packets, not-logged-in, business rejections, handler crashes and server-busy all get an error-code frame back with the original command/seq.

## Session Lifecycle

- **Login gate**: `GameRouterManager` requires the session to have a bound roleId for any command outside the LOGIN group, otherwise it replies `NOT_LOGGED_IN`.
- **Duplicate login**: when `PlayerSessionManager.bind` finds the same roleId already online, it first pushes a kick-offline frame to the old connection (`command=1, seq=0, code=DUPLICATE_LOGIN`), closes it after the write completes; conditional unbinding guarantees a kick-then-reconnect never removes the new session by mistake.
- **Idle kick**: read idle beyond 180 seconds (`CustomTcpPipelineConfigurator.DEFAULT_READER_IDLE_SECONDS`) disconnects outright; clients keep alive via heartbeat (command=1001). When the link is unhealthy, no frame is pushed — just close.

## Unified Task Failure Handling (`GameTaskFailureReplier`)

Registered at startup as the global `GameTaskExceptionHandler` + `GameTaskMonitor`:

- handler throws `GameBusinessException(code, msg)` → reply with that code, no stack trace (business rejection is normal flow);
- handler throws anything else → error log + reply `INTERNAL_ERROR`;
- task dropped due to full worker queue → reply `SERVER_BUSY`;
- slow tasks (≥1s) log warn.

Only COMMAND tasks have a requester to reply to; EVENT/TIMER failures are logged only.

## Writing Business Logic

**Command handlers**: `@GameController(group=...)` on the class, `@GameMethod(value=commandNumber)` on the method.
For LOGIN-group methods the first parameter is `PlayerSession` (roleId not yet bound); for other groups it is `Long roleId`; the second parameter is the message DTO.
The LOGIN group uses `routerKeyMethod` to extract a key from the message for spreading (e.g. `getUserId`), so a login surge doesn't serialize onto a single worker:

```java
@GameController(group = ExecutorGroups.LOGIN)
public class LoginController {
    @GameMethod(value = 1000, routerKeyMethod = "getUserId")
    public void login(PlayerSession session, LoginReq req) {
        if (req.getUserId() <= 0) {
            throw new GameBusinessException(GameErrorCode.BAD_REQUEST, "invalid userId");
        }
        // ... bind, read/write repositories ...
        gameRouterManager.reply(loginRes);                       // reply to the current request
        EventBus.getInstance().publishEvent(new PlayerLoginEvent(...)); // side-path logic goes through events
    }
}
```

**Replies and pushes** (`GameRouterManager`):

| Method | Semantics |
|---|---|
| `reply(msg)` | Reply to the current request (command/seq from the task context, code=OK); callable only from the handler thread |
| `replyError(code)` | Reply to the current request with an error code; throwing `GameBusinessException` is generally preferred |
| `push(roleId, command, msg)` | Server-initiated push, seq=0, dropped when offline; callable from any thread |
| `broadcast(command, msg)` | Broadcast to all online players, body serialized only once |

**Events**: `IGameEvent.routerKey()` decides the serialization key; listener classes use `@EventHandler(group=...)` + `@EventMethod` methods, managed by Spring scanning and registered into `EventBus` at startup. Execution inlines when the listener shares the publisher's group and routerKey, otherwise the event is submitted to the listener's group.
Example: `PlayerLoginEvent` / `PlayerLoginEventHandler`.

**Timers**: uniformly `TimingWheel.getInstance()` (hashed timing wheel; ScheduledExecutorService is banned).
Periodic tasks = one-shot schedule + self-renewal inside the task (see `Game.scheduleOnlineReport`).
Only lightweight bookkeeping on the wheel thread; heavy work is wrapped into tasks and submitted to executor groups.

**Message DTOs**: just place them in the `cn.managame.dev.message` package; `ForyMessageRegistrar` registers the whole package at startup, sorted by class name — no manual registration.

## Startup & Shutdown

Startup order (`Game.main`): Spring scan (include filters take over `@GameController`/`@EventHandler`) →
register game-jpa Repository singletons before refresh → refresh → register executor groups → register commands/events →
register Fory message types → wire the task-failure replier → start Netty → schedule timers.

Shutdown is an **ordered sequence inside a single shutdown hook** (not split into multiple hooks — the JVM runs them in parallel and order would be lost):

```
stop the timing wheel (no new tasks) → stop Netty (close connections, stop accepting requests) →
drain executor groups (in-flight business finishes writing caches) → close GameJpaContext (flush the async write queue to the database) → close the Spring container
```

## Configuration

`-D` system properties first, then environment variables, then defaults:

| System property | Environment variable | Default |
|---|---|---|
| `game.server.port` | `GAME_SERVER_PORT` | `8080` |
| `game.db.url` | `GAME_DB_URL` | `jdbc:mysql://localhost:3306/test` |
| `game.db.username` | `GAME_DB_USERNAME` | `root` |
| `game.db.password` | `GAME_DB_PASSWORD` | `123456` |

## Running

```bash
# Server (needs local MySQL; tables auto-created from entities)
mvn -q -pl game-dev exec:java -Dexec.mainClass=cn.managame.dev.Game

# Interactive test client (login / ping / send / raw — see GameClientMain help)
mvn -q -pl game-dev exec:java -Dexec.mainClass=cn.managame.dev.client.GameClientMain

# Tests (the end-to-end integration test does not depend on MySQL)
mvn -pl game-dev test
```

`ServerFrameworkIntegrationTest` covers framework behavior over the real pipeline: the login gate, error frames for unknown commands / bad packets, replies for business exceptions and crashes, duplicate-login kick pushes, and idle kick.
