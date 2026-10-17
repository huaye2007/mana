# Framework-wide review and changes (2026-10-14)

**[English](2026-10-14-framework-review.md)** | [简体中文](2026-10-14-framework-review.zh-CN.md)

> Non-normative record. The component specifications hold the current contracts; this page records the findings, the decisions the user confirmed, the changes made and what was deferred.

## 1. Scope

game-core, game-network, game-rpc, game-router, game-runtime, game-data and game-spring were reviewed for maintainability, readability, simplicity and performance. The findings fall into three groups: required changes (correctness or contract risk), performance, and maintainability/readability.

## 2. Required changes: decisions and results

| # | Finding | Decision | Result |
| --- | --- | --- | --- |
| 1 | A call timeout's onFail runs on the timer thread; Javadoc and the Java specification contradicted each other | Keep notifying on the timer thread; business code moves its handling to its own thread in onFail | RpcHandler Javadoc and RPC Java specification aligned |
| 2 | Router hard-coded a 4 MiB frame limit, decoupled from RpcNodeBuilder.maxFrameSize | Keep only the maxFrameSize setting | RouterWire.MAX_FRAME/MAX_INNER removed; new `RpcNode.maxFrameSize()` from which the Router derives its inner limit |
| 3 | Router hard-coded RPC frame offsets | Change | RpcWire publishes frame layout constants that the Router uses |
| 4 | The save thread reads entities that business code may still be changing | No snapshot; collection fields use thread-safe collections | Guidance added to the Data and Data Java specifications |
| 5 | Log queues were unbounded and `size()` was O(n) | Change | `logQueueCapacity` (default 100,000); when full, insert returns false and reports; O(1) counter; new DataStats.droppedLogs |
| 6 | The flush failure flag stayed set forever | Change | flush reports only its own pass; close still reports any drop during the lifetime |
| 7 | Overload rejections carried full stack traces | Change | RuntimeDispatchException captures no stack |
| 8 | GameRpc reported a synchronous failure both as an exception and as an UNAVAILABLE callback | Change | New `CapturedRouteCallback.cancel()`; a synchronous failure only throws |
| 9 | RPC pending calls were unbounded | Change (per Peer) | `maxPendingCalls` (default 65,536), UNAVAILABLE via onFail beyond it; R-SEND-04 changed from Node-wide to per-Peer so one slow Peer cannot consume every Peer's capacity |
| 10 | The cache queried the database inside a CHM lock | Change | Loads run outside the lock, coalesced per key; a mutation during a load waits for it; a load that raced with eviction reloads. Loads stay synchronous by design (D-MODEL-01); the asynchronous accessors this row first claimed were never added and are not wanted (corrected 2026-10-17) |
| 11 | HTTP request and result Jackson configurations differed | Change | Shared JsonSupport configuration |
| 12 | The RpcWire comment said v1 while the wire is v2 | Change | Corrected |

## 3. Performance

- `isPeerConnected` no longer uses a Stream; ServiceRouting picks a Router in two passes without allocating arrays.
- RpcNode's Peer table is a copy-on-write IntObjectHashMap, so sends look up by primitive int; the hasCandidate pre-scan is gone; passive-Peer reclamation checks lock-free first.
- New `NetworkTransports`: Epoll is used automatically on Linux, with channel types that match the group; RpcNodeBuilder accepts an external EventLoopGroup.
- VirtualThreadRouteExecutor uses a lock-free mailbox; the Caffeine dependency and idle cache are gone.
- Runtime statistics use LongAdder; `executionTiming(false)` turns timing off.
- Runtime timers run on a HashedWheelTimer (10 ms tick).
- An event without subscribers no longer submits a Route task.
- HTTP request bodies are parsed straight from the ByteBuf; `GameRpcCodec` gains ByteBuf default methods.
- MySQL log-table suffix formatters are precompiled, identifier checks no longer use regular expressions, and INSERT statements are cached per table.
- Data write admission uses an atomic count instead of a read-write lock.
- `rewriteBatchedStatements=true` is documented, and the demo sets it.
- GameRouter keeps a per-service index, so a broadcast visits only the target service's Nodes.
- CronSchedule jumps to the next valid hour, minute and second with nextSetBit.

Not changed: merging INSERT/UPDATE into an upsert. The contract reports both an UPDATE that matches no row and a duplicate INSERT; an upsert would hide a lost row, so the current behavior stays.

## 4. Maintainability and readability

- RpcNode is split into HandshakeGuard (authentication and replay protection) and PeerTable; the Peer role is explicit (`outbound`/`detached`) instead of `target == null`; inbound handshake binding no longer runs inside a CHM compute.
- RuntimeCompiler is split into HandlerCompiler, EventCompiler, HttpCompiler and Reflection, and the builder passes RuntimeSettings.
- GameRuntime's convenience overloads are interface default methods, so the implementation keeps one dispatch path.
- NetworkServer and HttpServer share `ListenerLifecycle`; HttpServerTransport's three request-phase flags are now an Exchange enum.
- New `FrameworkErrorCodes.name/describe`; `RouteCallback.onFail(code, cause)` can carry the cause.
- Fully qualified names are cleaned up, and log messages now include the Node and the remote peer.
- Build: a `quality` profile (Checkstyle on main sources), an `errorprone` profile, GitHub Actions CI, and game-benchmark (JMH). No automatic formatter: palantir-java-format was tried and dropped, because it reflows the dense house layout of nearly every file. The Checkstyle rules are the ones that layout does not decide; `config/checkstyle.xml` records why LineLength, OneStatementPerLine and NewlineAtEndOfFile are left out.

Deferred:

- Batched writes through `Connection.write(message, flush)` and `flush()`: added, then removed again because nothing in the framework or the demo writes several messages per flush, and public API without a caller cannot be validated. The RPC pipeline already coalesces flushes with Netty's FlushConsolidationHandler; an application that needs batching installs one too. Revisit when a downlink path actually pushes many small packets per connection.
- Unused frame-layout constants `RpcWire.VERSION` and `RpcWire.REQUEST_COMMAND_OFFSET`, and `NetworkTransports.epollAvailable()`: removed with the same reasoning; the Router uses the remaining offsets, and transport selection stays internal.
- Typed callback attachments: the Router handles two callback kinds in one handler, so generics would still need instanceof; the benefit is too small.
- A status-code Runtime API: it conflicts with the entry consolidation in item 3; the stackless exception already fixes the performance problem.
- Splitting out a game-runtime-http module: without Caffeine, the remaining dependencies are Jackson and network; splitting needs a compiler extension point and Spring assembly changes, so it is left as a separate task.
- Sleeps in tests: the remaining ones cover time-based behavior (cache expiry, Router warm-up, confirming that something did not happen) or are polling loops with deadlines, which a latch cannot replace.

## 5. Verification status

The review environment could not compile (its security policy blocks a networked build), so the changes were compiled and tested afterwards: `mvn test` passes for game-core, game-network, game-rpc, game-router, game-runtime, game-data, game-spring and game-demo — 357 tests, no failures, 2 skipped (DatabaseIntegrationTest needs a real MySQL). game-benchmark is not covered, because building it needs the JMH artifacts.

Not yet executed: the style gate (`mvn -Pquality -DskipTests verify`). Its Checkstyle rules were chosen against the current sources, but Checkstyle itself has not run.
