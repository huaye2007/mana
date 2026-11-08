# AGENTS.md

Guidance for coding agents working in this workspace. Read it before changing anything.

## Workspace

Seven component projects, a BOM and a benchmark project, Maven with no parent POM, all `cn.managame:*:1.0.0-SNAPSHOT` on
Java 25. The root `pom.xml` only aggregates them, to build all in one command; no project inherits from it. The workspace is the git repository https://github.com/huaye2007/mana (branch `main`).

| Project | Depends on | What it is |
| --- | --- | --- |
| `managame-common` | nothing | `Metadata`, `MetadataKey`, `IdGenerator` (`SnowflakeIdGenerator`, `SequenceIdGenerator`), `FrameworkErrorCodes`, `Metrics` (`cn.managame.metrics`) |
| `managame-network` | common, Netty 4.2.19, the modules it uses (common, buffer, transport, handler, codec-base, codec-http, epoll), never `netty-all` | TCP / TLS / ws / wss servers, HTTP/1.1 server, reconnecting client |
| `managame-runtime` | common, network | `GameRuntime` (the single entry for business logic), routing, `Dispatcher`, `EventBus`, `GameTimer`, HTTP routes |
| `managame-rpc` | common, network | `RpcNode` / `RpcPeer`: calls and notifies between server processes over symmetric TCP connections, membership the application drives, restart detection and admission |
| `managame-data` | common, Caffeine | `SingleRepository` / `GroupRepository` / `LogRepository`: game data in memory, written behind it in batches through the `EntityMapper` and `LogWriter` the application picks; knows no database |
| `managame-data-mysql` | data | `MysqlEntityMapper`, `MysqlLogWriter`: entities and logs in MySQL over JDBC |
| `managame-data-mongo` | data, MongoDB sync driver | `MongoEntityMapper`: entities in MongoDB |
| `managame-bom` | nothing | Versions only: every component and the Netty modules they share, for applications importing several |
| `managame-benchmark` | all, JMH 1.37, H2, MySQL driver, HikariCP | JMH benchmarks per component with recorded baselines (`results/*.csv`), plus `mysqlserver` and `mongo` against real servers; a tool nothing depends on |

## Build and test

- Build everything from the root, which runs the projects in dependency order: common → network → runtime and rpc
  (which do not depend on each other), and common → data → data-mysql and data-mongo, then the benchmark. A project
  built on its own sees another only through the local Maven repository (`d:\maven_repository`), so after changing a
  lower project install it before building the projects above it.
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
- managame-benchmark builds last in the root build; on its own, `mvn -o clean package` after the components are
  installed. Its jar stays in `target/` and is never installed.
- A component depends on the modules of a library it uses, not on an aggregate of all (`netty-all`), so an
  application gets few jars and keeps its own choice of the rest; managame-bom pins the versions components share.

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
- **Correctness comes from structure and order, not bookkeeping.** Do not keep counters of operations in flight (adds
  in progress, tasks pending) to make close or shutdown correct: a design that needs one lets things race that the
  lifecycle should order. GameData closes last, after the business threads have stopped, so no insert races with it;
  waiting for work to finish reads the state the structure already has (an executor's own queues).
- **The network layer never stores connections.** The application creates, maps, keeps and closes them
  (`ConnectionMapper`). RPC is the deliberate exception: an `RpcPeer` holds its connections because calls travel
  over them.
- **Protocols belong to the application.** The framework defines no client packet format; `Dispatcher` takes any
  decoded message plus one callback in the application's format, which receives a `HandleResult` (`code`, `data`,
  `args`) for every outcome. A failure is its code and string args (`fail(code, args...)`), handed over as they are
  for the application to place, in its failure packet's body say; HTTP shows a text rather than args, which its
  codec's `encodeFailure(code, args)` makes from them. A session (the connection, the role it
  is logged in as) is the application's and never a `Context`: its entry builds each message's context (who acts,
  the message's metadata) when it dispatches, and the answer goes back through the callback, which holds the
  connection; pushing to a player outside a request goes through the application's own map of roles to sessions.
- **Components carry no business concepts.** rpc knows nodes by id only: what a node is for (game, world, gate)
  travels in its handshake `Metadata`, and grouping peers and picking one for a key are the application's.
  Message fields such as `command`, `businessType` and `businessId` are carried as is, never interpreted.
- **Reference counting follows Netty.** Bodies are `ByteBuf`s, never copied on the way; RPC messages are
  `ReferenceCounted` through them. What is handed to the framework becomes its own; what the framework hands to a
  callback is released when it returns. What an entry holds for a task goes back at the end of its `run` and in its
  `GameTask.refused`, which runs instead when an interceptor or a refusal keeps it from running.
- **One entry for business logic.** In an application on the runtime, player requests, events, timers, HTTP and
  (through the application's own RpcHandler) RPC all run through `GameRuntime.execute`. Tasks with the same `(domain, routeKey)` run one at a time in submission order.
  Work that comes back from another thread or route (an RPC callback, a visit to another player's route) goes on
  through `GameRuntime.execute(context, task)` with the context to continue on; a game task that needs a result from
  another route uses `GameRuntime.call(target, work, then)`, which is the same two steps and never waits, as business
  code must not depend on the kind of thread under it. Called from a
  game task it is never refused, for load or shutdown, so what was accepted finishes what it began; any other caller is
  an entry, an RPC callback too, and shutdown refuses entries first and shuts executors down once none has work. A
  request is answered whether its task is accepted or refused. What runs around business logic (a trace id in the logs, saving
  what a task changed, a login check) is a `TaskInterceptor` of the runtime (`GameRuntime.builder().intercept`),
  around every task whatever its entry, a continuation included; entries have no interceptors of their own, and checks
  that need the message itself belong in the application's entry before it dispatches. A `before` refuses a task by
  returning false, answering a request it refuses itself; a refusal is no error.
- **Every context has the same fields:** `domain` (int), `routeKey` (long), `businessType` (byte),
  `businessId` (long) and `Metadata`.
- **The business decides routing per domain, and a handler may name its message's key.** Each domain has its own
  `RouteExecutor` and its own `RouteKeyResolver`, set once for the whole domain: by default the source's business id
  (the acting player). A handler whose message carries its key names that member in its annotation
  (`@HandleMethod(routeKey = "guildId")`, also `@EventMethod` and `@HttpMethod`): a method, its getter or a field,
  compiled into a method handle at registration, so a missing member fails at startup and a message pays one call.
  Only the handlers that need it name one, since messages name the same key differently; a domain's key keeps one
  meaning (a guild's id in a guild domain).
- **Handlers are annotated and registered in batches at startup:** `@HandleMethod`, `@EventMethod`,
  `@HttpMethod`, placed by `@Domain`. A handler takes one message parameter, optionally preceded by its context.
  A `@HandleMethod` returns its `HandleResult` (`ok(data)`, `fail(code, args...)`), or is void when the answer
  comes later through its `HandleContext` (`reply` / `fail`), so success, failure and a later answer reach the
  application one way; only a void method answers later, and null from any other is a bug, answered with
  `RUNTIME_EXECUTION_ERROR`. An `@HttpMethod` returns any result: an `HttpResult` (`code`, `data`, `msg`) for an API's
  common shape, a type of its own that the codec writes for a format the other side fixes, or a `FullHttpResponse`;
  its failures go to the codec's `encodeFailure` whatever it returns. Game logic hands its failures up as it hands up
  its results and never throws them; an exception is a bug, answered with `RUNTIME_EXECUTION_ERROR` and handed to the
  error handler.
  Registrations are immutable once built. `@Cron` methods (registered with `GameTimer.register`) are the exception:
  they run at calendar times on a fixed route, so the annotation carries `domain` and `routeKey`, and they take no
  message, only optionally their `GameContext`.
- **One clock and time zone for the game.** Business code reads dates and times from `GameTime`, whose zone is set
  once (`GameTime.setZone`), never per annotation or call. Cron timers read their times on it and reschedule within
  a second when its clock or zone changes; delays, periodic timers and timeouts use the monotonic clock.

## Names and API conventions

- Names the maintainer chose; keep them: `GameTask` (not ContextTask), `GameContext.copyOf` (not "derive"),
  `RequestContext` with `reply` / `fail`, `HttpExchange`, `RpcNode` (this process), `RpcPeer` (another process,
  connected either way, possibly over several connections), `RpcRequest` (with `requestId`) / `RpcNotify`
  (without) / `RpcResponse` (`requestId`, `code`, `metadata`, `body`), `node.createPeer` / `removePeer`,
  `node.peer(id).call(request, callback)` with `RpcCallback.onResponse(request, response)`, `peer.notify(notify)`,
  `node.broadcast(peers, notify)`, `RpcHandler.handleMessage(RpcPeer rpcPeer, Connection connection, Object msg)`
  answered with `rpcPeer.reply(connection, response)`, `RpcListener.onConnected(peer)`, `incarnation` (a
  running instance of a node), `node.close()`, `RpcAuthenticator`, handshake and heartbeat (the internal frames).
- rpc decisions the maintainer made: TCP only; waiting calls belong to the `RpcPeer`, under its own request ids,
  so an answer may come back on any connection of the same instance, and they fail at once when the peer's last
  connection closes; a callee pushes back by pausing reads with Netty's `autoRead`, with no API of rpc's own; a
  route key hashes to a fixed connection and
  falls back to another one up while that one is down; heartbeats are the dialing side's; there is no Goodbye or
  drain protocol, as a node announces going offline itself; callbacks never run on the calling thread; one time
  wheel per node does all its timing (call timeouts, heartbeats); the handshake negotiates nothing, so the nodes
  of a cluster share one protocol version, heartbeat interval and largest frame; a call fails with `RPC_UNAVAILABLE`
  when its peer is not there and `RPC_OVERLOADED` when it is but can take no more, since a caller answers the two
  differently.
- data names and decisions the maintainer made: `GameData` with `GameData.builder()`, `SingleRepository<K, E>`
  (by `@Id`), `GroupRepository<K, E>` (groups by `@GroupKey`, `GroupKeys.of(...)`, a map per group by `@MapKey`,
  `MapKeys.of(...)` for several), `LogRepository<E>`, repositories as empty subclasses the builder creates, registered
  as a collection with the `EntityMapper` or `LogWriter` that keeps them (`repositories(mapper, types)`,
  `logRepositories(writer, types)`). managame-data knows no database: MySQL and MongoDB are projects of their own
  (`MysqlEntityMapper` with `@Table`, `MongoEntityMapper` with `@Collection`, `MysqlLogWriter`), and where logs go,
  such as a service over HTTP, is the application's `LogWriter`; each mapper and writer decides what is retryable.
  Changes are recorded, not copied: the write thread reads the entity when it writes it, so business threads pay no
  copy and entity collections must be thread-safe. The caches are plain Caffeine, `Caffeine.newBuilder()
  .expireAfterAccess(cacheExpire)` with no further tuning or wrapper: it loads a key once however many threads ask.
  What reads return is handed over as is, without unmodifiable wrappers: it is for reading. Game data has only these two
  shapes plus logs; the application owns the schema (no DDL but MySQL log partitions created `LIKE` their base table).
  A table kept whole in memory registers with `preloadedRepositories` (read at build by `loadTable`, never leaves
  memory, listed by `all()`). The framework wraps no database API: the application queries its own `DataSource` or
  `MongoDatabase`, calls `flush()` first to read the newest, and changes the rows repositories hold only through them,
  so there is no reload or evictAll. Game servers trade strong consistency for speed: data is eventually consistent,
  with no atomicity across entities or types, by design. Entities are got by id in each task and never kept across
  tasks; the framework does not guard against a kept instance. `errorHandler` is required, since logging a failure
  loses it. Entity writes and log writes never affect each other: each has its own writer, rounds and thread
  (`game-data` with `game-data-write-N`, and `game-data-log`), `flush()` writes entities and `flushLogs()` logs.
- data's packages split by who uses them: `cn.managame.data` for business code (GameData, repositories, keys,
  failures), `annotation` for the entity annotations (`mysql.annotation`, `mongo.annotation` in the backend projects),
  `spi` for what a backend implements and reads (`EntityMapper`, `LogWriter`, `EntityType`, `EntityField`, read-only
  `Change`, `RetryableFailures`), and `internal` for the implementation: public only to be reachable across packages,
  marked not API, with no `module-info.java`. A helper used by one package stays package-private there.
- Error codes live in `FrameworkErrorCodes` (common): RPC 2001–2999, runtime 3001–3999, data 4001–4999. Failures
  of a request reach its callbacks as codes; misuse throws `IllegalArgumentException` (bad argument) or
  `IllegalStateException` (missing setting, wrong state).
- A method that sends one message returns network's `WriteStatus` (`connection.write`, `peer.notify`,
  `peer.reply`), not a boolean; one that sends to many returns how many accepted it.
- Builders validate eagerly, and `build()` / `start()` return a ready object with no "not started" state.
- What a builder registers in bulk (handlers, interceptors, repositories) it takes as a `Collection`, never as
  varargs, so an application, or a container that scans annotations, hands over what it gathered as is:
  `register(handlers)`, `intercept(interceptors)`, `repositories(mapper, types)`.
- Netty EventLoop callbacks never block, and business logic never runs on EventLoop or timer threads: they hand
  work to the runtime (or, in a component used on its own, to the application's executor).

## Code style

- Match the surrounding code: compact methods, Javadoc on public API that states behavior, threading and
  failure modes, few inline comments, records for values, sealed interfaces for closed sets.
- Log with `System.Logger`, the JDK's own facade, never a logging library: an application routes it to Logback, Log4j 2
  or java.util.logging with a bridge, changing nothing here. Loggers are named `cn.managame.<component>`, and below it
  by area (`cn.managame.runtime.timer`), so one setting turns a component's logs up or down. Levels: ERROR when
  something is lost or handed to an error handler; WARNING when work is refused, skipped or retried; INFO for
  lifecycle (a server listening or closed, a peer joining or leaving, a component started or shut down, what was
  registered); DEBUG for detail per connection, call or round. Never log per message at INFO, and build DEBUG messages
  in a Supplier, so a level that is off costs nothing.
- Report metrics through common's `Metrics`, never a metrics library: the application binds it to its backend as it
  bridges `System.Logger`. A component takes it as a builder setting, `metrics(Metrics)`, `Metrics.noop()` by default,
  never from a global (a network listener also takes a name, its `listener` tag). Metrics are named `managame.<component>.<area>.<what>`, after the loggers. Meters are created
  at build, or when what they measure is added (a domain, a peer), and kept in fields, so an event costs one call with
  no tags built; tag values come from small sets (a domain, an error code's name, a node id), never player ids or
  route keys. What a component already counts or holds is reported as a gauge or a counter read when the backend
  collects, not counted again on the hot path. Every component reports what it refuses, what fails and what it holds.
  network and rpc also report what goes through them, and the runtime each task by its kind (a handler, or for a task
  written by hand the class it was written in), whose meters are made once, so a task builds no name or tag. Who a task
  was for goes in the log, never in a tag. There is no timer: everything is read as a running total, so rpc and the
  runtime add up latency in common's `LatencyBuckets`, reported as counters, and read no clock under `Metrics.noop()`.
- Keep test fixtures small and readable; tests double as usage examples (for instance
  `PlayerGatewayExampleTest` shows an application-defined player gateway).

## Documentation

- Every project has a README in English (`README.md`, the default) and in Chinese (`README.zh-CN.md`). Each
  links to the other on its first line.
- When public API or behavior changes, update both READMEs in the same change and keep their code samples in
  line with the current API.
- Samples show a handler with its message alone. Its context appears only where the sample reads a business field from
  it or answers later: most handlers need neither, and a sample is what people copy.
- Update this file when a convention changes.
