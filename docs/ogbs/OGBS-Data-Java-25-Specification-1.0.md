# OGBS Data Java 25 Development Specification 1.0

**[English](OGBS-Data-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Data-Java-25-Specification-1.0.zh-CN.md)

Document type: **Java Development Specification**. Standard: [OGBS Data Specification](OGBS-Data-1.0.md), which defines the behavior; this document defines the Java API, defaults, exceptions, threads and validation.

Status: Java 25 reference implementation, Maven `cn.managame:game-data:1.0.0-SNAPSHOT`.

<a id="1-模块与公共入口"></a>

## 1. Module and public entry points

One artifact contains repositories, caching, write-back, MySQL/JDBC, MongoDB and logs. Dependencies: game-core and Jackson Databind 2.21.3 (default JSON). The MongoDB Sync Driver 5.5.1 is optional; Mongo applications add it. JDBC uses an application-provided DataSource and MySQL driver; Data selects no pool. Saves run as JDBC batches, which MySQL Connector/J only sends combined with the connection property `rewriteBatchedStatements=true` ([game-demo's MysqlConfig](../../game-demo/src/main/java/cn/managame/demo/common/mysql/MysqlConfig.java) sets it); otherwise every row costs a round trip.

| Package | Responsibility |
| --- | --- |
| cn.managame.data | GameData, GameDataBuilder, the three repository bases; package-private EntityCache, PendingBuffer, WriteBehindManager |
| annotation / key | Identity annotations, GroupKey, MapKeys |
| meta / mapper | EntityMeta (identity metadata for storage SPIs), EntityMapper |
| codec / error | JSON/BINARY codecs, exceptions, DataFailure, RetryPolicy, DataErrorHandler |
| mysql | SQL annotations, MysqlAccess, JdbcMysqlAccess, MysqlEntityMapper, log mapping |
| mongo | BSON annotations, MongoAccess, DriverMongoAccess, MongoEntityMapper |

<a id="2-repository-api"></a>

## 2. Repository API

```java
public abstract class SingleRepository<K, E> {
    public final E get(K key);
    public final E getOrCreate(K key, Function<? super K, ? extends E> factory);
    public final void insert(E entity);
    public final void update(E entity);
    public final void delete(K key);
}
public abstract class GroupRepository<K, E> {
    public final Map<K, E> getGroup(GroupKey key);   // read-only live view
    public final E get(GroupKey key, K mapKey);
    public final void insert(E entity);
    public final void update(E entity);
    public final void delete(E entity);
    public final void deleteGroup(GroupKey key);
}
public abstract class LogRepository<E> {
    public final boolean insert(E entity);   // false: log queue full, reported to DataErrorHandler
}
```

A business repository directly extends one concrete parameterized base and has a no-argument constructor; intermediate generic repositories are unsupported. `build` creates it; obtain it with `data.repository(Players.class)`. One state repository per entity type. Single K is the boxed Id type; Group K is the boxed MapKey type, or String for a composite MapKey. Repositories are initialized only by the builder; extend EntityMapper for a custom backend.

<a id="21-repository-直接持有依赖"></a>
<a id="22-读写路径"></a>

### 2.1 Cache and mutation paths

Each repository holds one EntityCache: Single caches entity or confirmed absence (null) by id; Group caches a `ConcurrentHashMap<K,E>` by GroupKey and getGroup returns a read-only live view of it (created once per load, so the same object for as long as the group stays cached); business code changes a group only through insert/update/delete, and a direct put/remove throws UnsupportedOperationException. Single reads (get/getOrCreate) and every Group read or write load a missing key; Single writes never load. Loads run synchronously on the calling thread; a missing key loads once while concurrent callers of that key wait. The load runs outside the cache map's locks, so other keys are never blocked by it; a mutation of the same key during the load waits for it and then applies to its result; if the key is evicted while loading, the result is discarded and loaded again, so nothing older than memory is ever installed. Reading the same key again inside its loader throws IllegalStateException. A load failure throws DataLoadException and is not cached as absence.

Data has no asynchronous reads. On the default virtual-thread Route executor a synchronous load only parks the current Route's virtual thread; later messages of that Route have to wait for it anyway, so ordering is unchanged and business code needs no callbacks. With a platform-thread Route executor, preload a player's data when the player enters its Route so in-game reads hit the cache.

Writes are checked as soon as the key is cached: Single insert of an existing entity and update of a key cached as absent throw DataOperationException, and delete of an absent key does nothing; a key that is not cached is recorded as is, without reading storage to check it. Group writes load the group first and are therefore always checked: insert of an existing MapKey, update of a missing one, or update with an entity of another @Id throws; delete removes whatever entity holds the MapKey and does nothing when it is missing. `getOrCreate` records the factory's entity as an INSERT and caches it when none exists; the factory runs outside any lock, must return an entity whose @Id equals the key, and under contention the first recorder wins while the other caller gets the cached entity.

The save thread reads entity fields while encoding (D-OWN-01). Collection fields that business code changes on its Route while the save thread may iterate them should be thread-safe collections (for example ConcurrentHashMap, CopyOnWriteArrayList, ConcurrentLinkedDeque), and scalar fields that need cross-thread visibility volatile or atomic; otherwise encoding may hit ConcurrentModificationException or observe a half-updated state. The framework takes no snapshot, by design, for performance.

A mutation validates state and identity, passes the admission count (one CAS, no read-write lock), and then runs inside `EntityCache.update` for its key: the existence checks above run, `writer.record` coalesces the intent (an illegal sequence throws and leaves the cache unchanged), then the cache entry or group Map changes. Running the record inside the cache's per-key atomic update is what makes eviction safe (section 5.1). Recording is not a field transaction: fields changed directly before the call are not rolled back. deleteGroup records a primary-key DELETE for each current member, then clears the original Map.

Spring integration is application code: the [game-demo adapter](../../game-demo/src/main/java/cn/managame/demo/common/data/GameDataConfig.java) registers scanned singleton `@Repository` beans with the builder and supplies `data.repository(type)` as the bean instance ([DataRepositoryRegistrationTest](../../game-demo/src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java), [DataSpringWiringTest](../../game-demo/src/test/java/cn/managame/demo/DataSpringWiringTest.java)).

<a id="3-构建与生命周期"></a>

## 3. Build and lifecycle

```java
try (GameData data = GameDataBuilder.builder()
        .mysql(dataSource)
        .repositories(PlayerRepository.class, TaskRepository.class)
        .logRepositories(ActionLogRepository.class)
        .cacheExpire(Duration.ofMinutes(30))
        .flushInterval(Duration.ofSeconds(1))
        .batchSize(500)
        .errorHandler(failure -> archiveFailureSynchronously(failure))
        .retryPolicy(RetryPolicy.transientFailures())
        .shutdownTimeout(Duration.ofSeconds(30))
        .build()) {
    PlayerRepository players = data.repository(PlayerRepository.class);
}
```

| Configuration | Default | Meaning |
| --- | --- | --- |
| cacheExpire | 30 min | Idle time before a clean entry is evicted; must exceed flushInterval |
| flushInterval | 1 s | Target interval between persistence passes |
| batchSize | 500 | Rows per batch |
| shutdownTimeout | 30 s | How long close keeps retrying retryable failures before dropping and reporting them |
| retryPolicy | RetryPolicy.transientFailures() | true keeps a failed change for the next pass; false splits, drops and reports (section 5) |
| errorHandler | System.Logger | Receives dropped changes; archive them here |
| partitionZone | UTC | Zone for DAY/MONTH/YEAR log partitions |
| mysql(DataSource) | none | Borrowed DataSource for repositories registered without an explicit backend |
| jsonCodec / binaryCodec | default Jackson / none | Codecs of the implicit MySQL mapper and of logs unless logCodecs overrides them |
| logCodecs(json, binary) | inherited | Log-only codecs; null JSON selects the default |
| logQueueCapacity | 100,000 | Logs one LogRepository may hold waiting for storage. At the limit insert returns false, reports the log to DataErrorHandler on the inserting thread as a LOG_INSERT failure (attempt 0) and counts it in DataStats.droppedLogs; a refused log was never accepted and does not make close fail |

repositories(...) and logRepositories(...) accept classes or lists; overloads with an explicit EntityMapper or MysqlAccess ignore mysql(DataSource). Registrations without a backend and no DataSource fail build with IllegalArgumentException. build validates repositories, identity and log mappings, then runs each state Mapper's initialize (mapping and schema; executed DDL is not rolled back if a later step fails), then creates caches and starts one persistence thread.

GameData closes only its own thread, never the DataSource, MongoClient or Mapper. close may wait for database calls; calling close or flush from a Mapper, RetryPolicy or DataErrorHandler callback is rejected to avoid deadlock. After close every repository operation throws DataOperationException.

<a id="31-build-的装配阶段"></a>
<a id="4-entity-与身份元数据"></a>

## 4. Entity and identity metadata

Inherited fields are supported; annotations are on nonstatic, nonfinal fields. State entities need an accessible no-argument constructor; loading writes fields directly through VarHandles (open the package under JPMS). Identity fields are byte/short/int/long, their wrappers or String, never null. Composite keys need explicit, distinct `order` values. `GroupKey.of(Object...)` keeps its array without copying (do not mutate it) and compares typed values: `GroupKey.of(42L)` ≠ `GroupKey.of(42)`. `MapKeys.of(a, b, …)` joins at least two integer/String values with ':' and rejects ':' inside values.

```java
public interface EntityMapper {
    void initialize(EntityMeta meta);
    Object load(EntityMeta meta, Object id);
    List<?> loadGroup(EntityMeta meta, GroupKey key);      // nonnull, empty for no rows
    void insertBatch(EntityMeta meta, List<?> entities);
    void updateBatch(EntityMeta meta, List<?> entities);
    void deleteBatch(EntityMeta meta, List<Object> ids);
    void deleteInsertBatch(EntityMeta meta, List<?> entities);
}
```

<a id="41-身份编译与存储编译的分工"></a>

EntityMeta holds identity and field access only; storage rules belong to each Mapper (an @Id without @Column is valid identity but rejected by MySQL mapping). A custom Mapper must keep entity types separate, never turn a load failure into absence, apply each batch atomically, and preserve causes.

<a id="5-write-back-implementation-and-limits"></a>
<a id="5-写回实现与适用边界"></a>

## 5. Write-back implementation and limits

PendingBuffer keeps one `ConcurrentHashMap<id, Change>` per EntityMeta; a Change holds the operation and latest entity reference and compares by identity. `record` merges with one `compute` using the merge matrix of the standard. Each pass claims an entry by conditional removal `pending.remove(id, change)`: a change recorded after the claim creates a new entry for the next pass, so nothing is lost between traversal and removal, and a single persistence thread keeps older changes of an id ahead of newer ones.

Failure handling. Mappers apply each batch atomically (MySQL in one transaction, Mongo by `_id` replacement), so a failed batch leaves no partial rows:

1. RetryPolicy returns true (default: lost or refused connections, pool timeouts, I/O, lock wait timeout 1205, deadlock 1213, SQLState 08xxx/40xxx, Mongo network/timeout/primary changes): the batch merges back under any newer change and is retried next pass, without an attempt limit. Kept changes are bounded by the number of dirty entities.
2. Not retryable and more than one row: the batch is split and rows run one by one, so the others still persist.
3. Not retryable single row: dropped, counted in failedBatches, reported synchronously to DataErrorHandler (attempt 1 = single-row batch, 2 = isolated after splitting); later flush/close throw DataSaveException.
4. Merging back conflicts with a newer change (an older DELETE then an UPDATE): the newer change wins; the older one is dropped and reported.

A MySQL UPDATE matching zero rows fails as a non-retryable single row (keep Connector/J's default `useAffectedRows=false`); a Mongo update whose document is missing fails the same way.

The saving thread reads entities without snapshots. Collections inside entities that business code modifies must be thread-safe; fields that must change consistently should be changed together before one update call. There is no WAL: a crash loses at most one flush interval plus kept failed changes.

### 5.1 Cache eviction

An entry is evicted only when it has been idle for cacheExpire and has nothing pending. Eviction runs on the persistence thread after a pass and at most every max(flushInterval, cacheExpire/4), so no claimed change is in flight. Single checks `pending.containsKey(id)`; Group collects the GroupKeys of pending changes once per sweep (group deletes keep their entity for this). The sweep reads the clock before building that view and rechecks the idle time inside the per-key atomic operation, so a mutation the view misses happened later, refreshed its entry and keeps it. Reads refresh the access time at most once per min(cacheExpire/16, 1 s), so threads reading the same hot entity do not keep writing its cache line; eviction therefore waits cacheExpire plus that interval. Consequently a load never consults the buffer: a missing key has nothing pending and storage is current. During a storage outage dirty entities stay cached until saved or dropped.

### 5.2 One pass

```text
for each EntityMeta:
  claim every pending entry (conditional remove)
  group as DELETE → DELETE_INSERT → INSERT → UPDATE, split by batchSize; save or classify failures
logs: finite drain, batched per partition table, failures classified the same way
then evict idle clean cache entries (when due); next pass after max(0, interval − pass duration)
```

Periodic passes, explicit flush and close never run Mapper batches concurrently.

### 5.3 RetryPolicy, errors and close

RetryPolicy only chooses between keeping for the next pass and splitting/dropping; there is no immediate retry or backoff thread. A RetryPolicy that throws counts as non-retryable. DataErrorHandler receives only dropped changes and runs on the saving thread, for example to write them to a local dead-letter file. Retryable failures log one WARNING per pass.

The admission read lock orders shutdown against mutations in progress. close takes the write lock to switch to CLOSING, stops scheduling and flushes until nothing is pending or shutdownTimeout elapses, then runs a final pass in which retryable failures are dropped and reported. Any drop during the lifetime makes close throw DataSaveException; repeated calls report the same failure. A database call that never returns also blocks close; set timeouts in the pool or driver.

<a id="6-mysql-mapping"></a>
<a id="6-mysql-映射"></a>

## 6. MySQL mapping

```java
@Table(name = "player_task", indexes = { @Index(name = "idx_role_task", columnList = "role_id, task_id") })
public class Task {
    @Id @Column private long id;
    @cn.managame.data.annotation.GroupKey @Column private long roleId;
    @MapKey @Column private long taskId;
    @Column private int progress;
}
```

Only @Column fields persist; Table.name is required. Names default to snake_case, use ASCII letters, digits and '_' (≤64 characters) and are quoted with backticks; long generated index names are truncated with a stable hash.

| Java (DEFAULT) | MySQL | Default |
| --- | --- | --- |
| byte / short / int / long | TINYINT / SMALLINT / INT / BIGINT | 0 |
| float / double / boolean | FLOAT / DOUBLE / TINYINT | 0 |
| char / String | CHAR(1) / VARCHAR(255) | '' |
| byte[] | BLOB | none |

Primitives and identity columns are NOT NULL. TEXT accepts String only. JSON stores a String as raw JSON and other types through the JSON codec; BINARY passes byte[] through and needs a BinaryCodec for other types; DEFAULT rejects complex objects. `Column.defaultValue` is trusted raw DDL.

<a id="61-预编译和运行期工作"></a>

Initialization compiles column order, accessors, codecs and SQL. INSERT writes all columns without a preflight SELECT; UPDATE writes non-identity columns by primary key (identity-only entities skip it); DELETE uses the primary key; DELETE_INSERT encodes first and then deletes and inserts in one transaction. Schema initialization reads information_schema and only adds missing tables, columns and explicit indexes; it checks basic types, the single-column primary key and explicit index shape, but not VARCHAR length, defaults or nullability. GroupKey implies no index.

MysqlAccess offers queryOne (null for no row, MysqlException for several), query, update, batchUpdate and transaction (one connection, commit on success, rollback on exception, valid only on the callback thread). JdbcMysqlAccess takes a connection per operation; MysqlException keeps the SQL, cause, `sqlState()` and `vendorCode()`. No automatic retries at this level.

<a id="default-json-field-binding"></a>

### 6.1 Default JSON field binding

`JsonCodec.defaultCodec()` is the shared default Jackson configuration. For a state entity with non-String JSON columns, initialization builds one temporary entity and binds each field's decoder to its declared generic type plus the initializer's class: a `Map<Integer,Long>` field initialized with `new ConcurrentHashMap<>()` loads as ConcurrentHashMap<Integer,Long>. Constructors and initializers must be deterministic and acquire no resources. Null initializers use the declared type; raw types cannot recover generics; default typing is off. Malformed JSON or trailing tokens throw IllegalArgumentException, which a load reports as DataLoadException without negative caching. Custom codecs may override `decoder(Type)` or `decoder(Type, Class<?>)`. Sources and tests: [MysqlFieldMeta](../../game-data/src/main/java/cn/managame/data/mysql/MysqlFieldMeta.java), [MysqlMappingTest](../../game-data/src/test/java/cn/managame/data/mysql/MysqlMappingTest.java), [MysqlBuilderTest](../../game-data/src/test/java/cn/managame/data/MysqlBuilderTest.java).

<a id="7-mongodb-映射"></a>
<a id="71-与-mysql-的相同点和差异"></a>

## 7. MongoDB mapping

Collection.name is required; only @Field fields persist; the id is always `_id`. MongoIndex lists database field names, all ascending. MongoEntityMapper takes a MongoDatabase or a MongoAccess; DriverMongoAccess uses the database's CodecRegistry (scalars, byte[], collections and Map with String/Integer/Long keys; register codecs for other classes). Loading uses the no-argument constructor; missing fields keep constructor defaults.

| Intent | MySQL | MongoDB |
| --- | --- | --- |
| INSERT / DELETE_INSERT | INSERT / delete+insert transaction | ordered bulk replacement, upsert |
| UPDATE | non-identity columns; 0 rows fails | ordered bulk replacement without upsert; a missing document fails |
| DELETE | by primary key | `_id $in` |
| Schema | tables, columns, explicit indexes | collections, explicit ascending indexes |

`MongoAccess.replaceMany` returns the matched (already existing) count; MongoEntityMapper fails an update when it is smaller than the batch. Replacement is not `$set`: fields added outside the mapping are not preserved. An ordered bulk write is not a transaction; earlier documents of a failed batch may already be replaced, which a retry overwrites again.

<a id="8-log"></a>
<a id="81-drain-与分表的运行边界"></a>
<a id="82-扩展实现时需要保留的边界"></a>

## 8. Log

LogRepository needs neither Id nor no-argument constructor and does not use EntityMapper. @Table/@Column compile an INSERT. At most one @PartitionKey: VALUE takes an integer or String (suffix of ASCII letters, digits, '_'); DAY/MONTH/YEAR take epoch milliseconds (long) rendered in partitionZone as uuuuMMdd/uuuuMM/uuuu. Without a partition key the base table is used.

insert validates type and partition and enqueues without copying; do not change a log after inserting it. Each pass drains a finite count, groups by physical table and inserts each group in one transaction; failures are classified as in section 5 (retryable logs go back to the queue tail). The base table is managed externally. When an insert fails because a partition table does not exist (MySQL 1146 / SQLState 42S02), the writer runs `CREATE TABLE IF NOT EXISTS partition LIKE base` and retries the insert once. close drains every accepted log.

<a id="9-异常与验证"></a>
<a id="91-错误扩展接口"></a>
<a id="92-易错契约的测试定位"></a>

## 9. Exceptions and validation

DataLoadException: storage load failure or invalid loaded data. DataOperationException: not initialized, closed, group not loaded, illegal change sequence, Mongo update of a missing document. DataSaveException: a change was dropped (reported by flush/close). Configuration and metadata errors throw IllegalArgumentException; schema and database errors keep their exceptions. Error numbers: [Core](OGBS-Core-1.0.md#data-constants).

```java
public record DataFailure(int errorCode, Class<?> entityType, DataOperation operation,
                          List<?> batch, Throwable cause, int attempt) {}
public interface RetryPolicy { boolean shouldRetry(DataFailure failure); }
public interface DataErrorHandler { void onError(DataFailure failure); }
```

DataOperation (INSERT, UPDATE, DELETE, DELETE_INSERT, LOG_INSERT) is the coalesced intent actually saved: DELETE then INSERT reports DELETE_INSERT. `RetryPolicy.transientFailures()` walks causes, next exceptions and suppressed exceptions; combine it as `f -> RetryPolicy.isTransient(f.cause()) || myRule(f)`. `RetryPolicy.never()` keeps nothing.

| Contract | Test |
| --- | --- |
| No preflight SELECT; load failures not cached | DataContractTest.negativeCacheDeleteAndInsertNeverSelect / loadFailuresAreNotNegativeCached |
| Group writes load the group; getGroup is a read-only view | DataContractTest.groupWritesLoadTheGroupAndGetGroupIsAReadOnlyLiveView |
| Writes check existence at once; uncached writes never read storage | DataContractTest.groupWritesAreCheckedAgainstTheLoadedGroup / cachedSingleWritesFailFastAndUncachedOnesNeverSelect |
| getOrCreate creates once | DataContractTest.getOrCreateInsertsOnceAndReturnsTheCachedEntity |
| Merge matrix | DataContractTest.legalMergeTable / illegalMergeTable |
| Batch order and splitting | DataContractTest.batchesOrderedAndSplitAndNeverMergeAcrossFlushes |
| Retryable kept, bad row isolated | DataContractTest.retryableFailureKeepsChangesUntilStorageRecovers / nonRetryableBadRowIsIsolatedFromItsBatch |
| Dirty entries never evicted; clean ones evicted and reloaded | DataContractTest.entitiesWithUnsavedChangesAreNeverEvicted / cleanIdleEntriesAreEvictedAndReloaded |
| close retries, then drops and reports | DataContractTest.closeRetriesThenDropsAndReportsLeftovers |
| Callback close rejected without deadlock | DataContractTest.closeFromCallbackIsRejectedWithoutDeadlock |
| Log partitions, drain, partition creation | LogContractTest.closeDrainsPartitionsAndBatchesWithoutSchemaInitialization / missingPartitionTableIsCreatedFromTheBaseTable |
| Mongo update of a missing document fails | MongoMappingTest |

Verified with in-memory Mappers, H2 in MySQL mode and a recording MongoAccess. [DatabaseIntegrationTest](../../game-data/src/test/java/cn/managame/data/DatabaseIntegrationTest.java) runs against real servers when `OGBS_DATA_MYSQL_URL` / `OGBS_DATA_MONGO_URI` are set and is skipped otherwise. Real MySQL/MongoDB behavior, production performance and long outages are unverified.

<a id="11-explicit-flush-and-datastats"></a>
<a id="11-显式-flush-与-datastats"></a>

## 10. Explicit flush and DataStats

`GameData.flush()` runs one pass synchronously: every change accepted before the call is saved, while changes written concurrently may wait for the next pass. It throws DataOperationException when closed or called from a persistence callback, and DataSaveException when this pass dropped a change (already reported to DataErrorHandler) or retryable failures remain kept (those stay pending). A drop in an earlier pass does not fail a later clean flush; close still throws DataSaveException for any drop during the GameData's lifetime. Avoid a service-wide flush on a busy game Route.

`GameData.stats()` returns DataStats: accepting, pendingChanges (pending entries, including kept failures; changes claimed by a running pass are not counted), queuedLogs, failedBatches (dropped changes), flushes (passes, excluding close's final one), saveNanos, singleCacheEntries (including cached absence) and groupCacheEntries (groups, not entities) and droppedLogs (logs refused because their queue was full). Values are approximate snapshots; queuedLogs is kept by a counter and is O(1).
