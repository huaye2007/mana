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
| annotation / key | Identity annotations, GroupKeys, MapKeys |
| meta / mapper | EntityMeta (identity metadata for storage SPIs), EntityMapper, SchemaMode |
| codec / error | JSON/BINARY codecs, exceptions, DataFailure, RetryPolicy, DataErrorHandler |
| mysql | SQL annotations, MysqlAccess, JdbcMysqlAccess, MysqlEntityMapper, log mapping |
| mongo | BSON annotations, MongoAccess, DriverMongoAccess, MongoEntityMapper |

<a id="2-repository-api"></a>

## 2. Repository API

```java
public abstract class SingleRepository<K, E> {
    public final E get(K key);
    public final E getOrCreate(K key, Function<? super K, ? extends E> factory);
    public final boolean update(K key, Consumer<? super E> change);   // read, change and record in one step; false if absent
    public final void insert(E entity);
    public final void update(E entity);
    public final void delete(K key);
}
public abstract class GroupRepository<K, E> {
    public final Map<K, E> getGroup(GroupKeys key);   // read-only live view
    public final E get(GroupKeys key, K mapKey);
    public final boolean update(GroupKeys key, K mapKey, Consumer<? super E> change);
    public final void insert(E entity);
    public final void update(E entity);
    public final void delete(E entity);
    public final void deleteGroup(GroupKeys key);
}
public abstract class LogRepository<E> {
    public final boolean insert(E entity);   // false: log queue full, reported to DataErrorHandler
}
```

A business repository directly extends one concrete parameterized base and has a no-argument constructor; intermediate generic repositories are unsupported. `build` creates it; obtain it with `data.repository(Players.class)`. One state repository per entity type. Single K is the boxed Id type; Group K is the boxed MapKey type, or String for a composite MapKey. Repositories are initialized only by the builder; extend EntityMapper for a custom backend.

`update(key, change)` reads the entity on the calling thread (loading it when not cached), runs `change` and then records an UPDATE. It replaces the three steps "`get` → change → `update(entity)`", so a change can no longer be made and then never recorded. When the entity is absent, `change` does not run and the call returns false. When `change` throws, nothing is recorded, but fields it changed before throwing stay changed in memory. The Group variant's `change` must not change the entity's identity fields (D-ID-02).

<a id="21-repository-直接持有依赖"></a>
<a id="22-读写路径"></a>

### 2.1 Cache and mutation paths

Each repository holds one EntityCache: Single caches entity or confirmed absence (null) by id; Group caches a `ConcurrentHashMap<K,E>` by GroupKeys and getGroup returns a read-only live view of it (created once per load, so the same object for as long as the group stays cached); business code changes a group only through insert/update/delete, and a direct put/remove throws UnsupportedOperationException. Single reads (get/getOrCreate) and every Group read or write load a missing key; Single writes never load. Loads run synchronously on the calling thread; a missing key loads once while concurrent callers of that key wait. The load runs outside the cache map's locks, so other keys are never blocked by it; a mutation of the same key during the load waits for it and then applies to its result; if the key is evicted while loading, the result is discarded and loaded again, so nothing older than memory is ever installed. Reading the same key again inside its loader throws IllegalStateException. A load failure throws DataLoadException and is not cached as absence.

Data has no asynchronous reads. On the default virtual-thread Route executor a synchronous load only parks the current Route's virtual thread; later messages of that Route have to wait for it anyway, so ordering is unchanged and business code needs no callbacks. With a platform-thread Route executor, preload a player's data when the player enters its Route so in-game reads hit the cache: a miss holds the whole stripe thread, and the other Routes of that stripe wait with it. Do not call `get`, `getGroup` or a write that loads a group on a Netty EventLoop (Network ConnectionHandler callbacks, RPC and Router handlers): one miss stalls every connection on that loop (D-MODEL-01). Concurrent loads are bounded by the DataSource pool, and with the pool exhausted a load fails with DataLoadException after the pool's acquisition timeout (HikariCP's `connectionTimeout`).

Writes are checked as soon as the key is cached: Single insert of an existing entity and update of a key cached as absent throw DataOperationException, and delete of an absent key does nothing; a key that is not cached is recorded as is, without reading storage to check it. Group writes load the group first and are therefore always checked: insert of an existing MapKey, update of a missing one, or update with an entity of another @Id throws; delete removes whatever entity holds the MapKey and does nothing when it is missing. `getOrCreate` records the factory's entity as an INSERT and caches it when none exists; the factory runs outside any lock, must return an entity whose @Id equals the key, and under contention the first recorder wins while the other caller gets the cached entity.

The save thread reads entity fields while encoding (D-OWN-01). Collection fields that business code changes on its Route while the save thread may iterate them should be thread-safe collections (for example ConcurrentHashMap, CopyOnWriteArrayList, ConcurrentLinkedDeque), and scalar fields that need cross-thread visibility volatile or atomic; otherwise encoding may hit ConcurrentModificationException or observe a half-updated state. The framework takes no snapshot, by design, for performance. So that this requirement shows up during development, MySQL mapping checks the initializer of every Collection/Map field at initialization. A field initialized with a java.util collection that is not thread-safe (HashMap, ArrayList and the like) logs one WARNING suggesting a replacement type; so does a field with no initializer, because a load fills it with one that is not. A ConcurrentModificationException met while encoding is retryable, and the next pass reads the entity again (D-ERROR-01).

A mutation validates state and identity, passes the admission count (one CAS, no read-write lock), and then runs inside `EntityCache.update` for its key: the existence checks above run, `writer.record` coalesces the intent (an illegal sequence throws and leaves the cache unchanged), then the cache entry or group Map changes. Running the record inside the cache's per-key atomic update is what makes eviction safe (section 5.1). Recording is not a field transaction: fields changed directly before the call are not rolled back. deleteGroup records a primary-key DELETE for each current member, then clears the original Map.

Spring integration is application code: the [game-demo adapter](../../game-demo/src/main/java/cn/managame/demo/common/data/GameDataConfig.java) registers scanned singleton `@Repository` beans with the builder and supplies `data.repository(type)` as the bean instance ([DataRepositoryRegistrationTest](../../game-demo/src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java), [DataSpringWiringTest](../../game-demo/src/test/java/cn/managame/demo/DataSpringWiringTest.java)).

<a id="3-构建与生命周期"></a>

## 3. Build and lifecycle

```java
try (GameData data = GameDataBuilder.builder()
        .mysql(stateDataSource)
        .logMysql(logDataSource)
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
| batchSize | 500 | Rows per batch ([§6.2](#62-batch-throughput-and-the-jdbc-url)) |
| saveParallelism | 4 | Tables saved at once (D-WRITE-02). In each pass every entity type and every log repository is saved by one thread, and different tables run in parallel, each holding one connection. The DataSource pool should be larger than this plus the loads Routes run at the same time. 1 saves every table in turn on the persistence thread |
| shutdownTimeout | 30 s | How long close may save (D-CLOSE-01): no save starts after it, and the changes and logs left are reported to DataErrorHandler with attempt 0 without another try; 0 means close saves nothing and reports at once. Under container integration it is set from the shutdown budget's reserved flush slice before the application's configurers run, so it defaults to that instead ([Spring §3.0](OGBS-Spring-Java-25-Specification-1.0.md#30-shutdown-budget)) |
| retryPolicy | RetryPolicy.transientFailures() | true keeps a failed change for the next pass; false splits, drops and reports (section 5) |
| errorHandler | System.Logger | Receives dropped changes; archive them here |
| partitionZone | UTC | Zone for DAY/MONTH/YEAR log partitions |
| mysql(DataSource) | none | Borrowed DataSource for state repositories registered without a backend; it never serves logs |
| logMysql(DataSource) | none | Borrowed DataSource for log repositories registered without a backend. Logs are a store of their own (D-LOG-04); one database for both is the same DataSource passed to both methods |
| jsonCodec / binaryCodec | default Jackson / none | Codecs of the implicit MySQL mapper and of logs unless logCodecs overrides them |
| logCodecs(json, binary) | inherited | Log-only codecs; null JSON selects the default |
| maxPendingChanges | 100,000 | Distinct entities that may wait to be saved, across all state repositories (D-WRITE-04). A change to an entity already waiting merges and is always accepted; one that adds another entity waits pendingChangeWait and is then refused with DataOperationException, counted in DataStats.rejectedChanges. A refused change is not recorded, leaves the cache as it was, reaches no DataErrorHandler and does not make flush or close fail |
| pendingChangeWait | 5 s | How long such a write waits for a pass to free room before being refused; 0 refuses at once. Closing refuses waiters rather than holding them, so they cannot delay close |
| maxCacheEntries | 1,000,000 | Entries one state repository may cache (D-CACHE-03). Above it the clean entries longest unread are evicted on the persistence thread, every tick rather than only on the idle sweep; entries with unsaved changes are never evicted, so keep this above maxPendingChanges |
| logQueueCapacity | 100,000 | Logs one LogRepository may hold waiting for storage. At the limit insert returns false, reports the log to DataErrorHandler on the inserting thread as a LOG_INSERT failure (attempt 0) and counts it in DataStats.droppedLogs; a refused log was never accepted and does not make close fail |
| schema | SchemaMode.CREATE | What build may do to storage structure (D-STORE-02): CREATE creates what is missing and validates what exists, VALIDATE issues no DDL and fails on anything missing or too narrow, NONE reads no catalog. It reaches every Mapper through `EntityMapper.initialize`, including one the application supplied, and decides whether log partitions may be created at run time (only CREATE may), so this is the single place a deployment turns DDL off |

repositories(...) and logRepositories(...) accept classes or lists; overloads with an explicit EntityMapper or MysqlAccess ignore the defaults. A registration that names a backend replaces an earlier one for the same repository type that did not and keeps its position, which is how a container integration lets the application redirect a repository it had already discovered; any other repeat of a type is an IllegalArgumentException at the registration call. A repository still unbound when build runs fails with IllegalArgumentException naming what would bind it, mysql(DataSource) or an EntityMapper for state and logMysql(DataSource) or a MysqlAccess for logs, before any DataSource is opened. build validates repositories, identity and log mappings, then runs each state Mapper's `initialize(EntityMeta, SchemaMode)` with the configured mode (mapping and structure; executed DDL is not rolled back if a later step fails), then creates caches and starts one persistence thread, plus its save threads when saveParallelism is above 1.

GameData closes only its own thread, never the DataSource, MongoClient or Mapper. close may wait for database calls; calling close or flush from a Mapper, RetryPolicy or DataErrorHandler callback is rejected to avoid deadlock. After close every repository operation throws DataOperationException.

<a id="31-build-的装配阶段"></a>
<a id="4-entity-与身份元数据"></a>

## 4. Entity and identity metadata

Inherited fields are supported; annotations are on nonstatic, nonfinal fields. State entities need an accessible no-argument constructor; loading writes fields directly through VarHandles (open the package under JPMS). Identity fields are byte/short/int/long, their wrappers or String, never null. Composite keys need explicit, distinct `order` values. `GroupKeys.of(Object...)` keeps its array without copying (do not mutate it) and compares typed values: `GroupKeys.of(42L)` ≠ `GroupKeys.of(42)`. `MapKeys.of(a, b, …)` joins at least two integer/String values with ':' and rejects ':' inside values.

```java
public interface EntityMapper {
    void initialize(EntityMeta meta, SchemaMode schema);
    Object load(EntityMeta meta, Object id);
    List<?> loadGroup(EntityMeta meta, GroupKeys key);      // nonnull, empty for no rows
    void insertBatch(EntityMeta meta, List<?> entities);
    void updateBatch(EntityMeta meta, List<?> entities);
    void deleteBatch(EntityMeta meta, List<Object> ids);
    void deleteInsertBatch(EntityMeta meta, List<?> entities);
}
```

<a id="41-身份编译与存储编译的分工"></a>

EntityMeta holds identity and field access only; storage rules belong to each Mapper (a transient @Id is valid identity but rejected by the MySQL and Mongo mappings, which must save it). A custom Mapper must keep entity types separate, never turn a load failure into absence, apply each batch atomically, and preserve causes.

<a id="5-write-back-implementation-and-limits"></a>
<a id="5-写回实现与适用边界"></a>

## 5. Write-back implementation and limits

PendingBuffer keeps one `ConcurrentHashMap<id, Change>` per EntityMeta; a Change holds the operation and latest entity reference and compares by identity. `record` merges with one `compute` using the merge matrix of the standard, and keeps the count of distinct pending keys inside that per-key atomic operation, so `DataStats.pendingChanges` is exact and O(1) rather than a walk.

Admission (D-WRITE-04). A repository calls the manager before its mutation, outside the shutdown barrier and outside any cache lock, so a waiting writer holds nothing: it returns at once when the dirty set is below maxPendingChanges or when that key is already pending (`containsKey`, a merge that adds no entry), otherwise it parks in 1 ms steps until a pass frees room, the component stops accepting (DataOperationException, so close is never delayed by a waiter), the thread is interrupted (DataOperationException, interrupt flag restored) or pendingChangeWait elapses (DataOperationException, counted in rejectedChanges). A caller that is running a lane itself — a Mapper or DataErrorHandler callback — never parks and is refused at once. `deleteGroup` records one change per member and is admitted as one call, so it may overshoot the bound by the size of the group, as concurrent writers may overshoot it by their number. Each pass claims an entry by conditional removal `pending.remove(id, change)`: a change recorded after the claim creates a new entry for the next pass, so nothing is lost between traversal and removal, and one table is saved by one thread per pass and passes never overlap, so older changes of an id stay ahead of newer ones.

Failure handling. Mappers apply each batch atomically (MySQL in one transaction, Mongo by `_id` replacement), so a failed batch leaves no partial rows:

1. RetryPolicy returns true (default: lost or refused connections, pool timeouts, I/O, lock wait timeout 1205, deadlock 1213, SQLState 08xxx/40xxx, Mongo network/timeout/primary changes): the batch merges back under any newer change and is retried next pass, without an attempt limit. Kept changes are bounded by the number of dirty entities.
2. Not retryable and more than one row: the batch is split and rows run one by one, so the others still persist.
3. Not retryable single row: dropped, counted in failedBatches, reported synchronously to DataErrorHandler (attempt 1 = single-row batch, 2 = isolated after splitting); later flush/close throw DataSaveException.
4. Merging back conflicts with a newer change (an older DELETE then an UPDATE): the newer change wins; the older one is dropped and reported.

A MySQL UPDATE matching zero rows fails as a non-retryable single row (keep Connector/J's default `useAffectedRows=false`); a Mongo update whose document is missing fails the same way.

The saving thread reads entities without snapshots. Collections inside entities that business code modifies must be thread-safe; fields that must change consistently should be changed together before one update call. There is no WAL: a crash loses at most one flush interval plus kept failed changes.

### 5.1 Cache eviction

An entry is evicted when it has been idle for cacheExpire, or when the cache is over maxCacheEntries, and in both cases only when it has nothing pending. Idle eviction runs on the persistence thread after a pass and at most every max(flushInterval, cacheExpire/4); the entry bound is checked after every pass instead, because a cache over its budget should come down within passes. Over the bound, one sweep selects the clean entries with the oldest access time through a bounded heap — never more than maxCacheEntries of them — and removes each one with a per-key atomic operation that keeps it if it was read, changed or became dirty since it was selected. Access time is refreshed at most once per min(cacheExpire/16, 1 s) (below), so entries read within one such window are not ordered against each other, and a cache filling faster than sweeps run settles somewhat above its bound rather than at it. No claimed change is in flight during either kind of eviction. Single checks `pending.containsKey(id)`; Group collects the GroupKeys of pending changes once per sweep (group deletes keep their entity for this). The sweep reads the clock before building that view and rechecks the idle time inside the per-key atomic operation, so a mutation the view misses happened later, refreshed its entry and keeps it. Reads refresh the access time at most once per min(cacheExpire/16, 1 s), so threads reading the same hot entity do not keep writing its cache line; eviction therefore waits cacheExpire plus that interval. Consequently a load never consults the buffer: a missing key has nothing pending and storage is current. During a storage outage dirty entities stay cached until saved or dropped.

### 5.2 One pass

```text
each EntityMeta with pending changes and each LogRepository with queued logs is one lane,
run in parallel on saveParallelism save threads (inline on the thread running the pass when
there is one lane or the parallelism is 1):
  entity lane: claim up to the number pending for it right now (conditional remove), never more
               group as DELETE → DELETE_INSERT → INSERT → UPDATE, split by batchSize; save or classify failures
  log lane:    finite drain, batched per partition table, failures classified the same way
wait for every lane (uninterruptibly, so the next pass never overlaps a lane still saving)
then evict cache entries over the bound, and idle clean ones when due
next pass after max(0, interval − pass duration)
```

The claim boundary is a count read once per entity type, as `LogRepository.drain` already did for logs: what is recorded while the pass runs waits for the next pass. A pass therefore costs at most what maxPendingChanges allows, which is what keeps its duration bounded and `flushes` moving while producers write. `GameData.flush()` repeats passes until it has claimed as much as was pending when it was called, and close repeats them within shutdownTimeout until no accepted change or log is left.

A backend is one EntityMapper instance, or the storage of one log repository. After two retryable failures in a row on one backend within a pass, every lane using that backend starts no further save (D-WRITE-05). Changes of the current type that were claimed but not attempted go back to the buffer by the same path as a retryable failure. Lanes not yet started claim nothing, and no more logs are dequeued, so nothing the pass did not attempt moves. The count resets when a pass starts, and on any successful save or non-retryable failure. A pass that stopped returns the whole pending count, which flush reports as `N changes remain pending`.

One table is never saved by two threads at once, and passes never overlap. The thread contract follows from that:

- EntityMapper's batch methods are called concurrently for different entity types, never for the same one. A Mapper shared by several entity types must therefore be thread-safe across types; the built-in MysqlEntityMapper and MongoEntityMapper are.
- RetryPolicy may also be called concurrently.
- DataErrorHandler calls are serialized by the component: one runs at a time, possibly on different save threads.

Calling flush or close from a Mapper or Handler callback, or waiting there for room in the dirty set, is refused at once. The check is whether the current thread is running a lane.

### 5.3 RetryPolicy, errors and close

RetryPolicy only chooses between keeping for the next pass and splitting/dropping; there is no immediate retry or backoff thread. A RetryPolicy that throws counts as non-retryable. The default `transientFailures()` treats ConcurrentModificationException as retryable. Jackson's exceptions (`com.fasterxml.jackson.*`) are IOExceptions, but they mean the value cannot be encoded, so they are not retryable; their causes are still examined. DataErrorHandler receives only dropped changes, for example to write them to a local dead-letter file. It runs on one of the save threads, and its calls are serialized. Retryable failures log one WARNING per pass.

An admission count orders shutdown against mutations in progress: each mutation enters and leaves with one CAS, and close sets the closing bit and waits for the count to reach zero. close switches to CLOSING, stops scheduling and saves in passes at flushInterval within shutdownTimeout until no change or log is pending; a pass past the deadline starts no save. It then switches to CLOSED and hands every change and log still unsaved to DataErrorHandler as its own failure (attempt 0, cause a DataOperationException saying close came first, whose cause is the latest retryable failure) without another try, so close ends within shutdownTimeout plus the one Mapper call in progress at the deadline. Any drop during the lifetime makes close throw DataSaveException; repeated calls report the same failure. How long that call in progress takes is up to the pool and driver timeouts, and a call that never returns blocks close; set timeouts in the pool or driver. Verified by [StorageOutageTest](../../game-data/src/test/java/cn/managame/data/StorageOutageTest.java).

<a id="6-mysql-mapping"></a>
<a id="6-mysql-映射"></a>

## 6. MySQL mapping

```java
@Table(name = "player_task", indexes = { @Index(name = "idx_role_task", columnList = "role_id, task_id") })
public class Task {
    @Id private long id;
    @GroupKey private long roleId;
    @MapKey private long taskId;
    private int progress;                 // no annotation needed (D-MAP-01)
}
```

Every non-static, non-transient, non-synthetic instance field is a column (D-MAP-01). `@Column` may be left out; it is needed only to rename a column (`name`), choose a type (`type`), or set a default (`defaultValue`) or a VARCHAR length (`length`). A final field fails initialization, with a message suggesting to drop final or mark the field transient. Table.name is required. Names default to snake_case, use ASCII letters, digits and '_' (≤64 characters) and are quoted with backticks; long generated index names are truncated with a stable hash.

| Java (DEFAULT) | MySQL | Default |
| --- | --- | --- |
| byte / short / int / long | TINYINT / SMALLINT / INT / BIGINT | 0 |
| float / double / boolean | FLOAT / DOUBLE / TINYINT | 0 |
| char / String | CHAR(1) / VARCHAR(255), VARCHAR(n) with `@Column(length = n)` | '' |
| byte[] | BLOB | none |
| Anything else: collections, maps, enums, objects | JSON (through the JSON codec) | none |

Primitives and identity columns are NOT NULL. TEXT accepts String only. JSON stores a String as raw JSON and other types through the JSON codec; BINARY passes byte[] through and needs a BinaryCodec for other types. `length` applies only to a String column of type DEFAULT and fails initialization anywhere else; use `type = TEXT` for longer text. `Column.defaultValue` is trusted raw DDL.

<a id="61-预编译和运行期工作"></a>

Initialization compiles column order, accessors, codecs and SQL. INSERT writes all columns without a preflight SELECT; UPDATE writes non-identity columns by primary key (identity-only entities skip it); DELETE uses the primary key; DELETE_INSERT encodes first and then deletes and inserts in one transaction. Schema initialization follows the configured SchemaMode. It reads `information_schema.tables`, then `information_schema.columns` (`column_type`, the only catalog column carrying the declared size) and `information_schema.statistics`, and checks the base type with every declared size, the single-column primary key and the shape of each explicit index; defaults, nullability, charset and collation are not checked. CREATE adds missing tables, columns and explicit indexes and nothing else — no DROP, MODIFY or CHANGE — and skips the column read for a table it created itself, since that table is the mapping. VALIDATE issues no statement: a missing table, column or explicit index throws. Both throw IllegalArgumentException with `Schema conflict on <table>: …`, and a mapping that disagrees with an existing table issues no DDL at all, because every existing column is checked before any column is added. CREATE also runs `CREATE TABLE` (not `IF NOT EXISTS`, which would report success without saying what is now there), `ALTER TABLE … ADD COLUMN` and `CREATE INDEX` as statements that may lose a race with another instance: MySQL 1050/SQLState 42S01, 1060/42S21 and 1061 mean the winner applied it, and the loser then reads the catalog again and validates what it created, so a conflicting winner still fails the startup while an identical one does not. Any other error, 1142 for a missing privilege for example, propagates as MysqlException. GroupKey implies no index; but once CREATE or VALIDATE has read `information_schema.statistics`, if no existing or declared index leads with all group key columns (in any order), it logs a WARNING `Group loads of <table> filter on (...) ...` naming the `@Index` to declare.

MysqlAccess offers queryOne (null for no row, MysqlException for several), query, update, batchUpdate and transaction (one connection, commit on success, rollback on exception, valid only on the callback thread). JdbcMysqlAccess takes a connection per operation; MysqlException keeps the SQL, cause, `sqlState()` and `vendorCode()`. No automatic retries at this level. Once commit has returned, transaction logs a failure to restore autoCommit or to close the connection as a WARNING and does not throw it: the work is durable by then, and reporting a committed batch as failed would make the pipeline retry or drop changes already stored.

<a id="62-batch-throughput-and-the-jdbc-url"></a>
<a id="62-批量吞吐与-jdbc-url"></a>

### 6.2 Batch throughput and the JDBC URL

On MySQL/MariaDB the component merges UPDATEs itself. A batch is split into multi-row statements of at most
100 rows each (fewer for wide tables, to stay under 60,000 placeholders):
`UPDATE t SET c = CASE id WHEN ? THEN ? … END, … WHERE id IN (?, …)`, one round trip each. When a statement
matches fewer rows than it lists, the whole batch rolls back and fails, and is then split into single rows to
find the missing one. `MysqlAccess.supportsMultiRowUpdate()` decides whether this path is taken. The default
implementation returns false (one UPDATE per row). JdbcMysqlAccess reads connection metadata the first time
it is needed and returns true when the database is MySQL or MariaDB. When the metadata cannot be read it
answers false and does not remember the answer. The matched count relies on Connector/J's default
"found rows" count: do not set `useAffectedRows=true` on the URL, or rows whose values did not change count
as missing. 100 rows per statement is the middle of the flat region measured between 50 and 500. More rows
make the server's cost for the CASE grow with the statement; fewer pay more round trips.

INSERTs are still merged by the driver. Connector/J turns a batch into one multi-row `VALUES` only with
`rewriteBatchedStatements=true`; otherwise each row is its own round trip. JdbcMysqlAccess checks this the
first time it inspects the database and logs a WARNING when the flag is off. It asks Connector/J itself
first, so a flag set as a DataSource property counts too.

| Setting | INSERT | UPDATE | Why |
| --- | --- | --- | --- |
| `rewriteBatchedStatements=true` | ≈4–6× at batchSize 500, ≈7× at 2000 | small (≈1.2× in one smaller run) | The driver merges INSERT rows into one multi-row `VALUES`; UPDATEs are already merged into multi-row statements by the component |
| batchSize 2000 instead of 500 | ≈1.9× faster | ≈1.6× faster | Larger batches spread round-trip and transaction cost over more rows |
| saveParallelism 4 instead of 1 (4 tables) | ≈2.6× faster | ≈2.6× faster | Different tables are saved at once on different connections |

Set the flag on the URL the DataSource is built from
(`jdbc:mysql://host:3306/game?rewriteBatchedStatements=true`). batchSize defaults to 500; an
INSERT-dominated store, or one table with a heavy write volume, can use 2000. A remote database gains more than
these ratios, which were measured over loopback, the smallest round trip there is. Opening a connection per
batch instead of pooling costs between a few percent and about half the rate. The handshake is paid per batch
and weighs most when the batch's own work is cheap, which is as true of a 2000-row INSERT the driver merged
into one statement as of a small batch. A pool is worth having, though it is not the bottleneck, and it is
what makes the flag worth setting. Both multiplier columns above were measured pooled. Without a pool, the
INSERT figure at 2000 falls to about 4–5× and the UPDATE ones disappear altogether (×0.9 to ×1.0 in both runs),
so a deployment that sets the flag without pooling gains nothing on state writes.
Conditions, absolute figures, the spread between runs, and the harness that re-measures them:
[save-path load test](../reviews/2026-10-18-data-save-path-load-test.md) and
[multi-row UPDATE and per-table parallelism](../reviews/2026-10-21-data-save-path-parallel.md), run with
[DataSavePathLoad](../../game-benchmark/src/main/java/cn/managame/benchmark/DataSavePathLoad.java).

<a id="default-json-field-binding"></a>

### 6.1 Default JSON field binding

`JsonCodec.defaultCodec()` is the shared default Jackson configuration. For a state entity with non-String JSON columns, initialization builds one temporary entity and binds each field's decoder to its declared generic type plus the initializer's class: a `Map<Integer,Long>` field initialized with `new ConcurrentHashMap<>()` loads as ConcurrentHashMap<Integer,Long>. Constructors and initializers must be deterministic and acquire no resources. Null initializers use the declared type; raw types cannot recover generics; default typing is off. Malformed JSON or trailing tokens throw IllegalArgumentException, which a load reports as DataLoadException without negative caching. Custom codecs may override `decoder(Type)` or `decoder(Type, Class<?>)`. Sources and tests: [MysqlFieldMeta](../../game-data/src/main/java/cn/managame/data/mysql/MysqlFieldMeta.java), [MysqlMappingTest](../../game-data/src/test/java/cn/managame/data/mysql/MysqlMappingTest.java), [MysqlBuilderTest](../../game-data/src/test/java/cn/managame/data/MysqlBuilderTest.java).

<a id="7-mongodb-映射"></a>
<a id="71-与-mysql-的相同点和差异"></a>

## 7. MongoDB mapping

Collection.name is required. Every non-static, non-transient instance field is saved (D-MAP-01); `@Field` may be left out and is only used to rename a field. The id is always `_id`. MongoIndex lists database field names, all ascending. `MongoAccess.initialize(String collection, List<IndexDefinition> indexes, SchemaMode schema)` is reached only with CREATE or VALIDATE — under NONE the Mapper compiles the mapping and asks the Access nothing — and `DriverMongoAccess` tolerates MongoCommandException 48 (NamespaceExists) from a concurrent `createCollection`, while an identical `createIndex` is accepted again and needs no such case. MongoEntityMapper takes a MongoDatabase or a MongoAccess; DriverMongoAccess uses the database's CodecRegistry (scalars, byte[], collections and Map with String/Integer/Long keys; register codecs for other classes). Loading uses the no-argument constructor; missing fields keep constructor defaults.

| Intent | MySQL | MongoDB |
| --- | --- | --- |
| INSERT | INSERT transaction | unordered insertMany, never replaces; an existing `_id` is recognized as an own write by content, otherwise a conflict |
| DELETE_INSERT | delete+insert transaction | ordered bulk replacement, upsert |
| UPDATE | non-identity columns; 0 rows fails | ordered bulk replacement without upsert; a missing document fails |
| DELETE | by primary key | `_id $in` |
| Schema (CREATE) | tables, columns, explicit indexes | collections, explicit ascending indexes |
| Schema (VALIDATE) | a missing table, column or index throws | a missing collection or index throws |

`MongoAccess.replaceMany` returns the matched (already existing) count; MongoEntityMapper fails an update when it is smaller than the batch. Replacement is not `$set`: fields added outside the mapping are not preserved. An ordered bulk write is not a transaction; earlier documents of a failed batch may already be replaced, which a retry overwrites again.

`MongoAccess.insertMany` inserts only documents whose `_id` is not stored yet, never replaces, and returns the positions (ascending) of the documents refused because their `_id` exists; any other failure throws, and documents it did not concern may be stored by then. `DriverMongoAccess` uses an unordered insertMany and treats only 11000 on the `_id_` index as "exists"; a duplicate on another unique index and a write concern error throw as usual.

When an insert call fails, MongoEntityMapper remembers the documents it sent, keeping the first and the latest per `_id`, until the next successful insert, update or delete of that `_id` settles it; only the persistence pipeline calls a Mapper, so these records exist only while a failed change waits for its retry or split. On an existing `_id`: if the stored document equals this one nothing is written; if it equals a remembered one it is the mapper's own write and is replaced with the current content; otherwise DataOperationException is thrown and the stored document stays as it is (D-SINGLE-02). A conflict fails the whole batch, which the pipeline then splits into single rows per D-ERROR-02, and the single-row retries recognize the documents the batch did store. Known limit: when one insert fails, then several attempts fail without reaching the server while the entity changes, and the attempt that actually landed was neither of the two remembered, it is reported as a conflict — nothing is overwritten, and the change goes to the error Handler with its entity per D-ERROR-02. Verified by [MongoMappingTest](../../game-data/src/test/java/cn/managame/data/mongo/MongoMappingTest.java).

<a id="8-log"></a>
<a id="81-drain-与分表的运行边界"></a>
<a id="82-扩展实现时需要保留的边界"></a>

## 8. Log

LogRepository needs neither Id nor no-argument constructor and does not use EntityMapper. @Table/@Column compile an INSERT. At most one @PartitionKey: VALUE takes an integer or String (suffix of ASCII letters, digits, '_'); DAY/MONTH/YEAR take epoch milliseconds (long) rendered in partitionZone as uuuuMMdd/uuuuMM/uuuu. Without a partition key the base table is used.

insert validates type and partition and enqueues without copying; do not change a log after inserting it. Each pass drains a finite count, groups by physical table and inserts each group in one transaction; failures are classified as in section 5 (retryable logs go back to the queue tail). The base table is managed externally. With schema CREATE, when an insert fails because a partition table does not exist (MySQL 1146 / SQLState 42S02), the writer runs `CREATE TABLE IF NOT EXISTS partition LIKE base` and retries the insert once; under VALIDATE and NONE it issues no DDL and the failure is classified as in section 5 (1146 is not transient, so the logs are dropped and reported). close saves accepted logs within its deadline and reports those left after it with attempt 0.

<a id="9-异常与验证"></a>
<a id="91-错误扩展接口"></a>
<a id="92-易错契约的测试定位"></a>

## 9. Exceptions and validation

DataLoadException: storage load failure or invalid loaded data. DataOperationException: not initialized, closed, group not loaded, illegal change sequence, a dirty set that stayed full for pendingChangeWait, Mongo update of a missing document. DataSaveException: a change was dropped (reported by flush/close). Configuration and metadata errors throw IllegalArgumentException; schema and database errors keep their exceptions. Error numbers: [Core](OGBS-Core-1.0.md#data-constants).

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
| A pass claims the set it started with; passes keep ending; close drains the backlog | SavePassBoundaryTest |
| A full dirty set waits, then refuses without recording; changes to waiting entities still merge; a callback never waits for its own pass | DirtySetBoundTest |
| The entry bound evicts the least recently used clean entries and never unsaved work | EntityCacheTest.theEntryBoundEvictsTheLeastRecentlyUsedCleanEntriesOnly / theEntryBoundNeverEvictsUnsavedWorkEvenWhenThatLeavesItOver |
| Unthrottled producers against a real MySQL stay bounded and lose nothing | DatabaseIntegrationTest.unthrottledProducersAreBoundedAndEveryAcceptedChangeIsStored |
| Callback close rejected without deadlock | DataContractTest.closeFromCallbackIsRejectedWithoutDeadlock |
| Log partitions, drain, partition creation | LogContractTest.closeDrainsPartitionsAndBatchesWithoutSchemaInitialization / missingPartitionTableIsCreatedFromTheBaseTable |
| Each SchemaMode's permitted statements, the length check, and a startup that loses the CREATE, ADD COLUMN or CREATE INDEX race | MysqlSchemaModeTest |
| Mongo update of a missing document fails | MongoMappingTest |
| State and logs are separate stores; neither is derived from the other | StoreBindingTest.logsNeverFallBackToTheStateSource / separateSourcesKeepLogRowsOutOfTheStateDatabase |
| MongoDB state and MySQL logs in one GameData | StoreBindingTest.mongoStateAndMysqlLogsAreIndependentStores |
| Naming a backend replaces an implicit registration; other repeats stay duplicates | StoreBindingTest.namingABackendReplacesAnEarlierImplicitRegistration / repeatsThatAreNotRedirectionsStayDuplicates |
| `update(key, change)` loads, changes and records in one call | DataContractTest.updateWithAChangeLoadsChangesAndRecordsInOneCall |
| Tables are saved in parallel, one table never by two threads; drops reach the handler one at a time; an outage stops every table of that storage | ParallelSaveTest |
| A pass stops after two retryable failures; close during an outage ends within its timeout and hands over everything | StorageOutageTest |
| Every instance field is a column without annotations; unsafe collections are named at startup | MysqlMappingTest.everyInstanceFieldIsAColumnWithoutAnnotations / collectionsTheSaveThreadCannotReadSafelyAreNamedAtStartup |
| Multi-row UPDATE: statement shape and arguments, 100-row statements, fewer rows for wide tables, a missing row failing with 02000 | MysqlMappingTest.multiRowUpdatesListEveryRowOnceAndFailTheBatchWhenOneIsMissing |
| Multi-row UPDATE on a real MySQL, and a missing row failing the whole statement with 02000 | DatabaseIntegrationTest.mysqlServerSchemaRoundTripAndRollback |

Verified with in-memory Mappers, H2 in MySQL mode and a recording MongoAccess. [DatabaseIntegrationTest](../../game-data/src/test/java/cn/managame/data/DatabaseIntegrationTest.java) runs against real servers when `OGBS_DATA_MYSQL_URL` / `OGBS_DATA_MONGO_URI` are set and is skipped otherwise; the CI `database` job sets both against MySQL 8.4 and MongoDB 7 service containers and fails if the tests are skipped. Live MySQL is verified for schema round trips, multi-row UPDATE and the bounds under a flood of unthrottled producers ([load test](../reviews/2026-10-18-data-save-path-load-test.md), [multi-row UPDATE and per-table parallelism](../reviews/2026-10-21-data-save-path-parallel.md)); MongoDB behaviour, production performance and long outages are unverified.

<a id="11-explicit-flush-and-datastats"></a>
<a id="11-显式-flush-与-datastats"></a>

## 10. Explicit flush and DataStats

`GameData.flush()` runs one pass synchronously: every change accepted before the call is saved, while changes written concurrently may wait for the next pass. It throws DataOperationException when closed or called from a persistence callback, and DataSaveException when this pass dropped a change (already reported to DataErrorHandler) or retryable failures remain kept (those stay pending). A drop in an earlier pass does not fail a later clean flush; close still throws DataSaveException for any drop during the GameData's lifetime. Avoid a service-wide flush on a busy game Route.

`GameData.stats()` returns DataStats: accepting, pendingChanges (pending entries, including kept failures; changes claimed by a running pass are not counted), queuedLogs, failedBatches (dropped changes), flushes (passes, including those during close), saveNanos, singleCacheEntries (including cached absence) and groupCacheEntries (groups, not entities) droppedLogs (logs refused because their queue was full) and rejectedChanges (state changes refused because the dirty set stayed full; never accepted, so not drops). Values are snapshots; pendingChanges and queuedLogs are kept by counters and are O(1).
