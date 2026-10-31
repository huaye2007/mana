# AGENTS.md

Guidance for coding agents working in this workspace. Read it before changing anything.

## Workspace

Seven component projects and a benchmark project, Maven with no parent POM, all `cn.managame:*:1.0.0-SNAPSHOT` on
Java 25. The workspace is the git repository https://github.com/huaye2007/mana (branch `main`).

| Project | Depends on | What it is |
| --- | --- | --- |
| `managame-common` | nothing | `Metadata`, `MetadataKey`, `FrameworkErrorCodes` |
| `managame-network` | Netty 4.2.19 (`netty-all`) | TCP / TLS / ws / wss servers, HTTP/1.1 server, reconnecting client |
| `managame-runtime` | common, network | `GameRuntime` (the single entry for business logic), routing, `Dispatcher`, `EventBus`, `GameTimer`, HTTP routes |
| `managame-rpc` | common, network | `RpcNode` / `RpcPeer`: calls and notifies between server processes over symmetric TCP connections, membership the application drives, restart detection and admission |
| `managame-data` | common, Caffeine | `SingleRepository` / `GroupRepository` / `LogRepository`: game data in memory, written behind it in batches through the `EntityMapper` and `LogWriter` the application picks; knows no database |
| `managame-data-mysql` | data | `MysqlEntityMapper`, `MysqlLogWriter`: entities and logs in MySQL over JDBC |
| `managame-data-mongo` | data, MongoDB sync driver | `MongoEntityMapper`: entities in MongoDB |
| `managame-benchmark` | all but data-mongo, JMH 1.37, H2 | JMH benchmarks per component with recorded baselines (`results/*.csv`); a tool nothing depends on |

## Build and test

- Build each project on its own, in dependency order: common → network → runtime and rpc (which do not depend on
  each other), and common → data → data-mysql and data-mongo. A project sees another
  only through the local Maven repository (`d:\maven_repository`), so after changing a lower project run
  `mvn install` in it before building the projects above it.
- Tests open real loopback sockets. Run them outside a sandbox, and give the JDK a writable directory for its
  Unix-domain selector sockets:

  ```bash
  mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
  ```

- Use `clean` after deleting or moving classes: stale classes left in `target/` are otherwise packaged into the
  installed jar.
- Tests are JUnit 5 under each project's `src/test/java`. Every behavior change comes with tests; network,
  runtime and rpc tests use real connections rather than mocks. data's tests run on an in-memory `EntityMapper` that
  behaves as a database does; data-mysql's on H2 in MySQL mode, a real database in memory; a real MySQL or MongoDB
  only when `MysqlIntegrationTest` or `MongoIntegrationTest` is given its address.
- managame-benchmark builds last (`mvn -o clean package`), after the components are installed.

## Benchmarks

Every component has JMH benchmarks in `managame-benchmark`, package `cn.managame.benchmark.<component>`, and a
baseline in `managame-benchmark/results/<component>.csv`; its README has the details.

- After changing a component's code, run its benchmarks from `managame-benchmark` and read the comparison:
  `java -Djdk.net.unixdomain.tmpdir=D:\tmp -jar target/benchmarks.jar <component>`. Report results marked
  `WORSE`, `MORE ALLOCATION` or `MORE BYTES`, and look into them before calling the change done. Each result also
  shows CPU per operation by kind of thread (caller, eventloop, other, jvm), GC, Netty's direct memory and bytes on
  the wire; use them to tell where a cost went.
- The machine moves results too: the maintainer's laptop has performance and efficiency cores, and Windows at
  times runs background benchmarks on the slower ones, halving every result with the code unchanged. When a result
  is `WORSE` and its allocation did not move, build the code before the change into a jar and rerun with
  `--against=<that jar>` before looking for a cause in the code. Only a difference that holds against it counts; a
  large regression that holds is traced to its cause and fixed, a small one is explained. `unsure` means run again
  on a steadier machine.
- When a change makes a component faster or slower on purpose, save the baseline (`--save`) and commit `results/`
  with the change. Never save over a baseline to hide a regression, nor from a run taken while the machine was
  busy; Bench refuses to save a component during which other programs used more than a tenth of the CPU.
- A benchmark that talks over sockets dials through `CountingSocketChannel`, so its bytes on the wire are counted.
  One with a server side also has a `…ProcessBenchmark` that runs the server in a process of its own
  (`PeerProcess`), whose results show client and server costs apart.
- A new feature on a hot path, or a new component, comes with benchmarks of its own, in the package of the
  component it measures and using only that component and those below it.
- Baselines compare only on the machine they were recorded on, named on their first line.

## Design principles

These come from the maintainer. Follow them, and ask when a change would bend one.

- **Think from first principles.** Design from the requirement, not from earlier implementations or other
  projects' code.
- **Projects stay independent.** Each component can be used on its own: runtime and rpc are separate components
  that know nothing of each other, and an application that uses both joins them in its own code. Do not add
  bridge or integration projects. A project depends only on the projects below it and never mentions the ones
  above or beside it. The one shared place is common's `FrameworkErrorCodes`, the code table every project draws
  from.
- **Discuss before large changes.** Propose the design and confirm intent before broad edits or API changes; the
  maintainer has reverted rounds that went beyond what was asked. Small, clearly requested changes need no
  discussion.
- **Minimal state.** A class holds only the fields its job needs, like a plain Netty handler. Do not wrap what
  Netty already exposes: socket options go through `ServerBootstrap` / `Bootstrap`, any `ChannelHandler` can be
  added as is.
- **Simple over rare cases.** Do not build mechanisms for situations that seldom happen; they are hard to
  maintain and cost speed. Document what happens instead: an answer written while a peer's only connection is
  being dialed again is lost, rather than held for the connection to come back. Name methods plainly for what
  they do (`call.fail(code)`, `pending.failAll(code)`).
- **The network layer never stores connections.** The application creates, maps, keeps and closes them
  (`ConnectionMapper`). RPC is the deliberate exception: an `RpcPeer` holds its connections because calls travel
  over them.
- **Protocols belong to the application.** The framework defines no client packet format; `Dispatcher` takes any
  decoded message plus reply and fail callbacks in the application's format.
- **Components carry no business concepts.** rpc knows nodes by id only: what a node is for (game, world, gate)
  travels in its handshake `Metadata`, and grouping peers and picking one for a key are the application's.
  Message fields such as `command`, `businessType` and `businessId` are carried as is, never interpreted.
- **Reference counting follows Netty.** Bodies are `ByteBuf`s, never copied on the way; RPC messages are
  `ReferenceCounted` through them. What is handed to the framework becomes its own; what the framework hands to a
  callback is released when it returns.
- **One entry for business logic.** In an application on the runtime, player requests, events, timers, HTTP and
  (through the application's own RpcHandler) RPC all run through `GameRuntime.execute`. Tasks with the same `(domain, routingKey)` run one at a time in submission order.
- **Every context has the same fields:** `domain` (int), `routingKey` (long), `businessType` (byte),
  `businessId` (long) and `Metadata`.
- **The business decides routing per domain.** Each domain has its own `RouteExecutor` and its own
  `RouteKeyResolver`: the key may come from the source context (a player's id) or from the message (a guild id
  marked `@RouteKey`).
- **Handlers are annotated and registered in batches at startup:** `@HandleMethod`, `@EventMethod`,
  `@HttpMethod`, placed by `@Domain`. A handler takes one message parameter, optionally preceded by its context.
  Registrations are immutable once built.

## Names and API conventions

- Names the maintainer chose; keep them: `GameTask` (not ContextTask), `GameContext.copyOf` (not "derive"),
  `RequestContext` with `reply` / `fail`, `HttpExchange`, `RpcNode` (this process), `RpcPeer` (another process,
  connected either way, possibly over several connections), `RpcRequest` (with `requestId`) / `RpcNotify`
  (without) / `RpcResponse` (`requestId`, `code`, `metadata`, `body`), `node.createPeer` / `removePeer`,
  `node.peer(id).call(request, callback)` with `RpcCallback.onResponse(request, response)`, `peer.notify(notify)`,
  `node.broadcast(peers, notify)`, `RpcHandler.handleMessage(RpcPeer rpcPeer, Connection connection, Object msg)`
  answered with `response.writeTo(connection)`, `RpcListener.onConnected(peer, restarted)`, `incarnation` (a
  running instance of a node), `node.close()`, `RpcAuthenticator`, handshake and heartbeat (the internal frames).
- rpc decisions the maintainer made: TCP only; waiting calls belong to the `RpcPeer`, under its own request ids,
  so an answer may come back on any connection of the same instance, and they fail at once when the peer's last
  connection closes; a callee pushes back by pausing reads with Netty's `autoRead`, with no API of rpc's own; a
  route key hashes to a fixed connection and
  falls back to another one up while that one is down; heartbeats are the dialing side's; there is no Goodbye or
  drain protocol, as a node announces going offline itself; callbacks never run on the calling thread; one time
  wheel per node does all its timing (call timeouts, heartbeats); the handshake negotiates nothing, so the nodes
  of a cluster share one protocol version, heartbeat interval and largest frame.
- data names and decisions the maintainer made: `GameData` with `GameData.builder()`, `SingleRepository<K, E>`
  (by `@Id`), `GroupRepository<K, E>` (groups by `@GroupKey`, `GroupKeys.of(...)`, a map per group by `@MapKey`,
  `MapKeys.of(...)` for several), `LogRepository<E>`, repositories as empty subclasses the builder creates, registered
  as a collection with the `EntityMapper` or `LogWriter` that keeps them (`repositories(mapper, types)`,
  `logRepositories(writer, types)`). managame-data knows no database: MySQL and MongoDB are projects of their own
  (`MysqlEntityMapper` with `@Table`, `MongoEntityMapper` with `@Collection`, `MysqlLogWriter`), and where logs go,
  such as a service over HTTP, is the application's `LogWriter`; each mapper and writer decides what is retryable.
  Changes are recorded, not copied: the write thread reads the entity when it writes it, so business threads pay no
  copy and entity collections must be thread-safe. The caches are Caffeine's, used through its own API with no
  wrapper: it loads a key once however many threads ask, and expires and bounds entries. Game data has only these two
  shapes plus logs; the application owns the schema (no DDL but MySQL log partitions created `LIKE` their base table).
  A table kept whole in memory registers with `preloadedRepositories` (read at build by `loadTable`, never leaves
  memory, listed by `all()`). The framework wraps no database API: the application queries its own `DataSource` or
  `MongoDatabase`, calls `flush()` first to read the newest, and changes the rows repositories hold only through them,
  so there is no reload or evictAll.
- data's packages split by who uses them: `cn.managame.data` for business code (GameData, repositories, keys,
  failures), `annotation` for the entity annotations (`mysql.annotation`, `mongo.annotation` in the backend projects),
  `spi` for what a backend implements and reads (`EntityMapper`, `LogWriter`, `EntityType`, `EntityField`, read-only
  `Change`, `RetryableFailures`), and `internal` for the implementation: public only to be reachable across packages,
  marked not API, with no `module-info.java`. A helper used by one package stays package-private there.
- Error codes live in `FrameworkErrorCodes` (common): RPC 2001–2999, runtime 3001–3999, data 4001–4999. Failures
  of a request reach its callbacks as codes; misuse throws `IllegalArgumentException` (bad argument) or
  `IllegalStateException` (missing setting, wrong state).
- A method that sends one message returns network's `WriteStatus` (`connection.write`, `peer.notify`,
  `response.writeTo`), not a boolean; one that sends to many returns how many accepted it.
- Builders validate eagerly, and `build()` / `start()` return a ready object with no "not started" state.
- Netty EventLoop callbacks never block, and business logic never runs on EventLoop or timer threads: they hand
  work to the runtime (or, in a component used on its own, to the application's executor).

## Code style

- Match the surrounding code: compact methods, Javadoc on public API that states behavior, threading and
  failure modes, few inline comments, records for values, sealed interfaces for closed sets.
- Log with `System.Logger`.
- Keep test fixtures small and readable; tests double as usage examples (for instance
  `PlayerGatewayExampleTest` shows an application-defined player gateway).

## Documentation

- Every project has a README in English (`README.md`, the default) and in Chinese (`README.zh-CN.md`). Each
  links to the other on its first line.
- When public API or behavior changes, update both READMEs in the same change and keep their code samples in
  line with the current API.
- Update this file when a convention changes.
