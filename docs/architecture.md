<a id="架构与执行契约"></a>

# Architecture and Execution Contracts

**[English](architecture.md)** | [简体中文](architecture.zh-CN.md)

The architecture follows the repository's OGBS standards and Java development specifications, with source and tests providing implementation and validation entry points. Applications explicitly register Domains, obtain RouteKeys through explicit extractors or integration logic, and handle response sending in the integration layer. Metadata carries no type field.

See the [OGBS 1.0 documentation index](ogbs/README.md) for component clauses and Java development specifications. This document describes composition; component documents and current source define specific interfaces and boundaries.

## Application skeleton

[game-demo](../game-demo/README.md) is a plain Spring application inheriting the repository's Java 25 baseline. It defines no new framework component. GameDemo initializes the Spring context and starts a GamePacket dispatch TCP listener on port 9000, using application-owned raw-byte framing codecs, plus a Runtime HTTP listener on 127.0.0.1:8080; Context closure first stops Runtime admission and drains registered work, then closes Runtime and both listeners before Data destruction. There is no blocking main-thread wait; MysqlConfig owns the Hikari DataSource. GameDataConfig enables game-spring Repository discovery, which builds Data with GameDataBuilder.mysql(source) and supplies initialized Repository instances as their Spring Beans. Repository Beans close before Data, and Data before its application-owned pool through Bean dependencies; no second uninitialized Repository is constructed. Existing runnable component examples remain in game-demo. game-demo declares game-spring and game-rpc as direct framework dependencies; Spring Context, Runtime, Data, Network and Core are transitive. game-spring owns Spring assembly and Repository adaptation; the demo retains Fory, JDBC driver and pool dependencies; its standalone RPC sample explicitly uses game-rpc.

GameRuntimeConfig assembles a Spring-managed Runtime with ROLE/LOGIN/SYSTEM Domains (IDs 1/2/3) bound to one virtual-thread RouteExecutor and uses an annotation include filter to discover and register classes marked only @EventHandler as Spring Beans, without requiring @Component. After the TCP listener starts, GameDemo statically publishes DemoEvent with Events.publish and Key 1001; the configuration binds the external-thread default during bootstrap, while Runtime execution selects its own owner; its listener prints the bound EventContext Route and thread type. The adapter uses the existing Runtime EventBus contract, creates no separate event thread, and closes Runtime through its Bean lifecycle. Source and validation are linked from the [demo event example](../game-demo/README.md#publishing-a-runtime-event).

The [demo HTTP/task examples](../game-demo/README.md#demo-runtime-services) register marker-only HttpHandler Beans, method-discovered singleton Cron Beans and a Spring-owned one-shot TimerRef. SYSTEM Domain 3 joins the existing executor; game-spring automatically starts HTTP from game.http.* settings after singleton assembly. Context closure drains/closes Runtime, then closes TCP and managed HTTP before Data destruction. No Spring business scheduling executor is added.

The demo's Fory dependency and Spring serializer configuration belong to the application. GameProtocols supplies one definition for Runtime protocol registration and Fory type IDs; GamePacketHandler resolves incoming REQUEST types through `runtime.protocols().get(REQUEST, command)`, then decodes, checks the root type, and dispatches that object to its Runtime HandlerMethod using the application's Domain policy, including an authenticated GameSession snapshot for ROLE. GamePacket keeps its existing raw-byte framing. This introduces no framework codec and does not alter Data JSON columns. Setup, compatibility boundaries and validation are in the [Fory payload example](../game-demo/README.md#fory-business-payloads).

GameRuntimeConfig also discovers marker-only @Handler Beans, including UserHandler and RoleHandler, and registers LoginReq/LoginRes through GameProtocols. GamePacketHandler calls `runtime.dispatch(connection, decodedMessage)`; Runtime resolves the Handler annotation's Domain, then the configured HandlerContextFactory reads identity/routing input and asks the application GameDomain policy to select the context. The demo uses a Connection GameSession attribute; a thread-safe external identity Map is an alternative. Handlers directly read businessId() and businessIdType() from Context; no identity wrapper or argument binding is required. Applications explicitly supply role identity after authentication and decide each Domain's routing/identity policy. Connection establishment does not allocate a Key or bind a session. LOGIN (ID 2) uses userId solely as its configured queue Key with identity 0/0. Business code binds the session inside login after token verification; ROLE (ID 1) requires that session and captures its values once before admission. Production token verification remains unimplemented in the skeleton. Explicit parameters bypass the factory; message-member extraction remains available through its separate overload. Business code owns responses; there is no automatic packet echo. See the [demo Handler entry](../game-demo/README.md#handler-dispatch) and [Java policy](ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#handler-context-factory).

<a id="network--rpc-包边界"></a>

## Network / RPC package boundaries

Java Network interfaces and Netty implementation are published together in game-network. RPC depends on game-network and game-core and provides internal TCP with multiple slots. Network uses connection, connector, error, netty and an independent http package and Netty AttributeKey directly; RPC uses node, message, call, transport, error, and netty.

The layout preserves package-private encapsulation: Network's NettyConnection/NetworkChannelInitializer remain in netty; RPC's ConnectionSlot remains in node. Callers must update imports for moved types. See the [Network Java Development Specification](ogbs/OGBS-Network-Java-25-Specification-1.0.md) and [RPC Java Development Specification](ogbs/OGBS-RPC-Java-25-Specification-1.0.md) for type mappings.

Internal game-server HTTP is implemented independently in cn.managame.network.http within the game-network artifact. HttpServer owns its ServerBootstrap, listener, lifecycle and request/response pipeline; it does not wrap the TCP/WS NetworkServer or use Connection/ConnectionHandler. The package publishes HttpServer/HttpServerBuilder/HttpResponseCallback and keeps HttpServerTransport package-private. Applications own health checks, management actions, routing and serialization. The initial profile supports HTTP/1.1 only, complete request/response bodies, Keep-Alive, configurable request limits and inbound inactivity timing. pipeline(...) installs native HTTP extensions after aggregation and before an optional fallback (default 404), including application-selected authentication, CORS and compression. The synchronous handler and asyncHandler callback share per-connection sequencing: a pending response delays later request processing and automatic protocol responses without blocking the transport thread. Optional external ordered executors and HTTP extensions share that ordered context; async request use requires independent ownership, and late/duplicate callback responses are released. There is no added client, HTTP/2 stack, connection registry or business timer. Contracts: [HTTP profile](ogbs/OGBS-Network-1.0.md#http-server-profile) and [Java binding](ogbs/OGBS-Network-Java-25-Specification-1.0.md#native-http-server-api). HttpServerExample lives in game-demo under cn.managame.demo.examples.network; no new Maven module or dependency is needed.

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

[game-demo](../game-demo/README.md) unifies the Spring main application and standalone component entries/execution tests under `cn.managame.demo.examples.<component>`: TCP string echo, synchronous/asynchronous HTTP, Runtime HTTP and two-node RPC. It directly depends on game-spring and game-rpc; other framework dependencies arrive transitively through game-spring. Standalone entries load neither the main application nor MySQL, and an isolation profile prevents their HTTP Handler from joining main-application scanning. Frameworks do not depend on demo or publish samples; these entries demonstrate existing specification pairs without adding a component.

## RPC

### Router composition on one RPC Node

[game-router](../game-router/README.md) depends on game-rpc, composing a private ordinary RpcHandler on the process's existing Node before startup. GameRouter provides Router membership/queries; its service factory returns concrete ServiceRouting for callback-based registration, bindings and direct business send/reply methods. All connections use rpc.addPeer. RPC owns connections, Slots, IDs, pending calls and timeouts; Router owns authority, synchronization and next hops. External discovery owns service presence and explicitly removes exact Node incarnations; temporary connection loss preserves authority. The application owns Node lifecycle; dependency remains Router → RPC.

The RPC integration is limited to ordinary Handler composition and availability/Slot-count snapshots. No Node readiness/cleanup callback or mutable Handler hook is supplied. Sending reuses call/notify/reply. Routing owns protocol maintenance, synchronization retries and buffer cleanup; recovery never resets shared RPC Peers or cancels unrelated calls. RpcHandler itself remains a three-method message contract.

Router peers use full mesh and one Slot per pair. Service registrations may use several Slots; binding authority survives transport loss and is removed by explicit discovery or unregister. Snapshot chunks and deltas use a bounded, peer-local acknowledged control stream; local bind success never waits for the cluster. Runnable [RouterEchoExample](../game-demo/src/main/java/cn/managame/demo/examples/router/RouterEchoExample.java) and its execution test live in game-demo, which directly depends on game-router. Contracts: [Router standard](ogbs/OGBS-Router-1.0.md), [Java development](ogbs/OGBS-Router-Java-25-Specification-1.0.md), [Wire](rpc-wire.md#router-profile-v2).

Managed Spring RPC composes Router on its single Node through `GameRpcConfigurer.decorate/attach/detach`: decorate wraps the Runtime RpcHandler with GameRouter/ServiceRouting before build, attach starts routing before the Node starts, and detach closes routing before the Node closes. Direct RpcHandlerContext/GameRpc replies still omit source attachment epoch and use native direct replies, so routed requests are delivered to the application's RouterHandler; decoding them into Runtime, exact routed replies (ServiceRouting.reply) and captured callback/drain integration remain application work, preserving one Node and Router → RPC dependencies. Do not introduce a second Node or move Runtime decoding/dispatch into Router to conceal this gap.

Discovery/placement integration is also application work, not supplied by the Router example: exact incarnation retirement, event replay after Router restart, membership removal and migration execution fencing need an operational contract. Epoch conflict ordering alone does not establish chronology, and deterministic binding conflict resolution does not serialize business execution across processes. Full mesh replicates all authoritative buckets at every Router; R Routers use R×(R−1)/2 pair connections and each local change fans out to up to R−1 peers. Control and business frames share the single Slot and forwarding monitor, and synchronization readiness gates forwarding. These are deliberate small-fleet constraints; production membership, memory and recovery budgets remain unverified. The [Router review](ogbs/OGBS-Router-Java-25-Specification-1.0.md#7-known-limits) records source evidence and pending integration decisions.

[game-rpc](../game-rpc/README.md) provides RpcNode Builder, a unified RpcHandler, and generic RpcCallback. RPC does not automatically interpret business bodies, restore Runtime Context, or invoke business callbacks; the application integration layer owns these tasks.

addPeer registers active peers with a fixed number of slots. A valid inbound handshake may create a passive peer. Passive peers are reclaimed when they have neither connections nor PendingCalls. Only one side of a pair may call addPeer: a repeated call on the other side throws and a reverse inbound handshake is rejected. With a shared secret, handshakes carry HMAC authentication; RpcNodeBuilder exposes the write-buffer water mark, ChannelOptions and transport handlers (TLS, flush consolidation and similar). Mutable peer/slot/call state remains package-private.

call/notify start at the unsigned remainder of nonzero routeKey, or round-robin for zero. reply first tries the actual source slot, then falls back by routeKey. Nothing is resent after the first ACCEPTED. requestId matches calls only within a Peer; responses may return through any slot.

Each Node has one HashedWheelTimer for maintenance; timeout business notifications use tracked virtual threads awaited by close. Node-wide request IDs survive Peer recreation; recovery stop rechecks empty Slots and reacquires ownership with CAS. Finite call admission is deferred and closure still scans global connections per Peer. See [RPC Java 9.1](ogbs/OGBS-RPC-Java-25-Specification-1.0.md#91-审阅确认的缺陷与规模风险).

Outbound ByteBuf bodies are consumed after argument/lifecycle validation and copied into one contiguous frame. Inbound bodies are borrowed within callbacks and need retain/copy for cross-thread use. Messages are read-only records; IDs are assigned internally during encoding, without a public requestId setter.

start/close run only in management contexts. Shutdown immediately rejects new work and waits for admitted RPC operations, owned networking, and the timer wheel, but not business work dispatched elsewhere. See the [RPC specifications](ogbs/OGBS-RPC-Java-25-Specification-1.0.md) for boundaries, defaults, tradeoffs, and validation.

## Runtime

The demo manages domain definitions in the business [GameDomain enum](../game-demo/src/main/java/cn/managame/demo/common/runtime/GameDomain.java): ROLE has explicit ID 1, LOGIN has explicit ID 2, and SYSTEM has explicit ID 3; both are registered as RouteDomain. An annotation references GameDomain.ROLE_ID, a Java compile-time integer constant. Enum ordering is not an ID source. This is application organization, without a framework ROLE enum or Domain-dependent identity/Key inference.

Route identity is the complete domain + key, not a worker/thread/executor. key=0 is invalid; other 64-bit values are usable. Domain is an application-defined positive integer.

The default platform-thread executor hashes the complete Route into stripes, each with a single thread and bounded queue; different Routes may share a stripe. The virtual-thread executor pins active FIFO mailboxes in a ConcurrentHashMap per complete Route and caches only fully idle mailboxes in Caffeine for bounded reuse (default 60 seconds, cache maximum equal to task capacity). Per-Key atomic map operations coordinate activation/draining without an executor-wide monitor. Caffeine arrives transitively through game-core and uses default passive maintenance, with no expiry scheduler. Expired mailboxes cannot be reused, while physical reclamation may wait for subsequent cache activity. Besides total capacity, the virtual-thread executor limits waiting tasks per Route (default 1024), so a hot Route only gets OVERLOADED itself. Both isolate task exceptions and provide nonblocking admission.

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

RouteKeyRegistry is a separate exact-class lookup: missing bindings return 0; extractor exceptions propagate as caller errors. It performs no inheritance search or naming inference. Explicit-context and supplied-Key dispatch preserve their Route; only message-derived dispatch queries extraction.

Handler methods must be public, non-static, return void, and take exactly one registered REQUEST/NOTIFY argument, optionally one Context and registered application argument types, in any order. Nonzero HandlerMethod.domain overrides Handler.domain. Duplicate message handlers, invalid Context types, unbound/ambiguous argument types, and unregistered Domains fail at build. Business integration supplies Key and businessIdType/businessId through the identity-aware dispatch overload; these values have separate meanings. Business identity is normally read directly through Context without RoleId/GuildId/RoomId wrappers or argument registration. HandlerArgumentBinding remains an optional extension for other application arguments, resolving once before admission on the submitting thread; resolvers cannot access Route-owned state. The target Handler still uses the existing Route executor. See the [Java argument contract](ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#handler-arguments).

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
- Use ClientHandlerContext/DefaultClientHandlerContext for client Connection access and RpcHandlerContext/DefaultRpcHandlerContext for logical RPC origin/correlation; extend the appropriate default only for application-specific fields. Common HandlerContext contains message and invocation identity. Optional RPC-to-Runtime adaptation is available through game-spring.
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

Data's saving thread claims pending changes by conditional removal, with no buffer swap or fixed wait. Retryable failures stay for the next pass, a non-retryable bad row is isolated, dropped and reported, and cache misses read unsaved changes first, so nothing rolls back. There is no WAL or capacity backpressure. See the [Data Java Development Specification](ogbs/OGBS-Data-Java-25-Specification-1.0.md) for unverified production/live-database boundaries, [Data Specification](ogbs/OGBS-Data-1.0.md) for general behavior, and [Core](ogbs/OGBS-Core-1.0.md) for shared persistence error codes.

<a id="runtime-http-integration"></a>

## Runtime HTTP composition

game-runtime depends on game-core and game-network, publishing cn.managame.runtime.http in the existing artifact. Explicit HttpHandler/HttpMethod registration compiles HttpRequestMethod enum/raw-path lookups; the method defaults to POST, with GET selected explicitly; Explicit RouteKey field rules read GET query or other-method JSON body; method configuration overrides the Handler rule. Named Handler methods may extract custom Keys. HttpContextFactory receives and preserves the selected Key, may supply custom HTTP context fields without framework business identity/Metadata, or selects Key when no rule exists. JSON field extraction and result encoding use Jackson Databind 2.21.3 with transitive Core/Annotations. RuntimeHttp connects to HttpServer.asyncHandler and submits to the same Route executor/context path as ordinary Handler/Event/call. Returned business objects complete automatically; void methods submit objects through HttpResultCallback. HttpResultCodec encodes JSON by default, and RuntimeHttp privately constructs the Network response; business results carry no HTTP version. It supports DTO/String request binding while authentication remains application policy.

Runtime retains an admitted request until method return, not until deferred reply. Cross-Route callbacks restore the same HttpContext without extending the request lifetime. No new listener/executor ownership is introduced: plain Runtime applications create/start/close HttpServer separately and arrange in-flight completion before Runtime closure; optional game-spring manages this listener lifecycle and context-path mapping. See [Runtime HTTP semantics](ogbs/OGBS-Runtime-1.0.md#runtime-http-profile), [Java binding](ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#runtime-http-api), and [RuntimeHttpExample](../game-demo/src/main/java/cn/managame/demo/examples/runtime/RuntimeHttpExample.java). Existing ordinary message contracts and the independent Network HTTP pipeline remain intact; RPC integration remains explicit through the optional game-spring adapter.


## Optional Spring integration

[game-spring](../game-spring/README.md) depends on Runtime, Data and spring-context without Spring Boot; core modules have no reverse Spring dependency. Demo uses EnableGameRuntime/EnableGameData and retains application Domain/executor/identity policy and DataSource configuration. ContextClosedEvent stops Runtime admission and drains registered work before Runtime closure; ordinary listeners then close TCP, managed HTTP stops through Spring lifecycle, followed by Data/pool destruction. HTTP applications configure game.http.port and game.http.context-path and declare marker-only HttpHandler/HttpMethod endpoints, with further network settings supplied by properties or GameHttpConfigurer. See [container semantics](ogbs/OGBS-Spring-1.0.md) and [Java integration specification](ogbs/OGBS-Spring-Java-25-Specification-1.0.md).


Optional `@EnableGameRpc` in game-spring assembles RpcNode, application object codec and Runtime adaptation from node-id/port settings. game-rpc is an optional Maven dependency, HTTP/Data-only applications need not inherit it; game-demo now adds game-rpc explicitly for its standalone RPC sample. See [Spring Java specification](ogbs/OGBS-Spring-Java-25-Specification-1.0.md#managed-rpc).
