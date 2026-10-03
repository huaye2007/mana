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

`@PropertySource` loads the file and Spring's default embedded value resolver injects `@Value`; no explicit `PropertySourcesPlaceholderConfigurer` Bean is needed. After successful initialization, the entry prints `game-demo started. Press Ctrl+C to stop.` and returns to main, which starts a packet dispatch TCP server on port 9000. Netty's owned threads keep the process running. The current entry has no blocking main-thread wait; JVM shutdown invokes Spring's hook. Context closure first closes the listener and its owned network resources, then Data and the application-owned pool. No extra keep-alive thread or HTTP listener is created.

[GameDataConfig](src/main/java/cn/managame/demo/common/data/GameDataConfig.java) collects scanned `@Repository` Beans extending SingleRepository, GroupRepository, or LogRepository without instantiating them. It registers their types with GameDataBuilder and replaces their Bean instance suppliers with `data.repository(type)`. Bean names, qualifiers, and Spring field/setter injection are retained. Data initializes each instance before Spring injection callbacks or business access; adding a Repository within the component scan needs no per-class registration or `@Bean` method. Repository scope must be singleton (other scopes fail at startup), and the existing Data requirement for a direct parameterized base and a no-argument constructor remains. Constructor dependency injection is not supported because GameData constructs these instances. This adapter belongs to the demo application; game-data has no Spring dependency.

User's JSON Map initializes as ConcurrentHashMap<Integer,Long> and default loading preserves that implementation and generic types. Its empty-map default is supplied by Java construction and full-row INSERT; raw `defaultValue="{}"` is not a SQL DDL expression and is omitted. See [default JSON binding and limits](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.md#default-json-field-binding). Custom JSON behavior uses `jsonCodec(...)`.

## Publishing a Runtime event

[GameRuntimeConfig](src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java) builds a Spring-managed GameRuntime, registers Domain 1 (`ROLE`) with a virtual-thread RouteExecutor, and collects Spring Beans marked `@EventHandler` through `builder.eventHandlers(...)`. Business listeners only need `@EventHandler`: the configuration adds an annotation include filter for `cn.managame.demo`, so Spring discovers these classes as Beans without `@Component`. This Spring scanning adaptation belongs to the demo; the Runtime annotation itself has no Spring dependency. The configuration calls Events.bind(runtime) during bootstrap so external business code can publish statically. It closes the newly built Runtime if default binding conflicts with another instance. Context shutdown closes Runtime, releases its default event binding, and closes its owned execution/timer resources.

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

[GameDomain](src/main/java/cn/managame/demo/common/runtime/GameDomain.java) is the application's enum of execution domains. It defines ROLE with explicit ID 1 and LOGIN with explicit ID 2; GameRuntimeConfig converts enum values to RouteDomain registrations and binds their IDs to the executor. Handler annotations use the compile-time constant `@Handler(domain = GameDomain.ROLE_ID)`, since Java annotation elements cannot accept arbitrary application enum types or calls such as ROLE.id(). IDs never come from ordinal(), so reordering the enum does not change routes. Domain groups execution; it does not determine role identity, require authentication by itself, or automatically select routeKey. Both routeKey and authenticated roleId remain explicitly supplied by business integration.

GameRuntimeConfig also discovers marker-only `@Handler` Beans and registers them through `builder.handlers(...)`. [UserHandler](src/main/java/cn/managame/demo/bus/user/UserHandler.java) uses `@Handler(domain=GameDomain.ROLE_ID)` with `@HandlerMethod(domain=GameDomain.LOGIN_ID)` on `login(DefaultHandlerContext context, LoginReq request)`; get its connection through `context.connection()`. Connection is not a separate method parameter. GameProtocols registers LoginReq/ LoginRes at command 1003, associates the response, and assigns Fory type IDs 3/4.

The caller normally already knows the Key, and only needs:

```java
runtime.dispatch(connection, routeKey, loginReq);
```

Runtime selects the Handler Domain and creates the context; a protocol userId never overrides the supplied Key. No extraction configuration is required. Only exceptional protocol-based routing needs `@HandlerMethod(routeKey="userId")` or `routeKeyMethod="getUserId"`, on a builder without a configured HandlerContextFactory, followed by `runtime.dispatch(connection, loginReq)`. The demo configures a factory, which explicitly selects routing by Domain as described below. Those names refer to LoginReq's field or public no-argument getter. This anonymous login uses identity 0/0 and empty Metadata; explicit Context dispatch remains available for Metadata/custom fields.

Business handlers read identity directly from Context. [RoleHandler](src/main/java/cn/managame/demo/bus/role/RoleHandler.java) declares `ping(DefaultHandlerContext context, PingMessage request)` and reads `long roleId = context.businessId()`. No RoleId/GuildId/RoomId wrapper or handlerArguments registration is needed. GameDomain.ROLE_BUSINESS_ID_TYPE=1 names the application's role identity category, independently of its execution Domain. Trusted integration can supply identity explicitly:

```java
runtime.dispatch(connection, 99L, GameDomain.ROLE_BUSINESS_ID_TYPE, 10001L, new PingMessage(1234));
```

Domain 1 comes from @Handler, Key is 99, and context.businessId() is 10001; neither the Key nor protocol timestamp selects identity. Integration authenticates and supplies trusted identity. In the network entry, the ROLE policy rejects a missing session before queueing, then captures the role category and ID in Context. Explicit-Key/identity/context overloads bypass that policy, so their callers must supply the appropriate trusted identity; a Context parameter does not automatically validate a business category or authenticate the caller. Only the Handler runs on its Route. [DemoIdentityTest](src/test/java/cn/managame/demo/DemoIdentityTest.java) verifies Spring dispatch without argument bindings, distinct identity/Key/protocol values, virtual-thread execution, and missing-session rejection without database/socket access. See the [complete argument contract](../docs/ogbs/OGBS-Runtime-Java-25-Specification-1.0.md#handler-arguments).

[DemoHandlerTest](src/test/java/cn/managame/demo/DemoHandlerTest.java) loads only Runtime configuration and replaces the empty login implementation with a manual test probe, verifying caller Key 99 while LoginReq.userId=10001, Domain 2, original connection/request, and a virtual thread. It also verifies unbound LOGIN dispatch using userId solely for routing, no connection attribute access, and rejection of Key 0 before admission. It needs no database or socket. The TCP entry now dispatches decoded messages to Runtime Handlers; UserHandler's login business logic remains empty and no LoginRes is generated automatically. Full demo startup still requires database configuration.

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
public void login(DefaultHandlerContext context, LoginReq loginReq) {
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

[GamePacketCodecTest](src/test/java/cn/managame/demo/network/GamePacketCodecTest.java) validates exact wire bytes, fragmentation/coalescing, empty/binary bodies, size limits, EOF, and input reference release. [GamePacketNetworkTest](src/test/java/cn/managame/demo/network/GamePacketNetworkTest.java) uses the actual Spring GamePacketHandler over real local TCP with borrowed EventLoopGroups and explicit cleanup. Marker-only test probes receive decoded LoginReq (1003) and PingMessage (1002) in their respective HandlerMethods on virtual threads, with the server-side connection and session Route/identity. The login test probe uses a test-only token verifier: an invalid token leaves the session absent, and successful verification binds it inside HandlerMethod before a ROLE ping. This fixture does not implement production authentication. A PingMessage under command 1001 closes the connection without another business invocation or automatic response. [GamePacketDispatchTest](src/test/java/cn/managame/demo/network/GamePacketDispatchTest.java) verifies no session creation or overwrite on connection establishment, session-free LOGIN routing, identity-required rejection before queueing, session/decoded-object capture while queued, missing Handler/session rejection, and absence of automatic writes. On Windows, only the TCP test applies the existing game-network workaround for the JDK Selector's AF_UNIX wakeup pipe. Production code changes no JVM properties. Sixteen related Handler/identity/Fory/packet/event tests pass with the enum-based application configuration. TCP verification does not certify TLS/WebSocket or production performance.

Build from the root:

```shell
mvn -pl game-demo -am clean verify
```

[DataSpringWiringTest](src/test/java/cn/managame/demo/DataSpringWiringTest.java) uses an in-memory JDBC stub to check property injection without an explicit placeholder configurer, component scanning, initialized Data Repository injection, and Data closure. It also verifies missing-URL rejection and that the entry remains running until Context closure or main-thread interruption. [DataRepositoryRegistrationTest](src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java) covers all three Repository kinds, one-time construction, injection callbacks using initialized repositories, and rejection of prototype scope. Current demo verification has nineteen passing tests and one failing lifecycle test: the test expects main-thread waiting/interruption, while the entry currently returns after initialization. Lifecycle test alignment remains unfinished. These stub tests do not verify native MySQL. Local MySQL startup and shutdown were checked with the blocking entry; the current full TCP entry with Spring/Data has not been executed against MySQL in this verification. The packet TCP test runs independently of MySQL. These checks do not verify every database operation or failure scenario. Root `clean verify` remains blocked by existing RPC tests referencing removed APIs such as `maxPendingCalls`; demo production sources compile, but its current `clean verify` fails on the lifecycle test. The module uses existing [OGBS specifications](../docs/ogbs/README.md) and defines no new framework contract; component examples remain in [game-example](../game-example/README.md).
