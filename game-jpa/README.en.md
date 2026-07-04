[中文](README.md) | English

# game-jpa

## Build & Verify

`game-jpa` uses Java 25, consistent with the parent project. The default test command needs no Docker:

```powershell
mvn "-Dmaven.repo.local=..\.m2" test
```

Real MySQL and MongoDB integration tests live under `src/it/java` and run only with the `integration-tests` profile:

```powershell
mvn "-Dmaven.repo.local=..\.m2" -Pintegration-tests verify
```

`game-jpa` is a data access framework for game servers. It provides a lightweight Repository API, entity metadata resolution, relational/document database adapters, local caching, asynchronous batched write-back, shard routing, lifecycle hooks and metrics collection.

It is not a full JPA/Hibernate replacement and does not pursue heavyweight ORM features. It fits the data models common to game servers:

- High-frequency read/write entities: players, roles, inventories, mail, activity data
- Game logic mutates in-memory state first, then merges into batched database writes
- Sharding by player id, role id, date, realm and similar dimensions
- A persistence API that is explicit, controllable and low-magic

## Modules

| Module | Description |
| --- | --- |
| `game-jpa-core` | Core SPI, metadata, Repository factory, routing, lifecycle, metrics (with default in-memory implementation), write task contracts |
| `game-jpa-starter` | Bootstrap and runtime context |
| `game-jpa-rdb` | Relational database annotations, metadata, query specs, Repository API |
| `game-jpa-rdb-mysql` | MySQL executor, SQL dialect, Hikari data source factory, schema generator |
| `game-jpa-docdb` | Document database annotations, metadata, query/update specs, Repository API |
| `game-jpa-docdb-mongo` | MongoDB executor |
| `game-jpa-cache` | Storage-agnostic unique-key and composite-key caches |
| `game-jpa-rdb-cache` | RDB cache Repository and batched write-back |
| `game-jpa-docdb-cache` | DocDB cache Repository and batched write-back |
| `game-jpa-async-write` | In-memory async write queue and flush scheduler |

## Quick Start: RDB + MySQL + Cache Write-back

Add dependencies:

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-jpa-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-jpa-rdb-cache</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-jpa-rdb-mysql</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

`game-jpa-rdb-mysql` brings in HikariCP and the MySQL JDBC driver; business projects usually don't need to declare `mysql-connector-j` separately.

Define an entity:

```java
import cn.managame.jpa.rdb.annotation.Column;
import cn.managame.jpa.rdb.annotation.ColumnType;
import cn.managame.jpa.rdb.annotation.Entity;
import cn.managame.jpa.rdb.annotation.Id;
import cn.managame.jpa.rdb.annotation.ShardKey;
import cn.managame.jpa.rdb.annotation.Table;
import cn.managame.jpa.rdb.annotation.Version;

import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "player")
public class Player {
    // A field maps to a column only with an explicit @Column; markers like @Id/@ShardKey/@Version
    // must be declared together with @Column
    @Id
    @ShardKey
    @Column
    private long id;

    @Column
    private long roleId;

    @Column(length = 64, defaultValue = "newbie")
    private String name;

    // Complex types are stored as JSON columns by default; the type is declared explicitly here
    // to also provide a JSON default value
    @Column(type = ColumnType.JSON, defaultValue = "{}")
    private Map<String, Integer> bag = new LinkedHashMap<>();

    @Version
    @Column
    private long version;
}
```

Define a Repository:

```java
import cn.managame.jpa.rdb.cache.IRdbUniqueCacheRepository;

public interface PlayerRepository extends IRdbUniqueCacheRepository<Player, Long> {
}
```

Bootstrap the framework:

```java
import cn.managame.jpa.rdb.cache.RdbCacheModule;
import cn.managame.jpa.rdb.mysql.MysqlDataSourceFactory;
import cn.managame.jpa.rdb.mysql.MysqlRdbExecutor;
import cn.managame.jpa.rdb.mysql.MysqlSchemaModule;
import cn.managame.jpa.starter.GameJpaBootstrap;
import cn.managame.jpa.starter.GameJpaContext;

import javax.sql.DataSource;
import java.util.List;

DataSource dataSource = MysqlDataSourceFactory.builder()
        .jdbcUrl("jdbc:mysql://localhost:3306/game")
        .username("root")
        .password("123456")
        .maximumPoolSize(16)
        .build();

GameJpaContext context = new GameJpaBootstrap()
        .install(MysqlSchemaModule.update(dataSource))
        .install(RdbCacheModule.withExecutor(new MysqlRdbExecutor(dataSource)))
        .flushIntervalMillis(5000)
        .flushThreadMode(FlushThreadMode.VIRTUAL) // flush worker thread model, virtual threads by default; with PLATFORM use flushThreadCount
        .flushThreadCount(2)
        .maxFlushBatchSize(500)
        .maxRetries(3)
        .maxPendingWriteTasks(1_000_000)
        .bootstrap(List.of(Player.class));

PlayerRepository players = context.getRepository(PlayerRepository.class);
```

Read/write through the cache:

```java
Player player = players.cacheLoad(10001L);
// mutate the player's in-memory state
players.cacheUpdate(player);

// async write-back routes automatically from the entity's @ShardKey
players.cacheDelete(10001L);
```

The context must be closed at server shutdown:

```java
context.close();
```

`close()` first rejects new async write submissions, then stops the flush scheduler, and performs one final flush of the tasks already in the in-memory queue.
The built-in async queue no longer offers local file persistence; a process crash may lose in-memory tasks. Data that must never be lost should use the synchronous Repository writes or an external durable queue/pipeline.

## Common Integration Patterns

### Plain RDB

Use `RdbModule.withExecutor(new MysqlRdbExecutor(dataSource))` and have business Repositories extend `RdbRepository<T, ID>`.

Suits strongly consistent write paths, back-office administration, and low-frequency entities.

### RDB Cache Write-back

Use `RdbCacheModule.withExecutor(...)` and have business Repositories extend:

- `IRdbUniqueCacheRepository<T, ID>`: unique cache by primary key
- `IRdbMultiCacheRepository<T, ID>`: multi-record cache by composite key

For a small table of config/dictionary-style data that should be fully loaded at startup and kept resident in memory, mark the entity class with `@Warmup` and have the business Repository extend `IRdbUniqueCacheRepository<T, ID>`:

```java
import cn.managame.jpa.cache.annotation.Warmup;

@Warmup
@Entity
@Table(name = "item_config")
public class ItemConfig {
    @Id
    private long id;
}
```

With `RdbCacheModule` installed, `GameJpaBootstrap.bootstrap(...)` automatically calls `findAll()` to warm such entities after the context is created; subsequent `cacheLoad(id)` calls reuse the same in-memory cache, which has no expiry and no capacity eviction.
This capability is only for non-sharded tables of controlled size.

In the character-creation flow, if the business can tell that a role was just created and its tables definitely have no history in the database, that judgment can be handed to the cache configuration:

```java
RdbCacheModule cacheModule = RdbCacheModule.withExecutor(executor)
        .newRoleDetector(roleId -> roleService.isNewlyCreated(roleId), Duration.ofMinutes(10))
        .defaultConfig(CacheConfig.defaults());

GameJpaContext context = new GameJpaBootstrap()
        .install(cacheModule)
        .bootstrap(entityClasses);
```

Role-related tables must mark the roleId field explicitly, so the framework never guesses by field name or key order:

```java
import cn.managame.jpa.cache.annotation.CacheKey;
import cn.managame.jpa.core.annotation.RoleId;
import cn.managame.jpa.rdb.annotation.ShardKey;

@Entity
@Table(name = "mail")
public class Mail {
    @Id
    private long id;

    @RoleId
    @ShardKey
    @CacheKey(order = 0)
    private long roleId;
}
```

`newRoleDetector` is a global setting, not bound to a single table's `CacheConfig`. Business usage is unchanged — still `cacheLoad(...)`. On a cache miss, the framework first extracts the roleId via `@RoleId` and briefly caches the new-role verdict; when judged new, unique keys return `null` and composite keys return an empty list, without hitting the database loader. RDB composite keys take the value from the part corresponding to the `@CacheKey` field marked `@RoleId`; unique keys can only be judged before querying when `@RoleId` is also `@Id`, or when the routingKey of `cacheLoad(id, routingKey)` corresponds to a `@RoleId @ShardKey` field.

Suits hot data in game servers. Note that it is eventually consistent write-back — a process crash may lose in-memory tasks not yet flushed.

### Mongo DocDB

Use `DocdbModule` or `DocdbCacheModule` with the `MongoDocExecutor` executor.

Suits flexible, document-shaped data. Cache write-back matches the RDB-side capabilities:

- `IDocUniqueCacheRepository<T, ID>`: unique cache by primary key
- `IDocMultiCacheRepository<T, ID>`: multi-record cache by composite key (entity fields marked `@CacheKey(order = N)`)
- `@Warmup` document entities are auto-warmed with `findAll()` at startup and stay memory-resident (non-sharded small collections only)

Nested POJO fields map to nested documents automatically, no annotations needed; types with a registered custom `TypeConverter` go through the converter first.

`MongoDocExecutor` translates driver exceptions into framework exceptions: transient errors such as network disconnects / primary elections / timeouts are wrapped as `RetriableWriteException` (async write-back retries up to `maxRetries`), duplicate keys as `DuplicateKeyException`, and the remaining deterministic failures are dropped after notifying the permanent failure handler — the same failure triage as the MySQL executor.

## Sharding

Built-in strategies:

- `HashShardingStrategy`
- `RangeShardingStrategy`
- `DateShardingStrategy`
- `ConsistentHashShardingStrategy`

An entity may mark at most one `@ShardKey`. Writes, batch writes and cache write-back route automatically from the entity's `@ShardKey` field; conditional queries also route automatically when the `RdbQuerySpec` / `DocQuerySpec` contains a single-value `@ShardKey` condition. Only when querying/deleting by id while `@ShardKey` is not the primary key can the framework not derive the shard from the id — an explicit routingKey is needed:

```java
repo.findById(id, routingKey);
repo.deleteById(id, routingKey);
cacheRepo.cacheLoad(id, routingKey);
cacheRepo.cacheDelete(id, routingKey);
```

If you want the unique cache's `cacheLoad(id)` to take no routing parameter at all, the simplest modeling is to put `@ShardKey` and `@Id` on the same field. `cacheInsert(entity)`, `cacheUpdate(entity)` and composite-key cache write-back all read the routing value from the annotated entity field automatically.

RDB conditional queries and counting use `RdbQuerySpec`, with field names being Java property names:

```java
repo.findBySpec(new RdbQuerySpec().eq("roleId", roleId).limit(100));
long count = repo.count(new RdbQuerySpec().eq("roleId", roleId).gte("level", 10));
```

## Production Notes

- Do not use `findAll()` or `warmUpAll()` on the hot path of large tables.
- `@Warmup` fully loads the whole table at startup and keeps it memory-resident — use only for small config/dictionary tables; sharded tables with a `RoutingStrategy` do not support automatic full warmup.
- `newRoleDetector` applies only to paths where the business knows for sure the backend has no data, such as new-role initialization; role-related tables should be annotated with `@RoleId`. Do not classify an ordinary login as a new role, or real database data may be treated as nonexistent.
- Cache write-back is eventually consistent; it is not a strongly consistent path where a response requires a confirmed database write.
- Cache write-back keeps references to entity objects to avoid snapshot overhead; do not keep mutating the same entity object concurrently after submitting.
- RDB cache write-back performs version-aware upserts for MySQL entities with `@Version`; a version mismatch is treated as a deterministic failure — dropped after notifying the permanent failure handler.
- Watch the `asyncWrite.pending` metric; `maxPendingWriteTasks` is the submission-phase backpressure threshold — beyond it, new unique-key write tasks are rejected, while updates to the same key still merge.
- Configure the async write permanent failure handler for alerting or compensation.
- Service shutdown must call `GameJpaContext.close()`.
- Shard routing for cache write-back is extracted automatically by the execution layer from the entity's `@ShardKey`; `cacheLoad(id)` / `cacheDelete(id)` can only route automatically when the entity is already cached or `@ShardKey` is the primary key — otherwise use the storage-specific Repository's `cacheLoad(id, routingKey)` / `cacheDelete(id, routingKey)`.
- The query API uses Java property names, not raw SQL column names.
- To auto-patch the schema at startup in production, install `MysqlSchemaModule.update(dataSource)` explicitly; it only creates missing tables and adds missing columns/indexes — it never drops columns, renames columns, or alters existing column types/defaults. Before going live, `MysqlSchemaGenerator.Mode.GENERATE_ONLY` can produce a diff report; items commented `-- MANUAL MIGRATION REQUIRED` should go into a controlled migration.
- RDB entities with `@ShardKey` and an installed `RoutingStrategy` do not support automatic schema sync; DDL for sharded physical tables / multiple data sources should use controlled migrations or pre-generated scripts.
- `@Column(defaultValue = "...")` only applies to `null`; fields declaring a default should use wrapper types such as `Integer`, `Boolean` — not `int`, `boolean`.

## Testing

Run all `game-jpa` module tests:

```bash
mvn "-Dmaven.repo.local=..\\.m2" test
```

Current test coverage:

- Sharding strategies
- RepositoryFactory priority
- Multi-level generic Repository type resolution
- Async write queue: submission-phase routing, per-physical-table merge/append and concurrent drain
- Composite-key cache consistency
- Sharded submission-phase routing (RDB/DocDB) and async log appending

## Extending

The general steps for a new storage backend:

1. Define annotations and a metadata resolver, or reuse the existing metadata model.
2. Implement the storage executor.
3. Implement a `RepositoryFactory`.
4. Implement a `PersistenceModule` registering the resolver, factory and executor.
5. For cache write-back, register the corresponding `WriteChannel` with `WriteChannelRegistry` (entity caches use the `BatchFlusher` merge channel, logs use the `AppendFlusher` append channel).
6. Add tests for metadata, routing, Repository creation and failure behavior.

Adding a cache implementation:

1. Implement `CacheStore<K, V>`.
2. Implement a `CacheStoreFactory`.
3. Inject via `CacheConfig.builder().cacheStoreFactory(...)`.
