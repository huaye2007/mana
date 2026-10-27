# AGENTS.md

Guidance for coding agents working in this workspace. Read it before changing anything.

## Workspace

Four component projects and a benchmark project, Maven with no parent POM, all `cn.managame:*:1.0.0-SNAPSHOT` on
Java 25. The workspace is the git repository https://github.com/huaye2007/mana (branch `main`).

| Project | Depends on | What it is |
| --- | --- | --- |
| `managame-common` | nothing | `Metadata`, `MetadataKey`, `FrameworkErrorCodes` |
| `managame-network` | Netty 4.2.19 (`netty-all`) | TCP / TLS / ws / wss servers, HTTP/1.1 server, reconnecting client |
| `managame-runtime` | common, network | `GameRuntime` (the single entry for business logic), routing, `Dispatcher`, `EventBus`, `GameTimer`, HTTP routes |
| `managame-rpc` | common, network | `RpcNode` / `RpcPeer`: calls and notifies between server processes over symmetric connections, membership the application drives, restart detection, draining, TLS and admission |
| `managame-benchmark` | all four, JMH 1.37 | JMH benchmarks per component with recorded baselines (`results/*.csv`); a tool nothing depends on |

## Build and test

- Build each project on its own, in dependency order: common → network → runtime and rpc (which do not depend on
  each other). A project sees another
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
  runtime and rpc tests use real connections rather than mocks.
- managame-benchmark builds last (`mvn -o clean package`), after the four components are installed.

## Benchmarks

Every component has JMH benchmarks in `managame-benchmark`, package `cn.managame.benchmark.<component>`, and a
baseline in `managame-benchmark/results/<component>.csv`; its README has the details.

- After changing a component's code, run its benchmarks from `managame-benchmark` and read the comparison:
  `java -Djdk.net.unixdomain.tmpdir=D:\tmp -jar target/benchmarks.jar <component>`. Report results marked
  `WORSE` or `MORE ALLOCATION`, and look into them before calling the change done.
- When a change makes a component faster or slower on purpose, save the baseline (`--save`) and commit `results/`
  with the change. Never save over a baseline to hide a regression.
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
  `node.peer(id).call(request, callback)`, `peer.notify(notify)`, `node.broadcast(peers, notify)`,
  `RpcHandler.handleMessage(RpcPeer rpcPeer, Connection connection, Object msg)` answered with
  `response.writeTo(connection)`, `RpcListener.onConnected(peer, restarted)`, `incarnation` (a running instance of
  a node), `node.close(Duration drain)`, `RpcAuthenticator`, `RpcObserver`.
- Error codes live in `FrameworkErrorCodes` (common): RPC 2001–2999, runtime 3001–3999, data 4001–4999. Failures
  of a request reach its callbacks as codes; misuse throws `IllegalArgumentException` (bad argument) or
  `IllegalStateException` (missing setting, wrong state).
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
