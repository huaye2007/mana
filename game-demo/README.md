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

`@PropertySource` loads the file and Spring's default embedded value resolver injects `@Value`; no explicit `PropertySourcesPlaceholderConfigurer` Bean is needed. After successful initialization, the entry prints `game-demo started. Press Ctrl+C to stop.` and returns to main, which runs a user/role write example. The current entry has no blocking wait or explicit Context closure; JVM shutdown invokes Spring's hook, closing Data before the application-owned pool. No extra keep-alive thread or HTTP listener is created.

[GameDataConfig](src/main/java/cn/managame/demo/common/data/GameDataConfig.java) collects scanned `@Repository` Beans extending SingleRepository, GroupRepository, or LogRepository without instantiating them. It registers their types with GameDataBuilder and replaces their Bean instance suppliers with `data.repository(type)`. Bean names, qualifiers, and Spring field/setter injection are retained. Data initializes each instance before Spring injection callbacks or business access; adding a Repository within the component scan needs no per-class registration or `@Bean` method. Repository scope must be singleton (other scopes fail at startup), and the existing Data requirement for a direct parameterized base and a no-argument constructor remains. Constructor dependency injection is not supported because GameData constructs these instances. This adapter belongs to the demo application; game-data has no Spring dependency.

User's JSON Map initializes as ConcurrentHashMap<Integer,Long> and default loading preserves that implementation and generic types. Its empty-map default is supplied by Java construction and full-row INSERT; raw `defaultValue="{}"` is not a SQL DDL expression and is omitted. See [default JSON binding and limits](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.md#default-json-field-binding). Custom JSON behavior uses `jsonCodec(...)`.

Build from the root:

```shell
mvn -pl game-demo -am clean verify
```

[DataSpringWiringTest](src/test/java/cn/managame/demo/DataSpringWiringTest.java) uses an in-memory JDBC stub to check property injection without an explicit placeholder configurer, component scanning, initialized Data Repository injection, and Data closure. It also verifies missing-URL rejection and that the entry remains running until Context closure or main-thread interruption. [DataRepositoryRegistrationTest](src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java) covers all three Repository kinds, one-time construction, injection callbacks using initialized repositories, and rejection of prototype scope. Current demo verification has four passing tests and one failing lifecycle test: the test expects main-thread waiting/interruption, while the entry currently returns after initialization. Lifecycle test alignment remains unfinished. These stub tests do not verify native MySQL. Local MySQL startup and shutdown were checked with the blocking entry; the current nonblocking user/role write example has not been executed against MySQL in this verification. These checks do not verify every database operation or failure scenario. Root `clean verify` remains blocked by existing RPC tests referencing removed APIs such as `maxPendingCalls`; demo production sources compile, but its current `clean verify` fails on the lifecycle test. The module uses existing [OGBS specifications](../docs/ogbs/README.md) and defines no new framework contract; component examples remain in [game-example](../game-example/README.md).
