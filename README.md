English | [中文](README.zh-CN.md)

# managame

Game server components on Java 25 and Netty 4.2. Each is a project of its own, so an application takes only the
ones it needs:

| Project | Depends on | What it is |
| --- | --- | --- |
| [managame-common](managame-common/README.md) | nothing | `Metadata`, `MetadataKey`, unique id generators, the shared error codes, and `Metrics`: counters, gauges and timers an application binds to its own backend |
| [managame-network](managame-network/README.md) | common, Netty (the modules it uses) | TCP / TLS / ws / wss servers, an HTTP/1.1 server and a reconnecting client, with any Netty handler |
| [managame-runtime](managame-runtime/README.md) | common, network | Every piece of business logic through one entry, with tasks of one domain and route key run one at a time: routing per domain, annotated handlers (`Dispatcher`, `EventBus`, `HttpRoutes`), interceptors and timers |
| [managame-rpc](managame-rpc/README.md) | common, network | Calls and notifies between server processes: `RpcNode`, `RpcPeer`, symmetric connections over TCP, restart detection, admission |
| [managame-data](managame-data/README.md) | common, Caffeine | Game data in memory, written behind it in batches wherever the application chooses: entities by id (`SingleRepository`), groups of entities (`GroupRepository`) and logs (`LogRepository`), through an `EntityMapper` and a `LogWriter` |
| [managame-data-mysql](managame-data-mysql/README.md) | data | `MysqlEntityMapper` and `MysqlLogWriter`: entities and logs in MySQL tables |
| [managame-data-mongo](managame-data-mongo/README.md) | data, MongoDB driver | `MongoEntityMapper`: entities in MongoDB collections |

An application that takes several components imports [managame-bom](managame-bom/README.md) for one version of
all: it holds versions only, of the components and the Netty modules they share.

[managame-benchmark](managame-benchmark/README.md) holds JMH benchmarks for every component, with a recorded
baseline each, so that a change that makes a component faster or slower shows at once.

runtime and rpc know nothing of each other. An application that uses both joins them in its own code: its
`RpcHandler` hands peers' requests to its `Dispatcher`, and its `RpcCallback`s run their work through its
`GameRuntime`; see [Putting the components together](#putting-the-components-together).

## Putting the components together

No component knows the ones beside it, so the application joins them. What it writes is little, and much the same in
every game; [managame-example](managame-example) has it written out.

```
clients          --> network --> gateway    --+
                                              +--> Dispatcher --> GameRuntime --> handlers --> data repositories
other processes  --> rpc     --> RpcHandler --+                       ^              |
                                                                      +--------------+
                                              RPC answers, timers and events come back as tasks
```

Messages from clients and from other processes both reach the `Dispatcher` through an entry the application writes,
and run one at a time on a route of the `GameRuntime`; handlers read and change repositories, call other processes,
set timers and publish events, and what comes of those returns as tasks on a route again.

### What the application writes

| Where two components meet | The application's | What it does |
| --- | --- | --- |
| Clients come in | a `ConnectionHandler`, its gateway | Keeps a session per connection, builds each message's context from it, dispatches, and writes the answer in its own packet format ([runtime: Dispatcher](managame-runtime/README.md)) |
| Other processes come in | an `RpcHandler` | Decodes a request and dispatches it as the gateway does, to the same handlers, answering with `rpcPeer.reply(connection, response)` ([rpc](managame-rpc/README.md)) |
| A task calls another process | a wrapper round `peer.call` | Decodes the answer in the callback, where it is released on return, then goes back to the calling task's route with `runtime.execute(context, task)`: a callback runs on an EventLoop, never on the route |
| Who is online | a map of roles to sessions | Pushes to a player outside a request, and finds the session a new login replaces |
| A player online | an object of its own per player, in memory | Holds what lasts as long as the player is online, such as the session and a `TimerGroup`; never a `Context`, which each message brings |

A session is the application's and never a context: the gateway builds a context for each message, for who acts now
(`GameContext.of(PLAYER, roleId, ROLE, roleId)`), and a task carries that context to whatever it starts: an event, a
timer, a call to another process, whose answer continues on it.

### A player coming and going

- **Logging in.** A message from a connection not logged in acts for no one, so its handler names the key it runs on,
  such as the account (`@HandleMethod(routeKey = "userId")`). The gateway binds the role to the session before it writes
  the answer, so what the client sends next acts as that role, and puts the session in its map of roles; a session
  already there for that role is the connection this login replaces, which the gateway closes.
- **Online.** Entities are got by id in each task and never kept across tasks; what the player's object keeps is what
  no repository holds.
- **Going offline.** `onInActive` runs on the connection's EventLoop, so it only takes the session out of the map and
  hands the rest to the player's route, where the player's timers are cancelled:

  ```java
  @Override public void onInActive(Connection connection) {
      Session session = connection.get(SESSION);
      if (session.roleId() == 0 || !sessions.remove(session.roleId(), session)) return;   // never logged in, or replaced by a new login
      try {
          runtime.execute(GameContext.of(PLAYER, session.roleId(), ROLE, session.roleId()), context -> players.leave(session.roleId()));
      } catch (GameRuntimeException refused) {
          // an entry like any other: refused while the player's route is full, or once the runtime shut down
      }
  }

  void leave(long roleId) {                    // on the player's route
      online.remove(roleId).timers().cancelAll();
  }
  ```

  The data needs nothing done: what the player changed is written in the next round whether or not it stays in memory,
  and leaves memory by itself when unused for `cacheExpire`. Only a player moving to another process is flushed and
  evicted first, so that process reads the newest.

### Starting and stopping

Start from the bottom and open the doors last, so a message that arrives finds everything behind it ready:

1. `GameData.build()`: reads the entity classes, has each mapper check its tables, loads the preloaded ones.
2. `GameRuntime.build()`, then the `Dispatcher`, `EventBus`, `HttpRoutes` and `GameTimer` on it.
3. `RpcNode.start()` with the `RpcHandler`, then `createPeer` for the nodes this one dials. What must be done with a
   peer before serving, such as registering with a world server, starts from `RpcListener.onConnected`.
4. `NetworkServer.start(...)` for clients.

Stop in the opposite order. Each step ends what the next would otherwise wait for or lose:

1. **Close the servers clients connect to.** No message comes in any more; with the server's own threads its
   connections close too, and each `onInActive` has run when `close()` returns, so every player goes offline the usual way.
2. **Close the `RpcNode`.** Its waiting calls fail with `RPC_UNAVAILABLE` and their callbacks still find the runtime
   taking tasks. Close the `GameTimer` as well; a timer set afterwards comes back cancelled.
3. **Close the `GameRuntime`.** It refuses what comes from outside and waits for every accepted task, and what those
   hand on, to finish. Not from one of its tasks.
4. **Close `GameData` last**, once nothing changes data any more: it writes what the tasks changed, and throws when
   something could not be written, after handing each such change to the `DataErrorHandler`.

A container that closes beans in the reverse order of their dependencies does this by itself when the runtime depends
on the data, the Dispatcher on the runtime, and the servers and the node on the Dispatcher, as the example's does.

### Where code runs

| Thread | Runs | Rule |
| --- | --- | --- |
| EventLoops (network, rpc) | Decoding, the gateway, the `RpcHandler`, RPC callbacks and listeners | Never block: decode, then hand the work to the runtime |
| A route of the runtime | Every handler, event, timer and continuation, one at a time per domain and route key | Business logic, and only here. A load from the database blocks the route it is on, which is what `RouteExecutors.virtualThreads()` is for |
| Timer wheels (runtime, rpc) | Handing due timers to the runtime; the callbacks of RPC calls that timed out | Never block |
| data's write threads | Writing changes, reading each entity as it is written | Keep an entity's collections thread-safe |

A request is always answered, with what its handler returns or with a code from common's `FrameworkErrorCodes`, one
table for every component: RPC 2001–2999, runtime 3001–3999, data 4001–4999. And one `Metrics` and one logging bridge,
bound once, serve them all.

## Logging

Every component logs through `System.Logger`, the JDK's own logging facade, and depends on no logging library. By
default it goes to java.util.logging; to send it to Logback or another SLF4J backend, or to Log4j 2, add one bridge to
the application, without changing a line of the framework:

```xml
<!-- Logback, or any SLF4J 2 backend -->
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>slf4j-jdk-platform-logging</artifactId>
  <version>${slf4j.version}</version>
</dependency>

<!-- Log4j 2 -->
<dependency>
  <groupId>org.apache.logging.log4j</groupId>
  <artifactId>log4j-jpl</artifactId>
  <version>${log4j.version}</version>
</dependency>
```

Loggers are named `cn.managame.<component>`, and below it by area, so one setting turns a component up or down:

| Logger | Component |
| --- | --- |
| `cn.managame.network`, `.network.http` | network |
| `cn.managame.runtime`, `.runtime.dispatch`, `.runtime.event`, `.runtime.http`, `.runtime.timer` | runtime |
| `cn.managame.rpc` | rpc |
| `cn.managame.data`, `cn.managame.data.mysql` | data, data-mysql |

Each component's README lists what its loggers say. Levels mean the same everywhere:

| Level | When |
| --- | --- |
| ERROR | Something is lost, or handed to an error handler |
| WARNING | Work refused, skipped or tried again |
| INFO | Lifecycle: a server listening or stopped, a peer joining or leaving, a component started or closed, what was registered |
| DEBUG | Detail per connection, call or write round; nothing per message at INFO, and DEBUG costs nothing while off |

For example with Logback, INFO for the framework and DEBUG for rpc:

```xml
<logger name="cn.managame" level="INFO"/>
<logger name="cn.managame.rpc" level="DEBUG"/>
```

## Build

One command at the root builds every project in dependency order, the benchmarks' jar last:

```bash
mvn install
java -jar managame-benchmark/target/benchmarks.jar all
```

The root `pom.xml` only aggregates: no project inherits from it, and each still builds on its own once the projects
below it are installed, such as `cd managame-runtime && mvn install`.

Tests open real loopback connections, and the data tests use an in-memory database. Conventions for contributors and coding agents are in [AGENTS.md](AGENTS.md).
