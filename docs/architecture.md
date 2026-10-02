<a id="架构与执行契约"></a>

# Architecture and Execution Contracts

**[English](architecture.md)** | [简体中文](architecture.zh-CN.md)

The architecture follows the repository's OGBS standards and Java development specifications, with source and tests providing implementation and validation entry points. Applications explicitly register Domains, obtain RouteKeys through explicit extractors or integration logic, and handle response sending in the integration layer. Metadata carries no type field.

See the [OGBS 1.0 documentation index](ogbs/README.md) for component clauses and Java development specifications. This document describes composition; component documents and current source define specific interfaces and boundaries.

## Application skeleton

[game-demo](../game-demo/README.md) is a plain Spring application inheriting the repository's Java 25 baseline. It defines no new framework component. GameDemo initializes the Spring context and runs a user/role write example without a blocking wait; MysqlConfig owns the Hikari DataSource. GameDataConfig collects component-scanned business `@Repository` types, builds Data with GameDataBuilder.mysql(source), and supplies Data's initialized instances as their Spring Beans. Repository Beans close before Data, and Data before its application-owned pool through Bean dependencies; no second uninitialized Repository is constructed. Existing runnable component examples remain in game-example. Spring dependency management and Repository adaptation are local to game-demo; framework modules retain their existing dependency boundaries.

<a id="network--rpc-包边界"></a>

## Network / RPC package boundaries

Java Network interfaces and Netty implementation are published together in game-network. RPC depends on game-network and game-core and provides internal TCP with multiple slots. Network uses connection, connector, error, netty and an independent http package and Netty AttributeKey directly; RPC uses node, message, call, transport, error, and netty.

The layout preserves package-private encapsulation: Network's NettyConnection/NetworkChannelInitializer remain in netty; RPC's ConnectionSlot remains in node. Callers must update imports for moved types. See the [Network Java Development Specification](ogbs/OGBS-Network-Java-25-Specification-1.0.md) and [RPC Java Development Specification](ogbs/OGBS-RPC-Java-25-Specification-1.0.md) for type mappings.

Internal game-server HTTP is implemented independently in cn.managame.network.http within the game-network artifact. HttpServer owns its ServerBootstrap, listener, lifecycle and request/response pipeline; it does not wrap the TCP/WS NetworkServer or use Connection/ConnectionHandler. The package publishes HttpServer/HttpServerBuilder/HttpResponseCallback and keeps HttpServerTransport package-private. Applications own health checks, management actions, routing and serialization. The initial profile supports HTTP/1.1 only, complete request/response bodies, Keep-Alive, configurable request limits and inbound inactivity timing. pipeline(...) installs native HTTP extensions after aggregation and before an optional fallback (default 404), including application-selected authentication, CORS and compression. The synchronous handler and asyncHandler callback share per-connection sequencing: a pending response delays later request processing and automatic protocol responses without blocking the transport thread. Optional external ordered executors and HTTP extensions share that ordered context; async request use requires independent ownership, and late/duplicate callback responses are released. There is no added client, HTTP/2 stack, connection registry or business timer. Contracts: [HTTP profile](ogbs/OGBS-Network-1.0.md#http-server-profile) and [Java binding](ogbs/OGBS-Network-Java-25-Specification-1.0.md#native-http-server-api). HttpServerExample lives in game-example under cn.managame.example.network; no new Maven module or dependency is needed.

<a id="runtime-包边界"></a>

## Runtime package boundaries

The root `cn.managame.runtime` package contains only GameRuntime and GameRuntimeBuilder. Public APIs are divided into context, route, executor, protocol, handler, http, event, timer, time, and error; compilation and runtime assembly live in internal. Builder collects configuration, RuntimeCompiler validates registrations and compiles methods, RuntimeTimers handles one-shot delays, and DefaultCronScheduler manages recurring execution. See [game-runtime](../game-runtime/README.md) for layout and migration.

<a id="配置与所有权"></a>

## Configuration and ownership

All Runtime configuration enters through batch Builder methods. build collects and validates it, compiles MethodHandles, freezes registries, and then starts scheduling resources. Validation failure does not take ownership of supplied executors. After success, Runtime calls close once for each unique RouteExecutor.

NetworkServer/NetworkClient create NIO EventLoopGroups by default and synchronously release only owned resources. Server starts once and close stops its listener; Client close rejects new attempts. Neither entry keeps a connection or unfinished-attempt collection. On external groups, established channels remain caller-owned and handshakes continue to their own result/timeout; readiness observing endpoint closure is rejected. See both Network specifications for race and ownership details.

RPC Node creates and synchronously closes its NetworkServer, NetworkClient, NIO groups, timer wheel, all connections, and PendingCalls. Its Builder does not accept application-owned Network instances or EventLoopGroups.

Built-in RouteExecutor.close rejects new work and asynchronously drains ACCEPTED tasks without waiting for worker threads, so handlers may call it. Applications own server shutdown ordering and should handle asynchronous business work they need to preserve before closing Runtime.

## Network

The implementation is a single game-network module. General contracts are defined by the [Network Specification](ogbs/OGBS-Network-1.0.md); Java APIs, defaults, threads, and ownership by the [Network Java Development Specification](ogbs/OGBS-Network-Java-25-Specification-1.0.md).

NetworkServer / NetworkClient support TCP, TLS TCP, binary WebSocket, and WSS. Connection is created only after all transport handshakes succeed; no public ready state is exposed. ConnectionHandler receives onConnected, onMessage/onEvent/onException, and onDisconnected serially on the Channel EventLoop.

write checks active/writable and calls writeAndFlush directly. ACCEPTED transfers ownership without guaranteeing delivery; INACTIVE/NOT_WRITABLE retain caller ownership. There is no framework send queue or automatic retry. Asynchronous write and application callback exceptions go to onException; ordinary exceptions do not automatically close the connection. Transport rejects and closes protocol-invalid messages.

Inbound ReferenceCounted messages are borrowed during onMessage and released on return or exception; asynchronous retention or echo requires retain. Attributes use Netty AttributeKey directly, without additional shutdown freeze/clear rules.

Pipeline order is caller-provided SslHandler (optional, first) → write-error entry → HTTP/WS handlers and binary adapters (if enabled) → user codecs/handlers → ConnectionHandler adapter. NetworkChannelInitializer assembles concrete handlers without a generic Transport interface or TLS wrapper. The terminal adapter directly connects native TLS/WS completion events, endpoint checks and onConnected, without intermediate readiness/WS Promises or PromiseCombiner; Client only uses a native Promise for its connection result. There is no connection registry, ChannelGroup or collection lock; Channel-local processing forwards messages to the supplied ConnectionHandler. TLS contexts, certificates and hostname verification are configured by the caller through pipeline(...); WSS requires explicit TLS. See the Network Java specification for placement and failure rules.

[game-example](../game-example/README.md) collects standalone runnable examples and their execution tests in `cn.managame.example.<component>`. NetworkEchoExample demonstrates TCP, HttpServerExample demonstrates synchronous HTTP/1.1 and HttpAsyncServerExample demonstrates callback completion, and RpcEchoExample demonstrates two RPC nodes. The module depends on game-network, game-runtime, and game-rpc; framework modules never depend on examples or publish example classes. Add dependencies for other components only when their examples exist. These are application demonstrations of existing contracts, not a new framework component or specification layer. Automatic RPC→Runtime integration is not implemented.

## RPC

[game-rpc](../game-rpc/README.md) provides RpcNode Builder, a unified RpcHandler, and generic RpcCallback. RPC does not automatically interpret business bodies, restore Runtime Context, or invoke business callbacks; the application integration layer owns these tasks.

addPeer registers active peers with a fixed number of slots. A valid inbound handshake may create a passive peer. Passive peers are reclaimed when they have neither connections nor PendingCalls and can be upgraded to active peers in place. Mutable peer/slot/call state remains package-private.

call/notify start at the unsigned remainder of nonzero routeKey, or round-robin for zero. reply first tries the actual source slot, then falls back by routeKey. Nothing is resent after the first ACCEPTED. requestId matches calls only within a Peer; responses may return through any slot.

Each Node has one HashedWheelTimer for call timeouts, handshake timeouts and fixed-delay reconnect; connection IdleStateHandler handles heartbeats. The current restored source resets call IDs on Peer recreation, has no configured call-admission bound, runs timeout onFail directly on the timer, and scans the global connection set for each Peer during closure. Confirmed defects and validation status are recorded in [RPC Java section 9.1](ogbs/OGBS-RPC-Java-25-Specification-1.0.md#91-审阅确认的缺陷与规模风险); earlier repair claims do not describe this source. Disconnection does not immediately fail admitted calls. All valid remote error responses go to onResponse; local availability, timeout, and lifecycle races go to onFail. Core defines error-code ranges without high-bit wrapping.

Outbound ByteBuf bodies are consumed after argument/lifecycle validation and copied into one contiguous frame. Inbound bodies are borrowed within callbacks and need retain/copy for cross-thread use. Messages are read-only records; IDs are assigned internally during encoding, without a public requestId setter.

start/close run only in management contexts. Shutdown immediately rejects new work and waits for admitted RPC operations, owned networking, and the timer wheel, but not business work dispatched elsewhere. See the [RPC specifications](ogbs/OGBS-RPC-Java-25-Specification-1.0.md) for boundaries, defaults, tradeoffs, and validation.

## Runtime

Route identity is the complete domain + key, not a worker/thread/executor. key=0 is invalid; other 64-bit values are usable. Domain is an application-defined positive integer.

The default platform-thread executor hashes the complete Route into stripes, each with a single thread and bounded queue; different Routes may share a stripe. The virtual-thread executor pins active FIFO mailboxes in a ConcurrentHashMap per complete Route and caches only fully idle mailboxes in Caffeine for bounded reuse (default 60 seconds, cache maximum equal to task capacity). Per-Key atomic map operations coordinate activation/draining without an executor-wide monitor. Caffeine arrives transitively through game-core and uses default passive maintenance, with no expiry scheduler. Expired mailboxes cannot be reused, while physical reclamation may wait for subsequent cache activity. Both isolate task exceptions and provide nonblocking admission.

Runtime implements same-route inline execution; the Executor SPI is Context-unaware. Nested same-Route work runs first and restores the outer Context on return. Different Runtime instances do not inline even when domain/key match.

Contexts use Java 25 ScopedValue and bind only while a task actually executes. Without a current Context, current() throws and currentOrNull() returns null.

| Entry point | Context | Identity and Metadata inheritance |
| --- | --- | --- |
| dispatch | Caller-created HandlerContext | Explicitly supplied by integration |
| HTTP dispatch | Default or factory-created HttpContext | No implicit business identity/Metadata |
| Event | DefaultEventContext | From the current InvocationContext of the same Runtime |
| Timer/Cron | DefaultTimerContext | None |
| call action | DefaultRouteCallContext | From the source InvocationContext |
| call callback | Original source Context | Restores the exact same object |

ProtocolRegistry indexes only (type,command) ↔ MessageClass and Req→Res. MessageClass is unique; request/response may share a command. The registry contains no Codec, Handler, or Route.

RouteKeyRegistry is a separate exact-class lookup: missing bindings return 0; extractor exceptions propagate as caller errors. It performs no inheritance search or naming inference. dispatch does not query it and validates only the actual Route in Context.

Handler methods must be public, non-static, return void, and take exactly one registered REQUEST/NOTIFY argument, optionally with one Context argument; business subtypes and either parameter order are supported. Nonzero HandlerMethod.domain overrides Handler.domain. Duplicate message handlers, invalid Context types, and unregistered Domains fail at build.

Event selects its unique Route through routeDomain()/routeKey(). EventMethod uses exact lookup by concrete Event class and runs in ascending order; equal order values have no relative-order guarantee. A failed listener is reported without stopping other listeners.

GameTime is a replaceable process-wide business wall clock, defaulting to Clock.systemUTC(). setClock/resetClock do not automatically alter Timer/Cron. RuntimeTimer uses monotonic delay only. Applications convert dynamic business deadlines using GameTime and cancel + schedule when needed.

TimerRef.cancel returns true only before the scheduling thread claims the due task; afterward it does not remove the task from the Route queue. Timer/Cron business actions always enter Route execution and do not retry on exceptions. After a Cron method ends, Cron rereads GameTime, computes the next trigger, and registers another ordinary Timer. Route admission failure is reported and future cycles continue.

Cron supports six numeric fields with wildcards, question marks (day/weekday only), lists, ranges, and steps. Constrained day and weekday fields use AND. UTC is default, with ZoneId configurable through cronZone; an expression must match within the next eight years. Missed historical triggers are not replayed; the next execution is computed from current GameTime.

runtime.cron() uses declaring class + method name. cancel stops future cycles; reschedule cancels the old Timer and recalculates; rescheduleAll includes all registered entries, even cancelled ones. Generation checks prevent old tasks from restoring obsolete cycles. Already-started methods may finish. Inherited, non-overridden methods use the parent declaring class as key; duplicate keys fail build.

Business failure inside runtime.call's action should be returned as a normal result. Thrown exceptions or target rejection go to onFail; same-Route calls may complete callbacks synchronously. Cross-Route callbacks participate in the original Route's queue ordering. Failed return dispatch reports ROUTE_CALLBACK_DISPATCH_FAILED and never invokes the business callback directly on the target thread.

Synchronous dispatch integration errors throw RuntimeDispatchException. Errors after task start go to RuntimeErrorHandler. If ErrorHandler itself throws, fallback logging keeps the worker usable.

<a id="可扩展点"></a>

## Extension points

- Custom RouteExecutor with explicit Domain bindings.
- Subclass DefaultHandlerContext for Session/reply and other business capabilities.
- Externally generated ProtocolProvider.
- MetadataKey carrying its encoding rules.
- Native Decoder/Encoder, IdleStateHandler, LoggingHandler, and FlushConsolidationHandler in Netty pipelines.
- RpcHandler for decoding, remote-error interpretation, and Runtime dispatch; external service discovery updates topology with addPeer/removePeer.

Spring scanning, Protobuf generation, cross-node Router, and game business logic are outside these components' cores. The separate game-data component owns database integration.

<a id="data-组合与所有权"></a>

## Data composition and ownership

game-data depends on game-core and uses annotation/key/meta/mapper/codec/error/mysql/mongo packages. Repositories and package-private write-behind implementation live in cn.managame.data. MySQL/MongoDB and the cache core publish in one artifact; Mongo Driver is optional. Runnable Data examples are unimplemented; database-free Data contract tests remain in game-data.

Data does not depend on Runtime or RPC; applications may access it serially on existing Routes. Cache misses in get/getGroup synchronously access storage, so callers must account for database latency on Route execution threads. Same-Route ordering does not give background serializers an atomic multi-field snapshot; applications still own entity visibility.

GameDataBuilder validates repositories, identities, and mappings, initializes state schemas, then starts one persistence pipeline. Applications own DataSource/MongoClient. GameDataBuilder.mysql(DataSource) assembles the default JDBC Access/Mapper; default JSON initialization binds declared generics and concrete state initializer classes, with optional codec overrides as defined in [Data Java §6.2](ogbs/OGBS-Data-Java-25-Specification-1.0.md#default-json-field-binding). GameData.close stops only its own scheduler, synchronously drains write-behind/logs, and throws DataSaveException on final persistence failure. Stop business entry points and await admitted business work before closing GameData, then close database clients.

Data uses two buffers and a fixed 100ms grace period, accepting unusually long thread-pause risk; it has no WAL or capacity backpressure. See the [Data Java Development Specification](ogbs/OGBS-Data-Java-25-Specification-1.0.md) for unverified production/live-database boundaries, [Data Specification](ogbs/OGBS-Data-1.0.md) for general behavior, and [Core](ogbs/OGBS-Core-1.0.md) for shared persistence error codes.

<a id="runtime-http-integration"></a>

## Runtime HTTP composition

game-runtime depends on game-core and game-network, publishing cn.managame.runtime.http in the existing artifact. Explicit HttpHandler/HttpMethod registration compiles HttpRequestMethod enum/raw-path lookups; the method defaults to POST, with GET selected explicitly; Explicit RouteKey field rules read GET query or other-method JSON body; method configuration overrides the Handler rule. Named Handler methods may extract custom Keys. HttpContextFactory receives and preserves the selected Key, may supply custom HTTP context fields without framework business identity/Metadata, or selects Key when no rule exists. JSON field extraction and result encoding use Jackson Databind 2.21.3 with transitive Core/Annotations. RuntimeHttp connects to HttpServer.asyncHandler and submits to the same Route executor/context path as ordinary Handler/Event/call. Returned business objects complete automatically; void methods submit objects through HttpResultCallback. HttpResultCodec encodes JSON by default, and RuntimeHttp privately constructs the Network response; business results carry no HTTP version. It performs no request DTO binding or authentication.

Runtime retains an admitted request until method return, not until deferred reply. Cross-Route callbacks restore the same HttpContext without extending the request lifetime. No new listener/executor ownership is introduced: applications create/start/close HttpServer separately and arrange in-flight completion before Runtime closure. See [Runtime HTTP semantics](ogbs/OGBS-Runtime-1.0.md#runtime-http-profile), [Java binding](ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#runtime-http-api), and [RuntimeHttpExample](../game-example/src/main/java/cn/managame/example/runtime/RuntimeHttpExample.java). Existing ordinary message contracts and the independent Network HTTP pipeline remain intact; RPC integration is still explicit/pending.
