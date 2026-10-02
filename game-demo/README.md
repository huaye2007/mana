# game-demo

**[English](README.md)** | [简体中文](README.zh-CN.md)

Plain Spring application in the root Maven reactor, using JDK 25 and the repository parent. Application sources live under `cn.managame.demo`. `spring-context:7.0.9` provides dependency injection; application-level HikariCP and MySQL Driver dependencies provide the pool and JDBC implementation.

[GameDemo](src/main/java/cn/managame/demo/GameDemo.java) starts a Spring context and registers its shutdown hook. [MysqlConfig](src/main/java/cn/managame/demo/common/mysql/MysqlConfig.java) loads `application.properties` and creates an application-owned pool. Business Repository classes use Spring's annotation:

```java
@Repository
public class UserRepository extends SingleRepository<Long, User> {
}
```

Configure `game.db.url`, `game.db.username`, and `game.db.password` in `src/main/resources/application.properties` before starting `cn.managame.demo.GameDemo` in an IDE with JDK 25. The database must already exist; Data creates missing state tables/columns/indexes. A missing or blank JDBC URL fails before a connection pool is opened, identifying `game.db.url` as the required setting. JVM system properties and environment variables may also supply these keys through Spring's Environment.

`@PropertySource` loads the file and Spring's default embedded value resolver injects `@Value`; no explicit `PropertySourcesPlaceholderConfigurer` Bean is needed. After successful initialization, the entry prints `game-demo started. Press Ctrl+C to stop.` and returns to main, which starts a packet echo TCP server on port 9000. Netty's owned threads keep the process running. The current entry has no blocking main-thread wait; JVM shutdown invokes Spring's hook. Context closure first closes the listener and its owned network resources, then Data and the application-owned pool. No extra keep-alive thread or HTTP listener is created.

[GameDataConfig](src/main/java/cn/managame/demo/common/data/GameDataConfig.java) collects scanned `@Repository` Beans extending SingleRepository, GroupRepository, or LogRepository without instantiating them. It registers their types with GameDataBuilder and replaces their Bean instance suppliers with `data.repository(type)`. Bean names, qualifiers, and Spring field/setter injection are retained. Data initializes each instance before Spring injection callbacks or business access; adding a Repository within the component scan needs no per-class registration or `@Bean` method. Repository scope must be singleton (other scopes fail at startup), and the existing Data requirement for a direct parameterized base and a no-argument constructor remains. Constructor dependency injection is not supported because GameData constructs these instances. This adapter belongs to the demo application; game-data has no Spring dependency.

User's JSON Map initializes as ConcurrentHashMap<Integer,Long> and default loading preserves that implementation and generic types. Its empty-map default is supplied by Java construction and full-row INSERT; raw `defaultValue="{}"` is not a SQL DDL expression and is omitted. See [default JSON binding and limits](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.md#default-json-field-binding). Custom JSON behavior uses `jsonCodec(...)`.

## GamePacket TCP framing

[GamePacket](src/main/java/cn/managame/demo/network/GamePacket.java) carries `int command`, `int seq`, `int code`, and raw `byte[] body`. The codecs belong to this application; they do not define RPC wire format or add a framework protocol. Each integer uses four bytes in big-endian order:

| Offset | Field | Meaning |
| --- | --- | --- |
| 0 | length | 12 + body.length; excludes its own four bytes |
| 4 | command | Application command ID |
| 8 | seq | Application request/response sequence |
| 12 | code | Application result code |
| 16 | body | Uninterpreted bytes, with no JSON/string/object serialization |

[GamePacketDecoder](src/main/java/cn/managame/demo/network/GamePacketDecoder.java) accumulates split frames and emits multiple coalesced frames in order. Incomplete input emits no packet. Both codecs default to a 1 MiB maximum **total frame size**, including the 16-byte header; constructors accept a different limit of at least 16. Lengths below 12 reject with CorruptedFrameException; lengths beyond the configured bound reject with TooLongFrameException as soon as the length arrives. EOF with an incomplete header/body reports CorruptedFrameException. The demo's onException closes the connection. Command/seq/code are transported unchanged; their business meaning is not validated by these codecs.

The decoder copies the body into an independent heap array, releasing its input through Netty's decoder lifecycle. GamePacket is not reference-counted and requires no retain/release. An empty body uses an empty array; `setBody(null)` throws NullPointerException. The setter/getter do not copy the array. After `Connection.write(packet)` returns ACCEPTED, do not change the packet or its body array; acceptance still does not guarantee peer receipt. The encoder checks the total size before emitting bytes. Decoder instances are per-connection; the stateless encoder can be shared.

The entry installs both codecs with `pipeline(p -> p.addLast(new GamePacketDecoder(), new GamePacketEncoder()))`. onMessage receives GamePacket and echoes it with `connection.write(packet)`. Applications can send their own data in the same form:

```java
GamePacket packet = new GamePacket();
packet.setCommand(1001);
packet.setSeq(42);
packet.setCode(0);
packet.setBody(new byte[]{1, 2, 3});
if (connection.write(packet) != WriteStatus.ACCEPTED) connection.close();
```

[GamePacketCodecTest](src/test/java/cn/managame/demo/network/GamePacketCodecTest.java) validates exact wire bytes, fragmentation/coalescing, empty/binary bodies, size limits, EOF, and input reference release. [GamePacketNetworkTest](src/test/java/cn/managame/demo/network/GamePacketNetworkTest.java) validates GamePacket callbacks/writes over real local TCP with borrowed EventLoopGroups and explicit cleanup. On Windows, only this test applies the existing game-network workaround for the JDK Selector's AF_UNIX wakeup pipe. Production code changes no JVM properties. Seven packet tests passed; TCP verification does not certify TLS/WebSocket or production performance.

Build from the root:

```shell
mvn -pl game-demo -am clean verify
```

[DataSpringWiringTest](src/test/java/cn/managame/demo/DataSpringWiringTest.java) uses an in-memory JDBC stub to check property injection without an explicit placeholder configurer, component scanning, initialized Data Repository injection, and Data closure. It also verifies missing-URL rejection and that the entry remains running until Context closure or main-thread interruption. [DataRepositoryRegistrationTest](src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java) covers all three Repository kinds, one-time construction, injection callbacks using initialized repositories, and rejection of prototype scope. Current demo verification has eleven passing tests and one failing lifecycle test: the test expects main-thread waiting/interruption, while the entry currently returns after initialization. Lifecycle test alignment remains unfinished. These stub tests do not verify native MySQL. Local MySQL startup and shutdown were checked with the blocking entry; the current full TCP entry with Spring/Data has not been executed against MySQL in this verification. The packet TCP test runs independently of MySQL. These checks do not verify every database operation or failure scenario. Root `clean verify` remains blocked by existing RPC tests referencing removed APIs such as `maxPendingCalls`; demo production sources compile, but its current `clean verify` fails on the lifecycle test. The module uses existing [OGBS specifications](../docs/ogbs/README.md) and defines no new framework contract; component examples remain in [game-example](../game-example/README.md).
