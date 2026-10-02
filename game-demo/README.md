# game-demo

**[English](README.md)** | [简体中文](README.zh-CN.md)

Plain Spring application in the root Maven reactor, using JDK 25 and the repository parent. Application sources live under `cn.managame.demo`. `spring-context:7.0.9` provides dependency injection; application-level HikariCP and MySQL Driver dependencies provide the pool and JDBC implementation.

[GameDemo](src/main/java/cn/managame/demo/GameDemo.java) starts a Spring context and registers its shutdown hook. [MysqlConfig](src/main/java/cn/managame/demo/common/mysql/MysqlConfig.java) loads `application.properties`, creates an application-owned pool, and builds Data with:

```java
GameData data = GameDataBuilder.builder()
        .mysql(dataSource)
        .repositories(UserRepository.class)
        .build();
UserRepository repository = data.repository(UserRepository.class);
```

Configure `game.db.url`, `game.db.username`, and `game.db.password` before starting GameDemo in an IDE. No HTTP listener is created yet. The entry currently initializes beans; it has no blocking service loop. Spring's shutdown hook closes Data before its dependent pool. Do not run startup against a database whose schema you do not intend to initialize: Data can create missing state tables/columns/indexes.

Repository is exposed as a Bean from GameData, avoiding a separately component-scanned uninitialized instance. User's JSON Map initializes as ConcurrentHashMap<Integer,Long> and default loading preserves that implementation and generic types. Its empty-map default is supplied by Java construction and full-row INSERT; raw `defaultValue="{}"` is not a SQL DDL expression and is omitted. See [default JSON binding and limits](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.md#default-json-field-binding). Custom JSON behavior uses `jsonCodec(...)`.

Build from the root:

```shell
mvn -pl game-demo -am clean verify
```

[DataSpringWiringTest](src/test/java/cn/managame/demo/DataSpringWiringTest.java) uses an in-memory JDBC stub to check that Spring injects the initialized Data Repository and closes Data. It does not connect to the configured database or verify native MySQL. Real database startup has not been verified in this task. The module uses existing [OGBS specifications](../docs/ogbs/README.md) and defines no new framework contract; component examples remain in [game-example](../game-example/README.md).
