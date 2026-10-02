# OGBS Data Java 25 Development Specification 1.0

**[English](OGBS-Data-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Data-Java-25-Specification-1.0.zh-CN.md)

Document type: **Java Development Specification**. Standard: [OGBS Data Specification](OGBS-Data-1.0.md).

This document defines public Java APIs, defaults, exceptions, threads/resources, extensions, and validation. Implementations satisfy both documents, including behavior, not signatures alone. Fix implementation deviations; synchronize both layers for design changes. Pending/unverified capabilities are not completed features.

Status: Java 25 reference implementation, Maven `cn.managame:game-data:1.0.0-SNAPSHOT`. Behavior is defined by the [Data Specification](OGBS-Data-1.0.md).

<a id="1-模块与公共入口"></a>

## 1. Module and public entry points

One game-data artifact contains Repository, Caffeine caching, write-back, MySQL/JDBC, MongoDB adaptation, and logs. Dependencies: game-core, with shared Caffeine supplied transitively as defined in the [Core Java specification](OGBS-Core-Java-25-Specification-1.0.md#1-模块与职责); MongoDB Sync Driver 5.5.1 is optional and must be explicitly included by Mongo applications. JDBC uses standard DataSource without selecting a pool; applications supply the MySQL JDBC Driver.

| Package | Responsibility |
| --- | --- |
| cn.managame.data | GameData, GameDataBuilder, three repositories; package-private PendingBuffer/WriteBehindManager |
| annotation / key | Identity annotations, GroupKey, MapKeys |
| meta / mapper | Storage-SPI identity metadata, VarHandle access, EntityMapper |
| codec / error | JSON/BINARY interfaces, exceptions, failure context, retries/error callbacks |
| mysql | SQL annotations, MysqlAccess, JdbcMysqlAccess, MysqlEntityMapper, log mapping |
| mongo | BSON annotations, MongoAccess, DriverMongoAccess, MongoEntityMapper |

MysqlEntityMeta, MysqlFieldMeta, MysqlSchema, MongoEntityMeta, and MongoValueCodec are package-private. MysqlLogWriter is an internal cross-package assembly bridge, not a business extension SPI. No RepositorySupport, CacheGroup, or Map view.

## 2. Repository API

Signature summary:

```java
public abstract class SingleRepository<K, E> {
    public final E get(K key);
    public final void insert(E entity);
    public final void update(E entity);
    public final void delete(K key);
}
public abstract class GroupRepository<K, E> {
    public final Map<K, E> getGroup(GroupKey key);
    public final E get(GroupKey key, K mapKey);
    public final void insert(E entity);
    public final void update(E entity);
    public final void delete(E entity);
    public final void deleteGroup(GroupKey key);
}
public abstract class LogRepository<E> {
    public final void insert(E entity);
}
```

Business repositories directly extend a concrete parameterized base and have a no-argument constructor. Intermediate generic repositories, raw types, and unresolved variables are unsupported. build creates them; retrieve with `data.repository(Players.class)`. Register only one state Repository per entity type; duplicates reject before database initialization.

Single K equals the boxed Id type. Group K is MapKey: boxed field type for one field, String for multiple fields. GroupKey object and annotation live in key/annotation packages; avoid ambiguous wildcard imports.

expireAfterAccess caches: Single uses LoadingCache<K, CacheEntity<E>>, where entity=null represents absence; Group uses LoadingCache<GroupKey, ConcurrentHashMap<K,E>>. Only get/getGroup loads; mutations use getIfPresent. No capacity-eviction setting, manual eviction API, or wrapper view.

<a id="21-repository-直接持有依赖"></a>

### 2.1 Repositories hold dependencies directly

Business repositories declare concrete K/E; base classes implement final operations. SingleRepository and GroupRepository directly hold EntityMeta, EntityMapper, WriteBehindManager, and Caffeine cache, without SingleRepositorySupport/GroupRepositorySupport delegation.

Package-private initialization:

```java
// SingleRepository / GroupRepository: internal assembly, not business API
final void initialize(EntityMeta meta, EntityMapper mapper,
                      WriteBehindManager writer, Duration expiry);

// LogRepository: internal assembly
final void initialize(MysqlLogWriter logWriter, WriteBehindManager writer);
```

Do not manually construct and inject repositories. Builder completes nonpublic initialization before admission. Extend EntityMapper for custom backends, not final CRUD methods.

<a id="22-读写路径"></a>

### 2.2 Read and mutation paths

Single.get validates initialization/running state and exact key type, then calls LoadingCache.get. Loader calls Mapper.load; null becomes CacheEntity negative cache, exceptions become DataLoadException. Neither direct null insertion into Caffeine nor exception-to-null conversion is allowed.

Group.getGroup validates component count/types and loads the entire group. Loader checks each entity's group and detects duplicate MapKey via putIfAbsent. Group.get reads that cached group without a separate single-row SELECT path.

Mutation order:

```text
Validate Repository state, arguments, and identity
→ enter writer.mutate admission read lock and recheck RUNNING
→ Group checks target is still cached
→ writer.record coalesces intent (may reject invalid sequence)
→ change cached entity/group Map
→ leave admission scope
```

Record before cache mutation so invalid merges cannot replace the cached reference. This is not a field transaction: prior direct field changes do not roll back. deleteGroup records each current member's primary-key deletion then clears the original Map; no Mapper predicate-delete operation.

<a id="3-构建与生命周期"></a>

## 3. Build and lifecycle

```java
MysqlAccess access = new JdbcMysqlAccess(dataSource);
MysqlEntityMapper mapper = new MysqlEntityMapper(access, jsonCodec, binaryCodec);
try (GameData data = GameDataBuilder.builder()
        .repositories(mapper, PlayerRepository.class, TaskRepository.class)
        .logRepositories(access, ActionLogRepository.class)
        .cacheExpire(Duration.ofMinutes(30))
        .flushInterval(Duration.ofSeconds(1))
        .batchSize(500)
        .errorHandler(failure -> archiveFailureSynchronously(failure))
        .retryPolicy(failure -> isRetryable(failure.cause()))
        .maxAttempts(3)
        .build()) {
    PlayerRepository players = data.repository(PlayerRepository.class);
}
```

Applications provide DataSource, business classes, codecs, and archive/retry functions. DataMemoryDemo is not in the repository; database-free Mapper/Repository usage is in [DataContractTest](../../game-data/src/test/java/cn/managame/data/DataContractTest.java).

| Configuration | Default | Validation/meaning |
| --- | --- | --- |
| cacheExpire | 30 minutes | Positive and greater than flushInterval; deploy well above actual save delay |
| flushInterval | 1 second | Positive monotonic target interval |
| batchSize | 500 | Positive integer |
| maxAttempts | 3 | Includes first attempt; positive; retry only when policy returns true |
| retryPolicy | Always false | One execution by default; no built-in SQLState/Mongo classification |
| errorHandler | System.Logger | Final-failure diagnostics; application archives failed data |
| partitionZone | UTC | Explicit ZoneId; configurable, e.g. Asia/Shanghai |
| logCodecs(json,binary) | Both null | Complex log-field codecs; state codecs supplied to MysqlEntityMapper |

repositories/logRepositories also accept List<Class<?>>. Validate repository generics, identity metadata, and log mapping first; state Mapper.initialize then compiles backend mappings and initializes Schema; assemble caches and start one persistence thread. Schema DDL has no aggregate rollback: earlier additions may survive later initialization failure. External resources are not taken over or closed.

GameData closes only its scheduler, not DataSource, MongoClient, or external Mapper. close may wait on database operations. Calling it from Mapper, RetryPolicy, or DataErrorHandler callbacks is synchronously rejected to avoid deadlock. All Repository access rejects after closure.

<a id="31-build-的装配阶段"></a>

### 3.1 build assembly stages

| Stage | Work | Failure state |
| --- | --- | --- |
| Configuration | Positive nanosecond-representable Duration, cacheExpire > interval, positive integers | No persistence thread |
| Repository parsing | Direct parameterized parent, concrete Entity, no-arg Repository constructor, duplicates | No partial GameData returned |
| Identity/log compilation | EntityMeta/K match; MysqlLogWriter | No persistence started |
| State Mapper.initialize | Backend mapping and Schema | Executed DDL not wholly rolled back |
| WriteBehindManager creation | Two buffers, error/retry configuration, scheduler | Internal assembly only |
| Repository initialization | Inject dependencies, create caches, register logs | No uninitialized Repository exposed |
| GameData creation | Fixed class-to-instance mapping, periodic startup | Usable entry returned |

Stage three does not validate every backend mapping; MySQL/Mongo do that in Mapper.initialize. A later mapping failure may follow an earlier successful Schema change. Correcting configuration and rebuilding does not undo prior tables/columns.

Each repositories/logRepositories call appends bindings. A Mapper may serve multiple entities; one entity cannot have two state repositories. GameData.repository(type) only looks up registered instances. After closure it still returns a reference, but Repository operations reject instead of reinitializing.

<a id="4-entity-与身份元数据"></a>

## 4. Entity and identity metadata

Inherited persistent fields are supported; duplicate identity/storage names are checked across the hierarchy. State Entity requires an accessible no-arg constructor. Loading invokes its precompiled constructor and writes fields directly, without setters. Annotations are field-only; mapped fields are nonstatic/nonfinal. Startup uses reflection; runtime uses VarHandle. JPMS packages must be opened to the implementation module.

Identity types: byte/short/int/long and wrappers, plus String; null prohibited. This restricted key domain is Java-binding-specific. Single-field order may be omitted; every multi-field order must be explicit/distinct, with Integer.MIN_VALUE indicating unspecified.

`GroupKey.of(Object...)` stores the original array and precomputes hash without defensive copy. Callers must not mutate array/components. equals preserves types: 1L differs from 1. Read-only size()/valueAt(index) support Mappers without exposing a replaceable array.

`MapKeys.of(a,b,...)` needs at least two nonnull integer/String values and rejects colons in strings. Simple joining only: no CompositeKey, escaping, or length protocol.

EntityMeta is public Mapper SPI metadata without storage-annotation semantics. EntityMapper:

```java
void initialize(EntityMeta meta);
Object load(EntityMeta meta, Object id);
List<?> loadGroup(EntityMeta meta, GroupKey key);
void insertBatch(EntityMeta meta, List<?> entities);
void updateBatch(EntityMeta meta, List<?> entities);
void deleteBatch(EntityMeta meta, List<Object> ids);
void deleteInsertBatch(EntityMeta meta, List<?> entities);
```

<a id="41-身份编译与存储编译的分工"></a>

### 4.1 Identity compilation versus storage compilation

EntityMeta owns Single/Group identity, field access, and key types, not MySQL types or Mongo indexes. Identity-valid entities must also pass backend validation. For example, @Id without @Column is recognized as identity but rejected by MySQL mapping.

Multi-field order must be explicit/unique, not contiguous 0,1,2. GroupKey.of(42L) differs from GroupKey.of(42); use matching boxed field types. Composite MapKeys must use the declared order without independent sorting.

Mapper.loadGroup returns a nonnull list, empty for no rows. Repository defensively checks identity/MapKey but is not a complete validator for arbitrary third-party Mappers. Custom implementations must satisfy entity type, complete-group loading, batch, and exception contracts.

<a id="5-写回实现与适用边界"></a>

## 5. Write-back implementation and limits

Exactly two PendingBuffers precreate EntityMeta → ConcurrentHashMap<PrimaryId, PendingChange>. PendingChange stores operation and entity reference; DELETE stores no entity. Each record rereads volatile activeBuffer and coalesces through compute, never caching a buffer reference.

The sole persistence thread swaps A/B, waits a fixed 100ms grace, processes every old-buffer entity batch, reports final failures, then clears it. No cross-buffer merge. Next delay is max(0, interval - previous round duration), with no catch-up backlog of missed periods.

**The fixed 100ms grace simplifies buffer switching; it is not a strict concurrency barrier.** A producer paused after obtaining the old buffer for longer than grace may miss traversal or race clear. There is no writer counter, sealed buffer, epoch, or third buffer, and no arbitrary-pause losslessness guarantee. record must remain short; applications serialize business entity access.

Admission uses a shared read lock; close takes the write lock to close admission. This protects admitted-operation/shutdown races, not buffer switching or serialization of unrelated entities. close awaits the pipeline and handles both buffers; any remembered final failure throws DataSaveException, regardless of later successes.

Caches, log queues, and pending sets have no capacity limit. Sustained production above persistence rate grows memory. Capacity backpressure and production-capacity validation are unimplemented.

<a id="51-pendingbuffer-的数据形状"></a>

### 5.1 PendingBuffer shape

```text
WriteBehindManager
  activeBuffer ──→ A or B (volatile)
  A: EntityMeta → ConcurrentHashMap<PrimaryId, PendingChange>
  B: EntityMeta → ConcurrentHashMap<PrimaryId, PendingChange>

PendingChange
  operation
  entityReference (null for DELETE)
```

Outer keys are EntityMeta, not EntityMapper. Player and guild may share a MysqlEntityMapper and both have id=42; they need separate pending maps for separate tables. Every record rereads activeBuffer; compute coalesces each key without per-update deep copies.

<a id="52-一轮-flush-的时间线"></a>

### 5.2 One flush timeline

```text
Record monotonic round start
→ swap A/B; later producers use new active
→ wait fixed 100ms
→ traverse old buffer; split each EntityMeta into operation-phase batches
→ execute/retry by policy/report final error for each batch
→ clear processed old buffer
→ bounded log drain for this round
→ schedule after max(0, interval - elapsed)
```

Pipeline exclusion prevents periodic and close-driven Mapper batches from running concurrently. Entity traversal is not business transaction order; ConcurrentHashMap key traversal is not update call order. Multi-entity ordering requirements cannot rely on traversal as a serial transaction.

The grace failure example must remain documented: a thread takes old A and pauses; persistence switches to B, waits 100ms, traverses/clears A; the thread then writes A. Its change may miss the round or be erased by racing clear. Without strict completion handshake, arbitrary-pause safety is not claimed. A future counter/sealing/epoch protocol must update mechanics, performance tradeoffs, and concurrency tests, not merely remove this limitation.

<a id="53-批次错误与尝试次数"></a>

### 5.3 Batch errors and attempts

Semantic pseudocode, not an API:

```text
attempt = 1
execute batch
on failure:
    create DataFailure with current attempt
    if attempt < maxAttempts and RetryPolicy permits:
        increment attempt and immediately retry same batch
    else:
        remember lifetime final failure
        synchronously invoke DataErrorHandler
        continue next batch
```

No backoff, jitter, or separate retry thread. RetryPolicy/DataErrorHandler run synchronously in the persistence pipeline and extend round duration. Policy failure adds diagnostics and becomes final failure; Handler failure logs and continues.

Callbacks cannot structurally modify DataFailure.batch, but elements remain entity references, not immutable snapshots. DELETE batches contain IDs; writes/logs contain objects. Construct failure context only on actual failure; successful paths create no archive copies.

<a id="54-close-的内部屏障"></a>

### 5.4 Internal close barrier

Under admission write lock, close enters CLOSING and blocks new mutate, stops future scheduling, awaits the active pipeline, processes older inactive buffer, active buffer, and all admitted logs, then enters CLOSED. Remembered runtime final failures cause DataSaveException, also on repeated close.

Mapper/policy/error callbacks on the persistence thread cannot close and wait for themselves; the framework detects/rejects this. A driver operation that never returns can keep close waiting indefinitely; applications configure database timeouts.

<a id="6-mysql-映射"></a>

## 6. MySQL mapping

```java
@Table(name = "player_task", indexes = {
    @Index(name = "idx_role_task", columnList = "role_id, task_id")
})
public class Task {
    @Id @Column private long id;
    @cn.managame.data.annotation.GroupKey @Column private long roleId;
    @MapKey @Column private long taskId;
    @Column private int progress;
}
```

Table.name is required. Only @Column persists; missing it on identity fields fails initialization. Default names convert camelCase to snake_case. Table/column/index names use ASCII letters/digits/underscore starting with letter/underscore, at most 64 MySQL characters. Long generated indexes are truncated with a stable hash. Quote identifiers with backticks and bind values.

| Java DEFAULT type | MySQL | Automatic DEFAULT |
| --- | --- | --- |
| byte / short / int / long | TINYINT / SMALLINT / INT / BIGINT | 0 |
| float / double / boolean | FLOAT / DOUBLE / TINYINT | 0 |
| char / String | CHAR(1) / VARCHAR(255) | '' |
| byte[] | BLOB | None |

Wrappers map identically. Primitives/identity columns are NOT NULL; others nullable. SQL NULL into primitive is a load error. TEXT accepts only String. JSON String is raw JSON; other types require JsonCodec. BINARY byte[] passes through; other types require BinaryCodec. Null bypasses codecs. DEFAULT rejects complex objects.

`Column.defaultValue` is a trusted raw SQL DDL expression without automatic quoting. JSON/BINARY/TEXT infer no defaults. JsonCodec/BinaryCodec.decode receives java.lang.reflect.Type and returns Object, preserving generics such as List<Item>.

Initialization compiles conversions, SELECT/INSERT/UPDATE/DELETE SQL, and parameter order. UPDATE excludes Id/GroupKey/MapKey; identity-only entities update as no-op. DELETE_INSERT encodes first, then deletes/inserts the batch in an explicit transaction.

Schema queries information_schema and only adds missing structures. Existing columns get basic type checks, not full VARCHAR-length compatibility or default/nullable migration. Check single-column primary key and explicit index order/uniqueness; never infer GroupKey indexes. Deployment orchestration serializes schema migration.

MysqlAccess provides queryOne/query/update/batchUpdate/transaction. queryOne returns null for zero rows and throws MysqlException for multiple rows; query returns an empty List. Write-back splits batches; Access does not split again. Transaction callback receives one-connection MysqlTransaction, commits success, rolls back exceptions, restores autoCommit, and closes; valid only on callback thread/scope.

JdbcMysqlAccess obtains/releases a DataSource connection per operation. Ordinary operations follow DataSource autoCommit, normally configured true. MysqlException retains SQL, SQLException cause, sqlState()/vendorCode(). No low-level automatic retry or business database-error mapping.

<a id="61-预编译和运行期工作"></a>

### 6.1 Precompilation and runtime work

Initialization fixes column order, VarHandles, value encoders, SQL templates, and indexes. Runtime reads/binds in that order without rescanning annotations or building business predicates.

| Operation | Constraint |
| --- | --- |
| load | Unique primary-key query, construct Entity, write fields directly |
| loadGroup | All group-key predicates, full group |
| INSERT | All explicitly persistent columns; no preflight SELECT |
| UPDATE | Locate by primary key, update nonidentity columns; no empty UPDATE |
| DELETE | Primary key only, no entity read |
| DELETE_INSERT | Encode current batch, then delete/insert in one small transaction |

JdbcMysqlAccess is the database boundary, not coalescing/retry policy. Do not retain MysqlTransaction for asynchronous work; it is thread/scope-bound. Transactions cover their callback, not all flush work or multiple repositories automatically.

Schema maintenance conservatively adds missing tables/columns/explicit indexes and rejects obvious conflicts. Deployment tools handle unsupported migrations. Successful initialization does not prove existing VARCHAR lengths/defaults/nullable exactly match Java declarations.

<a id="7-mongodb-映射"></a>

## 7. MongoDB mapping

Collection.name is required; only @Field persists. Default names use snake_case. Id is fixed _id; other names fail initialization. Only simple ASCII storage names. MongoIndex.fields uses database names, all ascending; no TTL/text/geo/partial indexes.

MongoEntityMapper accepts MongoDatabase or MongoAccess. DriverMongoAccess uses its CodecRegistry directly, without JSON conversion. Supports scalars, byte[], List/Set/Collection, and Map<String/Integer/Long,V>, compiling nested values by Type. Applications configure codecs for concrete complex classes on MongoDatabase, e.g. POJO/custom Codec. Other parameterized custom classes explicitly fail initialization.

Load uses no-arg construction + VarHandle. Missing fields retain constructor defaults; explicit BSON null cannot populate primitives. UPDATE uses ordered bulk replacement with upsert=false; DELETE_INSERT upsert=true; DELETE uses _id $in. Driver batches may partially succeed, affecting retry policy.

<a id="71-与-mysql-的相同点和差异"></a>

### 7.1 Similarities and differences from MySQL

Both Mappers receive changes by EntityMeta/primary key and use unified failure handling, without pretending their database operations are identical:

| Intent | MySQL | MongoDB |
| --- | --- | --- |
| UPDATE | All nonidentity columns | Complete Document replacement, upsert=false |
| DELETE_INSERT | Small delete/insert transaction | Replacement, upsert=true |
| Type extensions | JsonCodec / BinaryCodec | MongoDatabase CodecRegistry |
| Schema initialization | Tables, columns, explicit indexes | Collections, explicit ascending indexes |

Mongo replacement is not field-level $set; unmapped fields added externally are not preserved by contract. Ordered bulk is not a batch transaction: earlier operations may have succeeded and retries must handle that.

## 8. Log

LogRepository requires neither Id nor no-arg constructor and does not call EntityMapper. @Table/@Column compiles INSERT mapping without querying/changing Schema; external services prepare tables.

At most one @PartitionKey, optionally nonpersistent. VALUE supports integers/String with ASCII alphanumeric/underscore suffixes. DAY/MONTH/YEAR requires long/Long Unix epoch milliseconds, rendered in partitionZone as uuuuMMdd/uuuuMM/uuuu. Without partitioning, use the base table.

insert validates type/partition then enqueues in ConcurrentLinkedQueue without snapshots. Persistence drains batches, groups by physical table, and uses the same error/retry path. Do not mutate enqueued logs.

<a id="81-drain-与分表的运行边界"></a>

### 8.1 Drain and partition boundaries

Each drain first observes a finite queue-count boundary, then polls by batchSize. Concurrent production can affect that observation, but the round never loops forever to catch up. Group each drained batch by physical table and execute each group through the same execute/error/retry path.

A batch containing yesterday's and today's events yields two independent table INSERTs, not a cross-table transaction. close stops insertion then drains all remaining logs; bounded periodic work does not limit shutdown to one batch.

Partition values are validated at admission but Java objects are not frozen. Later mutation may fail or misroute persistence, hence the prohibition on changing submitted logs.

<a id="82-扩展实现时需要保留的边界"></a>

### 8.2 Boundaries for extensions

Custom EntityMapper distinguishes EntityMeta, never turns load failure into absence, defines partial-success/DELETE_INSERT semantics, and preserves causes. New backends update observable standard differences and Java mapping rules together.

New codecs must not silently turn DEFAULT complex objects into JSON or discard generic Type information. New log backends, log Schema maintenance, capacity settings, failed-batch reinsertion, and strict buffer exchange are unimplemented extensions; a configuration example cannot claim support.

<a id="9-异常与验证"></a>

## 9. Exceptions and validation

DataLoadException: storage load/invalid loaded results. DataOperationException: uninitialized/closed/unloaded group/illegal sequence. DataSaveException: final save failure discovered by close. Startup configuration/metadata errors generally use IllegalArgumentException; Schema/database errors retain underlying exceptions. Shared codes: [Core](OGBS-Core-1.0.md#data-constants).

Verified: Repository/buffer contracts, real JDBC queries/transactions on H2 MySQL mode, SQL mapping/Schema generation, BSON CodecRegistry encoding/decoding, Mapper invocation contracts. H2 Schema tests use an information-table adaptation stub, not real MySQL validation. Mongo tests use recording MongoAccess, not server validation.

[DatabaseIntegrationTest](../../game-data/src/test/java/cn/managame/data/DatabaseIntegrationTest.java) enables real services through OGBS_DATA_MYSQL_URL / OGBS_DATA_MONGO_URI; skipped by default. Without these variables, real-service tests do not run. Actual MySQL/MongoDB servers, production performance, and prolonged recovery remain unverified.

<a id="91-错误扩展接口"></a>

### 9.1 Error extension interfaces

```java
public record DataFailure(
    int errorCode, Class<?> entityType, DataOperation operation,
    List<?> batch, Throwable cause, int attempt) {}

public interface RetryPolicy {
    boolean shouldRetry(DataFailure failure);
}
public interface DataErrorHandler {
    void onError(DataFailure failure);
}
```

DataOperation includes INSERT, UPDATE, DELETE, DELETE_INSERT, LOG_INSERT. It is actual persistence intent, not necessarily the last Repository method: DELETE then INSERT reports DELETE_INSERT. attempt starts at 1; successful retry does not invoke the final error Handler.

Retain underlying causes. Policies may inspect MysqlException sqlState/vendorCode or Mongo exceptions; no built-in retryable list. Core alone allocates error numbers.

<a id="92-易错契约的测试定位"></a>

### 9.2 Tests for error-prone contracts

| Contract | Test class and method |
| --- | --- |
| No preflight SELECT for insert/delete; no negative caching of failure | DataContractTest.negativeCacheDeleteAndInsertNeverSelect / loadFailuresAreNotNegativeCached |
| Explicit group loading and original Map | DataContractTest.groupsRequireExplicitLoadAndReturnSameMap |
| Group entity deletion by database primary key | DataContractTest.loadedGroupDeletesByDatabaseId |
| Legal/illegal merge matrix | DataContractTest.legalMergeTable / illegalMergeTable |
| Illegal INSERT preserves cached object | DataContractTest.illegalInsertDoesNotReplaceCachedEntity |
| Phase order, batch splitting, no cross-buffer merge | DataContractTest.batchesOrderedAndSplitAndNeverMergeAcrossBuffers |
| Opt-in bounded retry, continue after failure | DataContractTest.retryIsOptInBoundedAndKeepsBatchContext / finalFailureAndHandlerFailureDoNotStopLaterBatchesAndCloseReportsIt |
| Closure awaits active pipeline and next buffer | DataContractTest.closeWaitsForActivePipelineAndFlushesNextBuffer |
| Callback closure rejected without deadlock | DataContractTest.closeFromCallbackIsRejectedWithoutDeadlock |
| Log partition/drain without Schema initialization | LogContractTest.closeDrainsPartitionsAndBatchesWithoutSchemaInitialization |
| Same log error Handler, continue later partitions | LogContractTest.logFailuresReachSameHandlerAndDoNotBlockLaterPartitions |

These tests do not prove arbitrary-pause safety of 100ms grace or replace real database failure/compatibility tests. New guarantees require implementation and verification, not broader claims about existing tests.
