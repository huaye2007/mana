# game-data

English | [简体中文](README.zh-CN.md)

Java entity loading, Caffeine caching, and asynchronous batch persistence for game servers. This README is the default English documentation. The Chinese edition describes the same APIs and behavior.

## Requirements and layout

The standalone game-data build targets Java 21. Use JDK 21 or later and Maven. Other projects in this repository may require a newer JDK. A database is needed to run the database examples; regression tests use controlled in-memory DataAccess implementations.

game-data is one Maven module producing one JAR. Core, JDBC, MongoDB, JSON, and optional Spring support are organized by Java packages instead of child Maven modules. Tests and runnable examples live in the test source tree and are not included in the library JAR.

```text
game-data/
  pom.xml
  README.md
  README.zh-CN.md
  src/main/java/cn/managame/
    annotation/
    core/
      GameData.java
      DataException.java
      RepositoryDefinition.java
      access/
      cache/
      key/
      log/
      mapping/
      metadata/
      repository/
      write/
    rdb/
    docdb/mongodb/
    codec/jackson/
    spring/
  src/test/java/cn/managame/
    core/mapping/
    core/repository/
    core/write/
    support/
    docdb/mongodb/
    example/
```

| Area | Java packages | Purpose |
| --- | --- | --- |
| Core entry | `cn.managame.core` | GameData lifecycle, common exception, package-private factory resolution |
| Annotations | `cn.managame.annotation` | Entity, field, key, and index declarations |
| access | `cn.managame.core.access` | Database access contract and queries |
| cache | `cn.managame.core.cache` | Entity cache and residency mode |
| key | `cn.managame.core.key` | Composite group and map keys |
| log | `cn.managame.core.log` | Business log repositories and event-time partitioning |
| mapping | `cn.managame.core.mapping` | Entity/value conversion and JSON codec contract |
| metadata | `cn.managame.core.metadata` | Entity/property metadata, indexes, and storage kinds |
| repository | `cn.managame.core.repository` | Single/group repositories and caches |
| write | `cn.managame.core.write` | Write queues, batching, results, failures, and metrics |
| JDBC | `cn.managame.rdb`, `cn.managame.rdb.dialect` | MySQL/MariaDB/PostgreSQL dialects and schema management |
| MongoDB | `cn.managame.docdb.mongodb` | MongoDB access |
| JSON | `cn.managame.codec.jackson` | Jackson JSON codec |
| Spring | `cn.managame.spring` | Optional Spring repository scanning and bean registration |
| Examples (test sources) | `cn.managame.example` | MySQL and MongoDB entry points |

Java packages remain under `cn.managame`. The single artifact is `cn.managame:game-data:0.1.0-SNAPSHOT`. Install it locally before using this snapshot from another project:

```sh
mvn -f game-data/pom.xml install
```

Consumers use one game-data dependency:

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-data</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

RDB and DocDB are both optional backends. The single JAR contains their implementations, but GameData uses only the DataAccess instances explicitly supplied by the application. You can use RDB alone, DocDB alone, both through named DataAccess instances, or a custom DataAccess with neither built-in backend. RDB uses the JDK JDBC API and needs only the chosen database driver; it does not require MongoDB. DocDB does not require a JDBC driver.

Caffeine is included transitively. MongoDB, Jackson, and Spring are optional Maven dependencies: their adapter classes are in the JAR, but consumers add the matching dependency only when using those classes. JDBC users add their database driver. MySQL Connector/J is a test dependency here so examples can run from the test classpath.

| Feature | Additional consumer dependency |
| --- | --- |
| JDBC/MySQL | `com.mysql:mysql-connector-j:9.7.0` (runtime) |
| MongoDB | `org.mongodb:mongodb-driver-sync:5.6.5` |
| Jackson JSON codec | `com.fasterxml.jackson.core:jackson-databind:2.22.2` |
| Spring integration | `org.springframework:spring-context` (application-managed version) |

## Quick start

Define an entity with an ID and a no-argument constructor, then define a business repository interface. Each public type below belongs in its own Java file.

```java
import cn.managame.annotation.Id;
import cn.managame.annotation.Table;

@Table("player")
public class Player {
    @Id
    private long id;
    private int level;

    public Player() { }
    public Player(long id, int level) { this.id = id; this.level = level; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
}
```

```java
import cn.managame.core.repository.SingleRepository;

public interface PlayerRepository extends SingleRepository<Player, Long> {
    default boolean exists(long id) { return get(id).isPresent(); }
}
```

With MySQL running and its JDBC driver on the classpath, initialize GameData and retrieve the repository by its interface. Choose an unused ID for this insert example. Credentials below are placeholders.

```java
import cn.managame.core.GameData;
import cn.managame.rdb.DriverManagerDataSource;
import cn.managame.rdb.RdbDataAccess;
import cn.managame.rdb.dialect.MySqlDialect;

public class QuickStart {
    public static void main(String[] args) {
        var source = new DriverManagerDataSource(
                "jdbc:mysql://localhost:3306/game", "game", "change-me");
        try (var gameData = new GameData(new RdbDataAccess(source, new MySqlDialect()))) {
            PlayerRepository players = gameData.repository(PlayerRepository.class);
            Player player = new Player(10001L, 1);
            players.insert(player);
            player.setLevel(2);
            players.update(player);
            gameData.flush();
        }
    }
}
```

`insert()` and `update()` update the cache and enqueue writes. `flush()` waits for pending work to be processed; it is not a guarantee that every batch succeeded. Inspect failure logs and metrics. See the runnable [MySQL example](src/test/java/cn/managame/example/MysqlExample.java) and [MongoDB example](src/test/java/cn/managame/example/MongoExample.java).

## Business repository interfaces

Use one entry point for both repository modes:

- `SingleRepository<Entity, ID>`: load one entity by ID.
- `GroupRepository<Entity, ID>`: load and manage a complete group.

`gameData.repository(MyRepository.class)` uses the default data source. `gameData.repository("game", MyRepository.class)` uses a named source. The factory resolves entity and key types from generic interface inheritance and initializes the matching implementation on first lookup.

For the same data source name, repeated lookup of an interface returns the same instance. Different interfaces with the same entity and mode share the underlying implementation and cache. Binding one entity to both Single and Group modes is rejected.

Business interfaces must be non-sealed and bind entity/key parameters to concrete classes. Raw or unresolved generics, entity classes passed instead of interfaces, and interfaces extending multiple repository modes are rejected at initialization. Implement custom helpers as `default` methods; additional abstract query methods are rejected, not translated into SQL. CRUD calls dispatch to the implementation, and default methods use MethodHandles bound at initialization.

## Spring repositories

Spring applications mark business interfaces with Spring's own `org.springframework.stereotype.Repository` and inject them using constructors or `@Autowired`. Enable interface scanning once with `@EnableGameDataRepositories`; Spring's ordinary component scan does not instantiate repository interfaces. The adapter registers singleton FactoryBeans backed by `GameData.repository(...)`, preserving the same repository instances, caches, and asynchronous saving behavior.

Add `org.springframework:spring-context` (compiled with 6.2.19; integration tests pass on 6.2.19 and 7.0.8), or use the Spring version managed by your application. This dependency is optional in game-data, so plain Java applications do not acquire Spring. Spring Boot applications can put the enable annotation on their application or configuration class; no Boot starter is required.

```java
import cn.managame.core.repository.SingleRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlayerRepository extends SingleRepository<Player, Long> {
}
```

```java
import cn.managame.core.GameData;
import cn.managame.core.access.DataAccess;
import cn.managame.spring.EnableGameDataRepositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableGameDataRepositories(basePackageClasses = PlayerRepository.class)
public class GameDataConfig {
    @Bean(destroyMethod = "close")
    GameData gameData(DataAccess dataAccess) {
        return new GameData(dataAccess);
    }
}
```

```java
import org.springframework.stereotype.Service;

@Service
public class PlayerService {
    private final PlayerRepository players;

    public PlayerService(PlayerRepository players) {
        this.players = players;
    }
}
```

Supply your configured `DataAccess` as a Spring bean. GameData owns its close operation; if the same DataAccess is also a bean, use `@Bean(destroyMethod = "")` on that bean to avoid closing it twice. External JDBC pools remain application-owned. Stop accepting business work and drain your Runtime before closing the Spring context; the GameData bean then drains accepted writes during destruction.

- `basePackageClasses` scans the packages of the marker types; `basePackages` accepts package names. If neither is set, the configuration class's package is used.
- Only annotated SingleRepository/GroupRepository/LogRepository interfaces are registered. Ordinary `@Repository` classes remain the responsibility of Spring component scanning. Repository creation and validation run at context startup, including interfaces with no consumers.
- `@Repository("playerRepository")` sets the **Spring bean name**. Use `@Qualifier("playerRepository")` for injection, or `@Primary` on a preferred interface.
- `gameDataRef = "gameData"` selects the owning GameData bean. `dataAccess = "archive"` selects a named DataAccess inside that owner; the default empty value uses its default source. Use separate configuration classes and repository packages for different source bindings. Conflicting bean names fail startup.

The integration uses Spring's [FactoryBean contract](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/beans/factory/FactoryBean.html) and [interface scanner extension point](https://docs.spring.io/spring-framework/docs/6.2.19/javadoc-api/org/springframework/context/annotation/ClassPathScanningCandidateComponentProvider.html).

## Plain Java repository wiring

Outside Spring, obtain business repository interfaces from `gameData.repository(...)` and pass them to services or handlers through constructors. Use `repository("archive", PlayerRepository.class)` for a named DataAccess.

```java
public class PlayerService {
    private final PlayerRepository players;

    public PlayerService(PlayerRepository players) {
        this.players = players;
    }
}
```

```java
PlayerService service = new PlayerService(gameData.repository(PlayerRepository.class));
```

## Runtime ownership and persistence model

```text
READ:  Application -> Repository -> Caffeine -> cache miss -> DataAccess -> Database
WRITE: Modify entity -> Repository.update() -> TableWriter -> Batch -> DataAccess -> Database
```

Memory is the authoritative runtime state; the database is updated asynchronously.

- The application owns login, authentication, server placement, and exclusive process ownership of data.
- Serialize business mutations of each entity. For groups, serialize access to the group, including iteration. Live entity encoding is not a point-in-time snapshot; the application owns any stronger consistency or collection-safety requirements.
- Every effective entity change must be followed by `update()`.
- There is no `@Version` check, cross-process ownership protocol, or cross-table transaction.
- Pending writes may be lost if the process crashes. Normal shutdown drains accepted work.
- The framework does not add snapshots, a WAL, or durable-response waits.

## Mapping and codecs

Annotations are in `cn.managame.annotation`; no Spring container is required. `@Table` and `@Document` set storage names, `@Id` identifies the primary key, `@Column` and `@Field` set field names, and `@Transient` excludes a field. Field accessors are bound once with MethodHandles. Entities need a no-argument constructor.

`ColumnType.AUTO` infers the storage shape. Collections, maps, non-byte arrays, and custom objects default to JSON; `byte[]` defaults to binary. Explicit field declarations can select string length, text, JSON, or binary:

```java
@Column(value = "name", type = ColumnType.STRING, length = 64)
private String name;

@Column(type = ColumnType.TEXT)
private String description;

@Column(type = ColumnType.JSON)
private java.util.List<String> rewards;

@Column(type = ColumnType.BINARY)
private byte[] payload;
```

JSON fields require an explicitly configured JsonCodec. Add the optional Jackson dependency and configure the mapper:

```java
import cn.managame.rdb.RdbValueConverter;
import cn.managame.core.mapping.EntityMapper;
import cn.managame.codec.jackson.JacksonJsonCodec;

var mapper = new EntityMapper(new RdbValueConverter(), new JacksonJsonCodec());
var access = new RdbDataAccess(dataSource, new MySqlDialect(), mapper);
```

RdbDataAccess defaults to RdbValueConverter (JDBC Date/Timestamp and decimal values); MongoDataAccess defaults to MongoValueConverter (BSON dates, Decimal128 and Binary decoding). Core DefaultValueConverter handles only backend-neutral scalars and does not select a database representation. Explicit custom mappers must use the converter for their backend, or provide equivalent conversions. For MongoDB with JSON fields:

```java
import cn.managame.core.mapping.EntityMapper;
import cn.managame.docdb.mongodb.MongoValueConverter;
import cn.managame.codec.jackson.JacksonJsonCodec;

var mapper = new EntityMapper(new MongoValueConverter(), new JacksonJsonCodec());
var access = new MongoDataAccess(mongoClient, "game", mapper, false);
```

JSON encoding in the current shared mapper produces JSON text; do not assume that Java collections are automatically stored as native BSON structures. Verify field representations for the selected backend before changing an existing schema.

Numeric conversion preserves BigInteger/BigDecimal precision without passing through long/double. BigInteger is written as an exact decimal value; a fractional database value cannot be read as BigInteger and raises an error instead of being truncated. Database column precision/scale still limits representable values. MongoDB uses BSON Decimal128 for these decimal values and rejects values that cannot be represented exactly. MongoValueConverter restores BSON Binary to byte[] and Decimal128 to the declared BigInteger/BigDecimal type on ID, query, and scan reads, including custom EntityMapper configurations using MongoValueConverter.

MongoDB LocalDate values use BSON dates and are restored in the JVM default time zone, matching the existing write conversion. All processes reading and writing these values must use the same default time zone (for example, `-Duser.timezone=Asia/Shanghai`); this fix preserves the existing storage format. ID lookup, queries, and scans use the same conversion, including a mapper configured with JacksonJsonCodec.

Built-in repositories call `DataAccess.validateMapping(metadata)` once during initialization, before schema work, resident loading or accepting mutations. Validation also runs with `ensureSchema(false)` and for LogRepository; it does not connect to a database or create log partitions. Missing JsonCodec and unsupported scalar types fail with the entity/field name. Custom DataAccess implementations can override this hook; custom ValueConverter implementations can override `validate(Class<?>)`.

| Field type | RDB | MongoDB |
| --- | --- | --- |
| Numeric primitives/wrappers, Boolean, String, enum, UUID, char/Character | Supported; char uses CHAR(1) | Supported; enum, UUID and char use strings |
| BigInteger / BigDecimal | Exact decimal conversion, subject to column precision/scale | Exact Decimal128 conversion, subject to its range/precision |
| byte[] | Binary column | BSON binary |
| Instant, LocalDateTime, LocalDate | JDBC timestamp/date types | BSON dates; timestamp precision is milliseconds |
| LocalTime | JDBC 4.2 typed binding/read, with TIME(6) column precision | Rejected during initialization |
| OffsetDateTime / OffsetTime | Rejected by built-in conversion | Rejected by built-in conversion |
| Lists, maps, arrays and object fields mapped as JSON | Configured JsonCodec required | Configured JsonCodec required; stored as JSON text |

RDB reads LocalTime using `ResultSet.getObject(column, LocalTime.class)` to retain fractional seconds available from the JDBC driver; RdbValueConverter also accepts java.sql.Time. PostgreSQL binds native UUID and JSON/JSONB fields with JDBC OTHER for both queries and writes. Explicit UUID STRING/TEXT mappings remain string parameters. Existing date, numeric and JSON storage formats remain unchanged.

## Single and group caches

SingleRepository exposes `get`, `insert`, `update`, `delete`, `deleteById`, and `resident`. `get()` returns Optional. Missing and deleted entities are cached as empty values. Caffeine `Cache.get(key, loader)` coordinates concurrent cache misses for the same key; DataAccess itself does not coordinate loads.

Repository insert/update/delete methods publish cache membership and replacement entities only after write submission succeeds. Rejection on shutdown or interruption leaves those cache entries unchanged. Direct changes already made by application code to a live entity are not rolled back; serialize operations for the same entity/group. Accepted writes remain asynchronous and do not wait for database success.

SingleRepository<T, ID> and GroupRepository<T, ID> validate ID against the entity's @Id type during initialization (primitive types match their wrappers, such as long/Long). Incorrect ID declarations fail before schema initialization or resident loading, including interfaces inheriting through generic base interfaces.

GroupRepository has only T and ID type parameters; ID always means the primary key, not the group or map key. Its API is `Map<Object, T> getGroup(Object groupKey)` and `deleteGroup(Object groupKey)`. Group/map key runtime types come from annotations. A single-field group key must match the annotated field type; mismatched arguments fail before querying or submitting deletes.

A group entity must declare `@GroupKey`. The cache stores one `Map<Object, T>` per group. Without `@MapKey`, the map key defaults to `@Id`; with one `@MapKey`, it uses that field value and type; with multiple annotations, it joins values with `:` in annotation `order`, producing a `String`. No separate map-key-to-ID mapping is maintained. This example uses the default ID key:

```java
import cn.managame.annotation.GroupKey;
import cn.managame.annotation.Id;
import cn.managame.annotation.Table;

@Table("player_task")
public class PlayerTask {
    @Id
    public long id;
    @GroupKey
    public long playerId;
    public int progress;
    public PlayerTask() { }
}
```

```java
import cn.managame.core.repository.GroupRepository;

public interface TaskRepository extends GroupRepository<PlayerTask, Long> { }
```

```java
TaskRepository tasks = gameData.repository(TaskRepository.class);
var group = tasks.getGroup(10001L);
PlayerTask task = group.get(7L);
if (task != null) {
    task.progress = 100;
    tasks.update(task);
}
tasks.deleteGroup(10001L);
```

- Call `getGroup(groupKey)` before single-entity insert, update, or delete. The cached group must represent the complete group.
- The application must keep `@Id`, `@GroupKey`, and `@MapKey` stable after loading or inserting an entity. Repositories do not retain original identity values or track field changes.
- `getGroup()` returns the cached map directly, with no wrapper or copy. Repeated lookups return the same map until cache expiry. Repository mutations update it in place. Use Repository methods for persistence; directly changing map entries does not submit database writes. Serialize access to each group, including iteration.
- `deleteGroup()` does not require loading first. After the backend deletion is accepted into the write buffer, it clears an existing map in place or caches an empty group.
- After ordinary cache expiry, reacquire the group before using it. Choose a cache expiry substantially longer than normal buffered-write latency; expiry is not a persistence barrier.

For composite keys, annotate fields with `@GroupKey(order = 0)`, `@GroupKey(order = 1)`, and so on. The composite group key is a `String`; for example, call `getGroup("10001:7")` for player ID 10001 and activity ID 7. Composite map keys follow the same rule and also use `String`. `GroupKeys.of(...)` and `MapKeys.of(...)` are builder utilities: one value is returned unchanged; multiple values produce a colon-separated string. Values must be non-null, and composite components must not contain `:`; there is no escaping. Enum components use their names. Group queries and group deletes convert string components back to their declared scalar field types before passing them to the database.

## Resident repositories

Annotate an entity with `@Resident` to load the entire table or collection during repository initialization. Schema initialization runs first when enabled, followed by `DataAccess.scan()`.

Resident caches never expire and never load missing keys from the database. Initialization fails if the scan fails; a partially loaded repository is not published. Updates still use asynchronous persistence. RDB scan maps ResultSet rows into entities; MongoDB scan iterates a cursor. These interfaces avoid creating a full intermediate list, but actual JDBC driver buffering depends on its configuration.

## Buffered writes and batching

Each physical writer key in a write engine has two reusable buffers and one virtual writer thread. Producers append to the active buffer; the writer swaps buffers and persists the detached buffer while producers continue on the other one. A short per-table synchronized section protects buffer changes, with no global admission read/write lock. A volatile accepting flag stops new submissions; only writer registration shares a monitor with shutdown. Each table drains its own accepted operations, including producers already waiting for buffer space. Submissions still resolving a writer when shutdown starts are rejected. Encoding and database I/O run outside the synchronized section. Different physical tables can write in parallel. Ordinary data and logs use separate engines and settings; use separate tables for these roles and a single configured owner for each physical table.

A batch runs when its size or flush interval is reached. The active buffer capacity applies blocking backpressure. A detached buffer is drained in chunks no larger than the effective batch size; the capacity setting is not a total pending-operation limit across both buffers. Insert/update operations hold live entity references plus identity values; field encoding happens when the backend executes the batch, not when the application submits the operation.

Within a batch, operations for the same ID are compacted as follows:

```text
INSERT + UPDATE -> INSERT using the latest entity
UPDATE + UPDATE -> latest UPDATE
UPDATE + DELETE -> DELETE
INSERT + DELETE -> no operation
DELETE + DELETE -> DELETE
DELETE + INSERT -> keep DELETE followed by INSERT
```

Group deletion is an ordering barrier. Surviving operations for different IDs retain their order. RDB batches consecutive operations that use the same SQL. Default-table SQL plans are cached during schema initialization; dynamic log INSERTs reuse the entity template without retaining per-table plans. ResultSet values are mapped directly into entities.

## Failures, flush, and shutdown

A database failure affects the current batch. If DataAccess classifies the attempt as safe to retry, the writer performs bounded retries. RDB retries require commit not to have started, a confirmed successful rollback, and a retryable transaction error. MongoDB does not replay the whole ordered bulk by default because some operations may already have succeeded.

After a final failure, WriteFailureHandler logs the unsuccessful operations synchronously and later batches continue. The framework does not retain the latest database exception, a history of exceptions, or failed batches for later replay. Counters and timestamps remain available. The default handler writes identity, current field values, and the error to stderr. A custom handler should record the failure promptly without retaining entities/errors; if it throws, logging falls back to stderr.

`BatchResult` represents SUCCESS, FAILED, UNEXECUTED, and UNKNOWN. MongoDB indexed ordered-write errors distinguish the failed item and unexecuted suffix. The executed prefix is also checked against its matched-update count: if matches are missing, all prefix UPDATE operations are marked UNKNOWN because the aggregate result cannot identify individual misses; confirmed prefix INSERT operations remain successful. Write-concern failures keep the executed prefix UNKNOWN; uncertain results are conservatively reported as UNKNOWN. RDB returns all-success after commit. A confirmed rollback reports FAILED; failure before transaction execution reports UNEXECUTED; an uncertain commit or failed rollback reports UNKNOWN through BatchWriteException and is never retried automatically. This is not an automatic repair mechanism: later writes may still be affected by a previously failed insert or delete.

RDB validates JDBC batch counts before committing. UPDATE with zero affected rows or SUCCESS_NO_INFO checks ID existence on the same connection and transaction: an existing unchanged row succeeds, while a missing row fails the batch and triggers rollback. UPDATE with a confirmed count of one needs no additional query. DELETE of an already absent ID remains successful and idempotent. Invalid INSERT counts or failed batch items also roll back the transaction.

An unresolved transaction is never cleaned up by switching auto-commit back on. Its connection is aborted before close; use a JDBC driver/pool that supports Connection.abort. If abort itself fails, the adapter logs the error and does not return that unresolved connection to the pool, which can consume a pool slot until the application disposes of it.

```java
gameData.flush();
gameData.flush(Player.class);
gameData.flush("game", Player.class);
```

Flush accepts the **entity class**, while repository lookup accepts the **business interface**. Flush waits for the relevant writers' pending counts to reach zero. Continuous submissions can extend the wait. Ordinary database batch failures are logged and are not rethrown by flush or close. Framework-level terminal failures can still be thrown.

Normal shutdown follows this order:

1. Stop application traffic and business mutations.
2. Call `GameData.close()`; all configured engines stop accepting new submissions.
3. Finish enqueueing submissions already accepted by a table writer, including those waiting for buffer space. Reject submissions that have not yet entered a writer.
4. Drain both buffers and batches already executing, without interrupting database work or safe retries.
5. Close DataAccess instances after writers finish.

Concurrent close calls wait for the same close process. An interrupted closing caller still waits for drain and retains its interrupt flag on return. Do not call blocking flush or close from a writer's failure callback. Shutdown has no framework timeout for a stuck database call; configure database timeouts. Externally owned connection pools or MongoClient instances remain the application's responsibility.

## Log repositories and automatic partitions

Business log interfaces extend `LogRepository<T>` and use the same factory and Spring registration as SingleRepository/GroupRepository. The business call is only `logs.append(log)`. The repository binds its entity type and DataAccess, while each physical partition is derived from the event-time field. No caller-supplied table name or router is required.

```java
import cn.managame.annotation.Id;
import cn.managame.annotation.LogPartition;
import cn.managame.annotation.Table;
import java.time.LocalDateTime;

@Table("player_action_log")
public class PlayerActionLog {
    @Id
    public long id;

    @LogPartition(LogPartition.Period.MONTH)
    public LocalDateTime createTime;
}
```

```java
import cn.managame.core.log.LogRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PlayerActionLogRepository extends LogRepository<PlayerActionLog> {
}
```

```java
@Autowired
private PlayerActionLogRepository logs;

// Inside a business method:
logs.append(log);
```

For `createTime` in September 2026, this appends to `player_action_log_202609`. Add `@LogPartition(Period.DAY)`, `MONTH`, or `YEAR` to exactly one mapped instance field to use suffixes `_yyyyMMdd`, `_yyyyMM`, or `_yyyy`. Without `@LogPartition`, logs use the entity's normal default table/collection name, including its `@Table`/`@Document` mapping.

The field can be LocalDate, LocalDateTime, Instant, or epoch-millisecond long/Long. Instant and epoch milliseconds use the annotation's `zone` (default `UTC`); for example, `@LogPartition(value = LogPartition.Period.DAY, zone = "Asia/Shanghai")`. Local dates/times use their own date directly. Invalid field declarations or zones fail at repository initialization; a null partition time is rejected on append. Inherited mapped fields are supported. The event time selects the partition when appended, so do not change it after append.

In Spring, `@EnableGameDataRepositories` registers this interface and constructor/`@Autowired` injection works normally. In plain Java, obtain it through the unified factory:

```java
PlayerActionLogRepository logs = gameData.repository("log", PlayerActionLogRepository.class);
logs.append(log);
```

The two-argument factory selects a named DataAccess; `repository(PlayerActionLogRepository.class)` uses the default source. Repository default methods can hold business helpers. Log entities require an ID, bypass entity caches, and are encoded from live objects by the background writer.

Appends are automatically batched; do not flush after every event. `logs.flush()` explicitly drains this entity's log writes on its source; `gameData.flushLogs()` drains all logs. Normal `GameData.close()` handles accepted writes automatically.

Partition writers release their queues and virtual threads after 5 idle minutes with no pending work. A later append to a historical partition recreates its writer. Pending batches, retries and failure callbacks prevent retirement. Configure `logWriterIdleTimeout(Duration)` on GameData.Builder; zero disables retirement. Fixed-table writers remain allocated. Entity metrics retain numeric totals, while physical metrics list current writers only.

RDB caches the INSERT template per entity and fills in the quoted partition name once per batch, without retaining per-partition SQL plans. Pure INSERT batches preserve every log and skip entity-state reduction. Tables and collections are provisioned ahead of traffic; repository initialization and append perform no log DDL. Use deployment tooling or `dataAccess.ensureSchema(metadata, physicalName)` during initialization. Partition retention/deletion remains application-owned.

## Multiple and composite indexes

Use the game-data `@Table(indexes = {...})` and `@Index` annotations, following the simple JPA declaration style without a JPA dependency:

```java
import cn.managame.annotation.Column;
import cn.managame.annotation.Id;
import cn.managame.annotation.Index;
import cn.managame.annotation.Table;

@Table(value = "player_item", indexes = {
    @Index(name = "uk_player_slot", columnList = "player_id, slot", unique = true),
    @Index(name = "idx_item_count", columnList = "item_id, count DESC")
})
public class PlayerItem {
    @Id public long id;
    @Column("player_id") public long playerId;
    public int slot;
    @Column("item_id") public int itemId;
    public int count;
}
```

`columnList` uses mapped RDB column names, separated by commas, with optional ASC/DESC (ASC by default). An omitted name becomes `idx_<table>_<columns>`. Unknown/repeated columns, invalid directions, and duplicate index names fail metadata initialization. Single-field `@Indexed` declarations remain supported.

The same declarations create ordered compound indexes in RDB and MongoDB; MongoDB resolves each column to its document field mapping. Legacy JSON `@CompoundIndex(def = "...")` is MongoDB-only; RDB schema initialization rejects it instead of silently ignoring it. Existing indexes are inspected and reported, never automatically replaced.

## Database adapters and schema management

RdbDataAccess accepts an application DataSource and a dialect: `MySqlDialect`, `MariaDbDialect`, or `PostgreSqlDialect`, all under `cn.managame.rdb.dialect`. MongoDB is provided by `cn.managame.docdb.mongodb.MongoDataAccess`:

```java
try (var gameData = new GameData(
        new cn.managame.docdb.mongodb.MongoDataAccess("mongodb://localhost:27017", "game"))) {
    PlayerRepository players = gameData.repository(PlayerRepository.class);
    players.get(10001L).ifPresent(player -> System.out.println(player.getLevel()));
}
```

DataAccess owns loading/querying, scanning, batch execution, and schema initialization. It does not own caches, business routing, scheduling, or cross-process locking. Custom implementations must provide `findById`, `find`, `scan`, `applyBatch`, and `ensureSchema`; `validateMapping` is the optional initialization-only validation hook; physical routing and failure classification have extension methods.

Automatic schema work is additive: create missing tables/collections, add missing RDB columns, and create indexes. It does not drop columns/indexes or perform destructive type changes. RdbSchemaReporter reports detected type, string-length, decimal integer/fractional capacity, primary-key definition, primary-ID nullability, and same-name index column/order/direction/uniqueness mismatches (direction is checked when JDBC metadata provides it). A larger decimal shape is accepted when it preserves both integer and fractional capacity. Unknown metadata values are not treated as zero capacity. DATETIME/TIMESTAMP are checked separately from DATE/TIME. Primary-key and precision differences are reported only; existing constraints or columns are never automatically rewritten. `access.schemaManager().inspect(metadata)` exposes detected issues; inspection is not a full schema migration/diff engine. MongoDB index definition conflicts are reported by the driver. Perform incompatible changes explicitly outside normal repository initialization.

## Named data sources and configuration

Business repositories bind to a named DataAccess when initialized; log repositories select physical partitions from annotated event-time fields. In this snippet, gameAccess and logAccess are preconfigured DataAccess instances:

```java
var gameData = GameData.builder()
        .dataAccess("game", gameAccess)
        .dataAccess("log", logAccess)
        .defaultDataAccess("game")
        .cacheExpireAfterAccess(java.time.Duration.ofMinutes(30))
        .writeQueueCapacity(65_536)
        .writeBatchSize(256)
        .flushInterval(java.time.Duration.ofMillis(100))
        .maxRetries(3)
        .logQueueCapacity(131_072)
        .logBatchSize(2_048)
        .logFlushInterval(java.time.Duration.ofMillis(200))
        .logWriterIdleTimeout(java.time.Duration.ofMinutes(5))
        .ensureSchema(true)
        .build();

PlayerRepository players = gameData.repository("game", PlayerRepository.class);
```

| Setting | Default | Scope |
| --- | --- | --- |
| `cacheExpireAfterAccess` | 30 minutes | Ordinary caches; resident caches never expire |
| `writeQueueCapacity` | 65,536 | Per physical ordinary-data writer |
| `writeBatchSize` | 256 | Ordinary-data batch limit |
| `flushInterval` | 100 ms | Ordinary-data batch collection interval |
| `maxRetries` | 3 | Additional retries only when classified safe |
| `logQueueCapacity` | 131,072 | Per physical log writer |
| `logBatchSize` | 2,048 | Log batch limit |
| `logWriterIdleTimeout` | 5 minutes | Idle routed log writer retirement; zero disables it |
| `logFlushInterval` | 200 ms | Log batch collection interval |
| `ensureSchema` | `true` | Repository initialization; not routed-log append |
| `failureHandler` | `WriteFailureHandler.stderr()` | Synchronous final-failure logging |

The effective batch size is capped by `DataAccess.maxBatchSize()`. A full queue can block submitters. Flush intervals are batching settings, not a maximum persistence-latency guarantee. Close GameData after stopping traffic, and manage externally owned resources separately.

## Metrics

```java
var metrics = gameData.writerMetrics("game", Player.class);
var logsMetrics = gameData.logWriterMetrics("log", PlayerActionLog.class);
var physicalWriters = gameData.physicalWriterMetrics("game");
var physicalLogs = gameData.physicalLogWriterMetrics("log");
var state = gameData.writerState("game", Player.class);
```

Metrics expose queue size, pending count, successful/failed batch counts, successful-operation count, last batch size, flush durations, and the last failure timestamp. Metrics aggregated by entity can cover multiple physical tables; use physical-writer maps for partition-level inspection. Framework-terminal errors set state to FAILED; an ordinary database batch failure does not. Monitoring integration is left to the application.

## Build, verification, and API migration

Run the module build from the repository root:

```sh
mvn -f game-data/pom.xml verify
```

Current regression tests cover double-buffer submission during blocked database writes, rejected submissions leaving cache membership unchanged, JDBC rollback/commit uncertainty, batch-count validation, multiple/composite index declarations and schema differences, shutdown during batch collection, late writer registration, full queues, interrupted/concurrent close, safe retries, failure logging, interface resolution/cache sharing, and in-place group mutations. Tests also cover partition-writer retirement/recreation under concurrent submissions and shutdown, exact numeric conversions, and binary/decimal round trips through the MongoDB driver BSON codecs with a simulated collection. These tests do not replace real-database integration testing. Running `mvn verify` at the repository root additionally builds and checks the other projects. You can select this module from the root with `mvn -pl game-data -am verify`. To run a database example in an IDE, select `cn.managame.example.MysqlExample` or `MongoExample` with the game-data test classpath; examples are compiled by verify but do not connect to a database during the tests.

When migrating from the earlier API:

- Core types now live in responsibility-specific subpackages. For example, import `SingleRepository` from `cn.managame.core.repository`, `DataAccess` from `cn.managame.core.access`, and `EntityMapper` from `cn.managame.core.mapping`. `GameData` and `DataException` remain in `cn.managame.core`; the Maven coordinate is unchanged.

- Replace Java imports under `io.gamedata` with `cn.managame`. Replace the former child-artifact dependencies with `cn.managame:game-data:0.1.0-SNAPSHOT`; add optional backend/codec dependencies as described above.
- Replace `repository(Entity.class)` and `groupRepository(Entity.class)` with business interfaces and `repository(MyRepository.class)`.
- Keep passing entity classes to flush and metrics APIs.
- Replace `writerFailure()` polling and batch-error aggregation from flush/close with WriteFailureHandler logs and failure metrics.
- Provision routed log tables before append.
- Group maps are the actual cached maps. Serialize group access and use Repository methods to persist changes.

Implementation references: [GameData](src/main/java/cn/managame/core/GameData.java), [WriteBehindEngine](src/main/java/cn/managame/core/write/WriteBehindEngine.java), [tests](src/test/java/cn/managame/core).
