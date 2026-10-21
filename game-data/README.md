# game-data

**[English](README.md)** | [简体中文](README.zh-CN.md)

<a id="规范文档"></a>

## Specifications

| Specification (language-independent) | Java Development Specification |
| --- | --- |
| [OGBS Data Specification](../docs/ogbs/OGBS-Data-1.0.md) | [OGBS Data Java Development Specification](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.md) |

Component behavior and Java implementation are maintained separately in these specifications. This document is the usage entry point.

Java 25 reference implementation of OGBS Data. Data is cached in memory, and business reads and writes touch only memory. Changes are written back to MySQL or MongoDB asynchronously in the background, batched per table. Append-only MySQL logs are included.

## Getting started

**1. Entity.** Every non-static, non-transient instance field is saved, with no annotation per field ([D-MAP-01](../docs/ogbs/OGBS-Data-1.0.md#d-map-01)). Collections, maps and objects become JSON columns automatically.

```java
@Table(name = "player")
public class Player {
    @Id private long id;
    private String name;
    private int level;
    private Map<Integer, Long> items = new ConcurrentHashMap<>();   // read by the save thread: use a thread-safe collection
    private transient long lastSeen;                                // transient: not saved

    Player() {}                          // no-argument constructor for loading
    Player(long id) { this.id = id; }    // factory for getOrCreate
    void levelUp() { level++; }
}
```

Write `@Column` only to rename a column, choose its type, set a default or a VARCHAR length. A field must not be final: startup fails and suggests dropping final or marking the field transient.

**2. Repository.** Extend one base class; no methods to write.

```java
public class Players extends SingleRepository<Long, Player> {}
```

**3. Build.** The application supplies the DataSource and its pool; add `rewriteBatchedStatements=true` to the URL.

```java
GameData data = GameDataBuilder.builder()
        .mysql(dataSource)                  // jdbc:mysql://host:3306/game?rewriteBatchedStatements=true
        .repositories(Players.class)
        .build();                           // schema CREATE by default: creates missing tables and columns
Players players = data.repository(Players.class);
```

**4. Read and write.**

```java
Player p = players.getOrCreate(id, Player::new);       // creates and records an INSERT when absent
players.update(id, Player::levelUp);                   // read, change and record in one step
players.delete(id);
```

`update(id, change)` replaces the three steps "`get` → change fields → `update(entity)`", so a change can no longer be made and then never recorded. It returns false when the entity is absent. Changing fields directly and then calling `update(entity)` also works. Group entities (a player's tasks, for example) use `GroupRepository`: `getGroup(key)` returns a read-only view, and `update(groupKey, mapKey, change)` works the same way. Logs use `LogRepository.insert`.

Spring applications use [game-spring](../game-spring/README.md). `@Repository` Beans are registered automatically, and tuning is set through `game.data.*` properties ([Spring Java specification](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.md)).

## Performance

Business threads only touch memory. The save thread runs one pass every `flushInterval` (1 s). Changes to one table are merged into batches and saved together, and different tables are saved in parallel on `saveParallelism` (4) threads. One table is only ever written by one thread. On MySQL the component merges UPDATEs into multi-row statements of up to 100 rows each. The driver merges INSERTs when `rewriteBatchedStatements=true` is set.

Measured on a local MySQL 9.7 over loopback with a HikariCP pool ([measurement record](../docs/reviews/2026-10-21-data-save-path-parallel.md)):

| Scenario | Rows/s |
| --- | --- |
| One table, UPDATE, batchSize 500 | ≈45,700 (≈14,300 with one UPDATE per row) |
| One table, UPDATE, batchSize 2000 | ≈72,500 (≈9,300 with one UPDATE per row) |
| 4 tables, UPDATE, saveParallelism 1 → 4 | ≈46,300 → 119,800 |
| 4 tables, INSERT, saveParallelism 1 → 4 | ≈51,200 → 133,900 |

Common settings (full list and limits in [Java specification §3](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.md#3-build-and-lifecycle)):

| Setting | Default | When to change it |
| --- | --- | --- |
| batchSize | 500 | 2000 for an INSERT-heavy store, or one table with heavy writes |
| saveParallelism | 4 | Raise it for many tables when the database takes more concurrent connections; keep the pool larger than it |
| flushInterval | 1 s | A crash loses at most about one interval of changes |
| maxPendingChanges | 100,000 | Bound on entities waiting to be saved. When it is full, a write that adds an entity waits and is then refused |
| maxCacheEntries | 1,000,000 per repository | Over it, the clean entries longest unread are evicted; unsaved entries never are |

## Rules to follow

- **Use thread-safe collections in collection fields.** The save thread reads entity fields while encoding, with no snapshot. Use ConcurrentHashMap, CopyOnWriteArrayList and the like. Startup logs a WARNING for a HashMap, an ArrayList or an uninitialized collection field ([D-OWN-01](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.md#21-cache-and-mutation-paths)).
- **Do not read uncached data on a Netty EventLoop.** Loads are synchronous; call them on the default virtual-thread Route.
- **Size the pool.** It must cover the tables being saved at once plus the concurrent loads.
- **Keep MySQL's `useAffectedRows=false` (the driver default).** Multi-row UPDATE relies on the found-rows count to detect a missing row.
- **How save failures are handled.** Retryable failures (lost connection, timeout, deadlock) stay pending for the next pass. A non-retryable bad row is split out, dropped and handed to `errorHandler`; archive it there. Close saves what it can within `shutdownTimeout` and hands what is left to `errorHandler` too.

## Storage

MySQL uses the application's DataSource/JDBC Driver; Mongo uses the application's MongoDatabase/CodecRegistry (`MongoEntityMapper`). The framework does not close these external resources. The Mongo driver is optional in game-data; applications using Mongo declare org.mongodb:mongodb-driver-sync themselves. Logs are a store of their own: `logMysql(DataSource)` names it, and `mysql(DataSource)` never serves it.

`schema` decides what startup does to the database. `CREATE` (the default) creates missing tables, columns and declared indexes, and validates what is already there. `VALIDATE` issues no DDL and fails on anything missing or too narrow. `NONE` reads no catalog. `CREATE` tolerates another instance of the same service applying the same structure at the same moment ([D-STORE-02](../docs/ogbs/OGBS-Data-1.0.md#8-storage-adapter-semantics)). JSON columns bind to the field's full generic type and its initializer's class, for example `ConcurrentHashMap<Integer,Long>`, with no type registration ([JSON binding](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.md#default-json-field-binding)).

`GameData.flush()` synchronously saves the changes accepted before the call. `GameData.stats()` reports pending, cached and dropped counts ([flush and DataStats](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.md#11-explicit-flush-and-datastats)).

<a id="实机测试"></a>

## Tests

```shell
mvn -pl game-data -am test
```

Default tests need no external services: in-memory Mappers (usage in [DataContractTest](src/test/java/cn/managame/data/DataContractTest.java)), H2 in MySQL mode and BSON encoding. Live tests are skipped by default and require explicit environment variables:

```powershell
$env:OGBS_DATA_MYSQL_URL = 'jdbc:mysql://127.0.0.1:3306/ogbs_data_test?rewriteBatchedStatements=true'
$env:OGBS_DATA_MYSQL_USER = 'test_user'
$env:OGBS_DATA_MYSQL_PASSWORD = 'test_password'
$env:OGBS_DATA_MONGO_URI = 'mongodb://127.0.0.1:27017'
mvn -pl game-data -am test "-Dtest=DatabaseIntegrationTest" -Dsurefire.failIfNoSpecifiedTests=false
```

Use a dedicated disposable MySQL test database. The tests create and drop the ogbs_data_probe and ogbs_data_flood tables, and refuse to run if either already exists. Mongo creates a random ogbs_data_test_* database and drops it afterwards. Do not point them at production. For save-throughput load tests, see DataSavePathLoad in [game-benchmark](../game-benchmark/README.md).
