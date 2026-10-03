# game-demo

**[English](README.md)** | [简体中文](README.zh-CN.md)

Plain Spring application in the root Maven reactor, using JDK 25 and the repository parent. Application sources live under `cn.managame.demo`. `spring-context:7.0.9` provides dependency injection; application-level HikariCP and MySQL Driver dependencies provide the pool and JDBC implementation.

`org.apache.fory:fory-core:1.7.6` provides binary business-payload serialization inside this application. When launching GameDemo in an IDE on JDK 25, add the VM option `--add-opens=java.base/java.lang.invoke=ALL-UNNAMED`, following [Fory's JDK setup](https://fory.apache.org/docs/object-serialization/java/). The demo POM supplies it to Surefire for tests.

[GameDemo](src/main/java/cn/managame/demo/GameDemo.java) starts a Spring context and registers its shutdown hook. [MysqlConfig](src/main/java/cn/managame/demo/common/mysql/MysqlConfig.java) loads `application.properties` and creates an application-owned pool. Business Repository classes use Spring's annotation:

```java
@Repository
public class UserRepository extends SingleRepository<Long, User> {
}
```

Configure `game.db.url`, `game.db.username`, and `game.db.password` in `src/main/resources/application.properties` before starting `cn.managame.demo.GameDemo` in an IDE with JDK 25. The database must already exist; Data creates missing state tables/columns/indexes. A missing or blank JDBC URL fails before a connection pool is opened, identifying `game.db.url` as the required setting. JVM system properties and environment variables may also supply these keys through Spring's Environment.

`@PropertySource` loads the file and Spring's default embedded value resolver injects `@Value`; no explicit `PropertySourcesPlaceholderConfigurer` Bean is needed. After successful initialization, the entry prints `game-demo started. Press Ctrl+C to stop.` and returns to main, which starts a packet dispatch TCP server on port 9000. Netty's owned threads keep the process running. The current entry has no blocking main-thread wait; JVM shutdown invokes Spring's hook. Context closure first stops Runtime admission and drains registered work, then closes Runtime, listeners, Data and the application-owned pool. HTTP also starts on 127.0.0.1:8080 and both listeners close after Runtime drain, before Data destruction. No extra keep-alive thread is created.

[GameDataConfig](src/main/java/cn/managame/demo/common/data/GameDataConfig.java) collects scanned `@Repository` Beans extending SingleRepository, GroupRepository, or LogRepository without instantiating them. It registers their types with GameDataBuilder and replaces their Bean instance suppliers with `data.repository(type)`. Bean names, qualifiers, and Spring field/setter injection are retained. Data initializes each instance before Spring injection callbacks or business access; adding a Repository within the component scan needs no per-class registration or `@Bean` method. Repository scope must be singleton (other scopes fail at startup), and the existing Data requirement for a direct parameterized base and a no-argument constructor remains. Constructor dependency injection is not supported because GameData constructs these instances. This adapter now lives in optional game-spring and is enabled by @EnableGameData; game-data has no Spring dependency.

User's JSON Map initializes as ConcurrentHashMap<Integer,Long> and default loading preserves that implementation and generic types. Its empty-map default is supplied by Java construction and full-row INSERT; raw `defaultValue="{}"` is not a SQL DDL expression and is omitted. See [default JSON binding and limits](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.md#default-json-field-binding). Custom JSON behavior uses `jsonCodec(...)`.

## Publishing a Runtime event

[GameRuntimeConfig](src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java) builds a Spring-managed GameRuntime, registers Domain 1 (`ROLE`) with a virtual-thread RouteExecutor, and collects Spring Beans marked `@EventHandler` through `builder.eventHandlers(...)`. Business listeners only need `@EventHandler`: the configuration adds an annotation include filter for `cn.managame.demo`, so Spring discovers these classes as Beans without `@Component`. This scanning adaptation belongs to optional game-spring via @EnableGameRuntime; the Runtime annotation itself has no Spring dependency. The configuration calls Events.bind(runtime) during bootstrap so external business code can publish statically. It closes the newly built Runtime if default binding conflicts with another instance. Context shutdown closes Runtime, releases its default event binding, and closes its owned execution/timer resources.

After the TCP listener starts, GameDemo publishes one immutable [DemoEvent](src/main/java/cn/managame/demo/event/DemoEvent.java):

```java
Events.publish(new DemoEvent(1001L, "hello game-runtime"));
```

[DemoEventHandler](src/main/java/cn/managame/demo/event/DemoEventHandler.java) receives it through `@EventMethod` and reads the bound EventContext using `Contexts.current()`. The visible result is:

```text
DemoEvent received: routeDomain=1, routeKey=1001, message=hello game-runtime, virtualThread=true
```

This publication from the main thread enters Runtime's configured RouteExecutor; no extra event thread or Spring ApplicationEvent is used. Domain is fixed at 1 by DemoEvent and Key is supplied by its caller (zero is rejected by Runtime). `publish` returning does not confirm listener completion. This is a local in-process event with no database persistence or remote delivery. See the existing [Runtime event semantics](../docs/ogbs/OGBS-Runtime-1.0.md#7-local-events) and [Java EventBus binding](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#9-eventbus).

[DemoEventTest](src/test/java/cn/managame/demo/DemoEventTest.java) registers only GameRuntimeConfig and discovers the marker-only listeners through its scan, without MySQL or a listener socket, waits for a later ordered listener, and verifies the event identity, target Domain/Key, virtual thread, and static publication rejection after Runtime closure removes the default binding. The event test passed and printed the result above. Full application startup still requires the existing database configuration.

## Handler dispatch

[GameDomain](src/main/java/cn/managame/demo/common/runtime/GameDomain.java) is the application's enum of execution domains. It defines ROLE with explicit ID 1, LOGIN with explicit ID 2, and SYSTEM with explicit ID 3; GameRuntimeConfig converts enum values to RouteDomain registrations and binds their IDs to the executor. Handler annotations use the compile-time constant `@Handler(domain = GameDomain.ROLE_ID)`, since Java annotation elements cannot accept arbitrary application enum types or calls such as ROLE.id(). IDs never come from ordinal(), so reordering the enum does not change routes. Domain groups execution; it does not determine role identity, require authentication by itself, or automatically select routeKey. Both routeKey and authenticated roleId remain explicitly supplied by business integration.

GameRuntimeConfig also discovers marker-only `@Handler` Beans and registers them through `builder.handlers(...)`. [UserHandler](src/main/java/cn/managame/demo/bus/user/UserHandler.java) uses `@Handler(domain=GameDomain.ROLE_ID)` with `@HandlerMethod(domain=GameDomain.LOGIN_ID)` on `login(ClientHandlerContext context, LoginReq request)`; get its connection through `context.connection()`. Connection is not a separate method parameter. GameProtocols registers LoginReq/ LoginRes at command 1003, associates the response, and assigns Fory type IDs 3/4.

The caller normally already knows the Key, and only needs:

```java
runtime.dispatch(connection, routeKey, loginReq);
```

Runtime selects the Handler Domain and creates the context; a protocol userId never overrides the supplied Key. No extraction configuration is required. Only exceptional protocol-based routing needs `@HandlerMethod(routeKey="userId")` or `routeKeyMethod="getUserId"`, on a builder without a configured HandlerContextFactory, followed by `runtime.dispatch(connection, loginReq)`. The demo configures a factory, which explicitly selects routing by Domain as described below. Those names refer to LoginReq's field or public no-argument getter. This anonymous login uses identity 0/0 and empty Metadata; explicit Context dispatch remains available for Metadata/custom fields.

Business handlers read identity directly from Context. [RoleHandler](src/main/java/cn/managame/demo/bus/role/RoleHandler.java) declares `ping(ClientHandlerContext context, PingMessage request)` and reads `long roleId = context.businessId()`. No RoleId/GuildId/RoomId wrapper or handlerArguments registration is needed. GameDomain.ROLE_BUSINESS_ID_TYPE=1 names the application's role identity category, independently of its execution Domain. Trusted integration can supply identity explicitly:

```java
runtime.dispatch(connection, 99L, GameDomain.ROLE_BUSINESS_ID_TYPE, 10001L, new PingMessage(1234));
```

Domain 1 comes from @Handler, Key is 99, and context.businessId() is 10001; neither the Key nor protocol timestamp selects identity. Integration authenticates and supplies trusted identity. In the network entry, the ROLE policy rejects a missing session before queueing, then captures the role category and ID in Context. Explicit-Key/identity/context overloads bypass that policy, so their callers must supply the appropriate trusted identity; a Context parameter does not automatically validate a business category or authenticate the caller. Only the Handler runs on its Route. [DemoIdentityTest](src/test/java/cn/managame/demo/DemoIdentityTest.java) verifies Spring dispatch without argument bindings, distinct identity/Key/protocol values, virtual-thread execution, and missing-session rejection without database/socket access. See the [complete argument contract](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#handler-arguments).

[DemoHandlerTest](src/test/java/cn/managame/demo/DemoHandlerTest.java) loads only Runtime configuration and replaces the empty login implementation with a manual test probe, verifying caller Key 99 while LoginReq.userId=10001, Domain 2, original connection/request, and a virtual thread. It also verifies unbound LOGIN dispatch using userId solely for routing, no connection attribute access, and rejection of Key 0 before admission. It needs no database or socket. The TCP entry now dispatches decoded messages to Runtime Handlers; UserHandler's login business logic remains empty and no LoginRes is generated automatically. Full demo startup still requires database configuration.

<a id="demo-runtime-services"></a>

## HTTP, timer, and cron

[SystemHttpHandler](src/main/java/cn/managame/demo/bus/system/SystemHttpHandler.java) is discovered using only @HttpHandler, without @Component. GameRuntimeConfig registers it through httpHandlers and connects the main HTTP listener to runtime.http()::dispatch. SYSTEM has explicit Domain ID 3; it shares the same RouteExecutor as ROLE and LOGIN. The packet context policy rejects SYSTEM messages until the application explicitly adds a packet identity/routing policy for that Domain. HTTP defaults to 127.0.0.1:8080; override game.http.port through the Spring Environment (for example the JVM option -Dgame.http.port=8081). TCP continues using port 9000. Both listeners close on ContextClosedEvent after Runtime drain/closure and before Data destruction; a listener startup failure closes the Context and the already-started listener.

| Request | RouteKey input | Result |
| --- | --- | --- |
| POST /demo/dto | JSON body field routeKey | EchoRequest.text bound directly to DTO; current HttpContext via Contexts |
| GET /demo/query | Query parameter routeKey | EchoRequest.text bound from query |
| POST /demo/echo | JSON body field routeKey | EchoResult containing the selected Key and original UTF-8 body |
| GET /demo/tasks | Query field routeKey | JSON timerRuns and cronRuns counters |

POST is the annotation default; GET explicitly uses HttpRequestMethod.GET. Business methods return objects, with JSON encoding and transport responses supplied by Runtime. Keys must be nonzero signed 64-bit integers; missing/invalid Keys produce 400, an unknown path produces 404, and a wrong method on a known path produces 405. A Key selects execution and does not authenticate a player. HttpContext has no business identity/Metadata. These demonstration endpoints implement no token verification or production administration policy.

```shell
curl -H "Content-Type: application/json" -d '{"routeKey":1,"message":"hello"}' http://127.0.0.1:8080/demo/echo
curl "http://127.0.0.1:8080/demo/tasks?routeKey=1"
```

[DemoTasks](src/main/java/cn/managame/demo/bus/system/DemoTasks.java) demonstrates both task mechanisms. The demoStartupTimer Bean calls runtime.timer().schedule(SYSTEM_ID, 1, delay, tasks::onTimer) once, with a default 3000 ms delay from Runtime configuration initialization. game.demo.timer.delayMillis can override that nonnegative delay; a negative value rejects startup. The returned TimerRef is owned by Spring and canceled on destruction. Runtime closure also stops pending delays; a triggered/admitted task may already execute. This is a one-shot timer, not a repeated fixed-delay loop.

The public onCron method uses @Cron(value="*/10 * * * * ?", domain=GameDomain.SYSTEM_ID, routeKey=1). EnableGameRuntime uses a startup-only internal CronMethodFilter to discover independent concrete classes declaring/inheriting @Cron methods in cn.managame.demo, including default interface methods. No @Component, @Import task list or fixed task type is required. CronBeans collects annotated task owners from known Spring Bean types, including @Bean products and manually registered singletons, and passes them to cronHandlers. @Cron remains a framework method annotation; this method-level class scanning is provided by game-spring. The demo explicitly uses UTC and fires on wall-clock seconds 0, 10, 20, 30, 40, and 50. Runtime starts the schedule at build, computes the next future occurrence after each completed invocation, and does not replay missed occurrences. Timer and cron callbacks run with TimerContext on Route (3,1); an HTTP request using Key 1 joins that same serial Route. The Runtime's existing timer scheduler only signals expiry; the demo adds no task executor or scheduler thread. Counters use AtomicLong because HTTP may observe them from another Key; the two counters are observational values, not a transactional snapshot.

```java
TimerRef timer = runtime.timer().schedule(GameDomain.SYSTEM_ID, DemoTasks.ROUTE_KEY,
        Duration.ofSeconds(3), tasks::onTimer);
timer.cancel(); // true only if cancellation wins before trigger
runtime.cron().cancel(DemoTasks.class, "onCron");
runtime.cron().reschedule(DemoTasks.class, "onCron");
```

Task discovery reads Bean types without eagerly creating unrelated Beans or the Runtime under construction. Unknown-type uninitialized FactoryBean products are not instantiated to infer their tasks; expose an identifiable product/return type. Annotated owners are initialized once during Runtime build and must be singleton; duplicate references to the same object are registered once. Invalid private/static/signature/domain/expression definitions fail build instead of being ignored. Existing inherited public annotations retain Runtime's declaring-class/method cancellation key; an unannotated override does not inherit the method annotation. Spring AOP proxies are unsupported Cron targets and identifiable proxies reject startup. A task cannot require the same still-building Runtime in its constructor; resolve deferred dependencies only after Runtime initialization completes. Adding task Beans after build does not update the frozen registration. [CronBeansTest](../game-spring/src/test/java/cn/managame/spring/runtime/CronBeansTest.java) verifies method-only scanning, factory-created and inherited tasks, real Route execution, object-identity deduplication, avoiding unrelated initialization, and startup rejection.

[DemoServicesTest](src/test/java/cn/managame/demo/DemoServicesTest.java) assembles the actual Spring configuration without Data/MySQL, sends UTF-8 POST and GET requests over real local HTTP/1.1, verifies route binding/virtual-thread execution and 400/405 rejection, waits for the startup timer and a real cron occurrence, and checks cancellation and closure rejection. The full application still requires its existing database configuration. See [Runtime timer/cron semantics](../docs/ogbs/OGBS-Runtime-1.0.md#demo-runtime-services), [Java task API](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#demo-task-integration), and [HTTP contract](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#runtime-http-api).

## Fory business payloads

[GameProtocols](src/main/java/cn/managame/demo/common/protocol/GameProtocols.java) is the single application definition of commands, message classes, and stable Fory type IDs. GameRuntimeConfig installs it through `builder.protocols(...)`; [ForyConfig](src/main/java/cn/managame/demo/common/serialization/ForyConfig.java) registers the same classes and type IDs before exposing one Spring-managed ThreadSafeFory. The current registrations are:

| Request command | Message class | Fory type ID |
| --- | --- | --- |
| 1001 | [DemoMessage](src/main/java/cn/managame/demo/network/message/DemoMessage.java) | 1 |
| 1002 | [PingMessage](src/main/java/cn/managame/demo/network/message/PingMessage.java) | 2 |
| 1003 | [LoginReq](src/main/java/cn/managame/demo/bus/user/LoginReq.java) | 3 |

LoginRes is registered separately as RESPONSE command 1003 with Fory type ID 4; the server's incoming lookup remains REQUEST.

Commands and Fory type IDs are separate identities. Add a binding in GameProtocols to add another protocol; do not add message-class branches to the connection handler. Runtime validates its existing protocol uniqueness rules at startup. Fory type IDs must remain unique and stable across both peers; request and response may share a command while using different message classes and Fory type IDs.

[GamePacketHandler](src/main/java/cn/managame/demo/network/GamePacketHandler.java) is a Spring Bean injected with the shared serializer and Runtime. The server interprets incoming packets as REQUEST and resolves the expected class from Runtime's existing ProtocolRegistry before deserializing:

```java
var protocol = protocols.get(ProtocolType.REQUEST, packet.getCommand());
if (protocol == null) {
    throw new IllegalArgumentException("Unknown request command: " + packet.getCommand());
}
Class<?> type = protocol.messageType();
Object decodedMessage = type.cast(fory.deserialize(packet.getBody()));
runtime.dispatch(connection, decodedMessage);
```

The expected type comes from the command, not a hard-coded DemoMessage class. The cast checks the actual decoded root object against that type; a registered PingMessage sent with command 1001 is rejected instead of being read as DemoMessage. Unknown commands reject before decoding. The decoded object then enters its exact-class HandlerMethod through Runtime on the configured Route executor, rather than being echoed by the network callback. Protocol registration alone does not supply a Handler: DemoMessage remains a serialization fixture and dispatching command 1001 with its correct body currently rejects HANDLER_NOT_FOUND. LoginReq command 1003 targets UserHandler.login; PingMessage command 1002 targets RoleHandler.ping. Empty raw body is valid framing but not a serialized Fory object.

[GameSession](src/main/java/cn/managame/demo/network/GameSession.java) is an immutable application record stored in Connection's Netty AttributeKey. It stores a nonzero business-selected Key and an authenticated long roleId. onConnected does nothing: it allocates no Key and never binds or overwrites a session. Before successful authentication there is no session. Bind manually inside login only after business token verification succeeds. The following is a business-integration sketch; tokenVerifier and the Key selection are application-owned and are not implemented in the skeleton:

```java
@HandlerMethod(domain = GameDomain.LOGIN_ID)
public void login(ClientHandlerContext context, LoginReq loginReq) {
    // verify must reject invalid tokens and return server-verified identity.
    var identity = tokenVerifier.verify(loginReq.getToken());
    long selectedRouteKey = selectRoleRouteKey(identity);
    context.connection().set(GameSession.KEY,
            new GameSession(selectedRouteKey, identity.roleId()));
}
```

The role identity must be supplied after authentication by business code, not copied from loginReq.userId/token. GameRuntimeConfig registers a HandlerContextFactory: Runtime first resolves the Handler's annotation Domain, then the factory calls GameDomain.fromId(domain).handlerContext(...). LOGIN (ID 2) uses LoginReq.userId solely as its explicitly configured queue Key with identity 0/0 and no session access or writes. This client value does not establish authenticated identity; Key 0 rejects before admission. ROLE (ID 1) reads GameSession once, rejects an absent session before admission, and preserves the business-supplied Key and GameDomain.ROLE_BUSINESS_ID_TYPE/role value; it does not force Key equal to roleId. Failed token verification leaves an unbound connection unbound. A successful binding affects subsequent admission; earlier admitted contexts remain unchanged. New Domains explicitly define their own policy. A thread-safe external Connection-to-role Map may replace the attribute lookup without changing GamePacketHandler or Runtime; no storage implementation or fixed Domain-to-role rule is mandated. Handlers read identity through Context. Session presence records successful business binding; roleId is a long and does not use 0/null as an authentication marker. Applications may impose their own ID range constraints. See the [factory contract](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#handler-context-factory).

Already admitted work keeps those values and its decoded object even if the connection's session or original packet changes. Changing the Key affects subsequent admission and does not migrate earlier tasks; applications coordinate any state handoff. The configured factory entry performs no implicit message extraction or missing-session fallback. Invalid Fory body, type mismatch, missing ROLE session, factory/identity resolution failure, missing Handler, overload and closed-Runtime admission failures propagate to Network's onException callback, which closes the connection. Handler execution failures follow RuntimeErrorHandler, with no automatic transport closure or retry. Disconnect does not cancel admitted work. Authentication, identity-store cleanup, packet seq/code correlation and response creation/sending remain business integration work; this entry sends no automatic echoes or response DTOs. Explicit-Key/identity and explicit-context dispatch remain available and bypass the factory.

Fory uses Java native mode (`withXlang(false)`) with required class registration. Both peers must use this version/configuration and the same type identities; register additional custom nested classes if a payload needs them. Previously sent arbitrary bytes are not valid business payloads for this listener. [The shared ThreadSafeFory pool](https://fory.apache.org/docs/object-serialization/java/virtual-threads/) supports concurrent EventLoops and virtual threads without keeping one serializer per virtual thread. Asynchronous compilation is disabled; serialization runs in the caller and no additional business executor is introduced. Reuse the Bean instead of building Fory per packet or changing registrations during traffic. Data's MySQL JSON columns continue to use its JSON codec; this dependency is confined to game-demo.

[ForySerializationTest](src/test/java/cn/managame/demo/ForySerializationTest.java) validates Spring injection, independent writer/reader configurations, Unicode/long values, 128 concurrent virtual threads, unregistered types, malformed bodies, unknown commands, and command/body mismatches. These checks do not establish cross-language compatibility, arbitrary schema upgrades, or capacity limits.

## GamePacket TCP framing

[GamePacket](src/main/java/cn/managame/demo/network/GamePacket.java) carries `int command`, `int seq`, `int code`, and raw `byte[] body`. The codecs belong to this application; they do not define RPC wire format or add a framework protocol. Each integer uses four bytes in big-endian order:

| Offset | Field | Meaning |
| --- | --- | --- |
| 0 | length | 12 + body.length; excludes its own four bytes |
| 4 | command | Application command ID |
| 8 | seq | Application request/response sequence |
| 12 | code | Application result code |
| 16 | body | Raw bytes; the framing codecs do not interpret them, and the application uses Fory |

[GamePacketDecoder](src/main/java/cn/managame/demo/network/GamePacketDecoder.java) accumulates split frames and emits multiple coalesced frames in order. Incomplete input emits no packet. Both codecs default to a 1 MiB maximum **total frame size**, including the 16-byte header; constructors accept a different limit of at least 16. Lengths below 12 reject with CorruptedFrameException; lengths beyond the configured bound reject with TooLongFrameException as soon as the length arrives. EOF with an incomplete header/body reports CorruptedFrameException. The demo's onException closes the connection. Command/seq/code are transported unchanged; their business meaning is not validated by these codecs.

The decoder copies the body into an independent heap array, releasing its input through Netty's decoder lifecycle. GamePacket is not reference-counted and requires no retain/release. An empty body uses an empty array; `setBody(null)` throws NullPointerException. The setter/getter do not copy the array. After `Connection.write(packet)` returns ACCEPTED, do not change the packet or its body array; acceptance still does not guarantee peer receipt. The encoder checks the total size before emitting bytes. Decoder instances are per-connection; the stateless encoder can be shared.

The entry installs both codecs with `pipeline(p -> p.addLast(new GamePacketDecoder(), new GamePacketEncoder()))` and obtains GamePacketHandler from Spring. Applications serialize a registered message into the raw body before writing:

```java
GamePacket packet = new GamePacket();
packet.setCommand(1003);
packet.setSeq(42);
packet.setCode(0);
LoginReq login = new LoginReq();
login.setUserId(10001L);
login.setToken("application-token");
packet.setBody(fory.serialize(login));
if (connection.write(packet) != WriteStatus.ACCEPTED) connection.close();
```

[GamePacketCodecTest](src/test/java/cn/managame/demo/network/GamePacketCodecTest.java) validates exact wire bytes, fragmentation/coalescing, empty/binary bodies, size limits, EOF, and input reference release. [GamePacketNetworkTest](src/test/java/cn/managame/demo/network/GamePacketNetworkTest.java) uses the actual Spring GamePacketHandler over real local TCP with borrowed EventLoopGroups and explicit cleanup. Marker-only test probes receive decoded LoginReq (1003) and PingMessage (1002) in their respective HandlerMethods on virtual threads, with the server-side connection and session Route/identity. The login test probe uses a test-only token verifier: an invalid token leaves the session absent, and successful verification binds it inside HandlerMethod before a ROLE ping. This fixture does not implement production authentication. A PingMessage under command 1001 closes the connection without another business invocation or automatic response. [GamePacketDispatchTest](src/test/java/cn/managame/demo/network/GamePacketDispatchTest.java) verifies no session creation or overwrite on connection establishment, session-free LOGIN routing, identity-required rejection before queueing, session/decoded-object capture while queued, missing Handler/session rejection, Metadata capture and correlated admission-error replies. On Windows, the TCP and HTTP tests apply the existing game-network workaround for the JDK Selector's AF_UNIX wakeup pipe. Production code changes no JVM properties. Sixteen related Handler/identity/Fory/packet/event tests pass with the enum-based application configuration. TCP verification does not certify TLS/WebSocket or production performance.

Build from the root:

```shell
mvn -pl game-demo -am clean verify
```

[DataSpringWiringTest](src/test/java/cn/managame/demo/DataSpringWiringTest.java) uses an in-memory JDBC stub to check property injection without an explicit placeholder configurer, component scanning, initialized Data Repository injection, Data closure and missing-URL rejection. Its lifecycle test follows the current nonblocking bootstrap: initialization returns so main can start TCP/HTTP, and Context closure releases Spring resources. [DataRepositoryRegistrationTest](src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java) covers all three Repository kinds, one-time construction, injection callbacks using initialized repositories, and rejection of prototype scope. The current demo `clean verify` passes all 23 tests, including the real TCP and HTTP/Timer/Cron integration tests. These stub tests do not verify native MySQL. Earlier local MySQL startup/shutdown was checked with the previous entry; the current complete Spring/Data/TCP/HTTP entry has not been executed against MySQL in this verification. These checks do not establish all database operations, fault scenarios or production timing/capacity. Root `clean verify` remains blocked by existing RPC tests referencing removed APIs such as maxPendingCalls; The selective reactor `mvn -pl game-runtime,game-data,game-spring,game-demo clean verify` succeeds with baseline Core/Network/RPC artifacts available in the local Maven repository. The module uses existing [OGBS specifications](../docs/ogbs/README.md) and defines no new framework contract; component examples remain in [game-example](../game-example/README.md).


## Packet Metadata, errors and management

GamePacketHandler passes command/seq/code through [GamePacketMetadata](src/main/java/cn/managame/demo/network/GamePacketMetadata.java), using application-owned int keys 1/2/3. Handlers read context.metadata(); these keys are not reserved Core IDs. A RuntimeDispatchException admission rejection writes a packet with the original command/seq, framework error code, and empty body, without automatic connection closure or retries. WriteStatus is logged; acceptance does not prove delivery. Unknown commands, invalid Fory bodies/type mismatches and missing authentication policy remain exception paths. Handler execution errors remain RuntimeErrorHandler reports and do not automatically create a remote response. These policies do not alter packet bytes or implement authentication.

POST /demo/echo receives String directly; POST /demo/dto and GET /demo/query receive EchoRequest without a context parameter. Contexts.current(HttpContext.class) supplies the current Route; non-GET still needs JSON body routeKey even when the parameter is String. Example query: /demo/query?routeKey=1&text=hello. Runtime stats and Data stats/flush are explicit management APIs, with no new unauthenticated management endpoint. The reusable integration contracts are [container semantics](../docs/ogbs/OGBS-Spring-1.0.md) and [Java specification](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.md).
