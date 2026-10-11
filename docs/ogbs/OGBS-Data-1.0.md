# OGBS Data Specification 1.0

**[English](OGBS-Data-1.0.md)** | [简体中文](OGBS-Data-1.0.zh-CN.md)

Document type: **Language-independent specification**. Companion: [Data Java specification](OGBS-Data-Java-25-Specification-1.0.md).

OGBS = Open Game Backend Specification.

Status: repository draft with Java 25 reference implementation `game-data`. Java types, annotations, threads, and caching belong in the [Java specification](OGBS-Data-Java-25-Specification-1.0.md). [Core](OGBS-Core-1.0.md#4-frameworkerrorcode) alone defines shared error codes.

<a id="1-职责与边界"></a>

## 1. Responsibilities and boundaries

**D-MODEL-01** Data manages mutable game-process state through Single, Group, and append-only Log repositories. Cache misses load synchronously. Changes take effect in memory first; explicit insert/update/delete records asynchronous batch persistence. update saves complete mutable persistent state, not field differences.

No ORM Session, proxies, automatic dirty tracking, entity snapshots, rollback, relationships, generated primary keys, arbitrary query DSL, distributed transactions, cross-process cache coherence, or Redis L2 cache. Use native database access for specialized queries.

**D-OWN-01** Applications serialize business modifications of the same entity/group. Thread-safe containers do not make fields thread-safe. Applications ensure visibility between business writes and background field reads, plus required multi-field consistency. Pending references may continue changing; persistence observes encoding-time state, not an update-time snapshot.

<a id="2-身份"></a>

## 2. Identity

**D-ID-01** Single entities have exactly one persistent primary key. Group entities have exactly one primary key, at least one group-key field, and at least one in-group-key field. Group plus in-group key identifies the business position; background coalescing/update/delete always uses the primary key, unique throughout that entity's storage.

**D-ID-02** Primary, group, and in-group keys cannot change after load/insert. No snapshots, setter interception, or runtime identity comparisons are required; violations have unspecified results. Equal primary keys of different entity types never coalesce.

**D-ID-03** Multi-field group/in-group keys require stable distinct orders, not necessarily contiguous. Single-field in-group keys use the original value. Multi-field in-group keys join ordered decimal integers/simple strings with `:`, without escaping or length encoding. Components cannot be null; composite in-group string components cannot contain `:`. Group keys retain typed components rather than stringify them.

<a id="21-三种身份不能混用"></a>

### 2.1 Three distinct identities

Player-task example:

| Field | Example | Purpose |
| --- | --- | --- |
| Persistent primary key | id = 90001 | Unique database row, pending coalescing, update/delete |
| Group key | roleId = 42 | Load every task for player 42 |
| In-group key | taskId = 7 | Locate a task within that group's Map |

Another player may have taskId=7 but not primary key 90001. GroupRepository K is the in-group key, neither primary key nor whole GroupKey. Composite group keys retain type/order; composite in-group strings follow D-ID-03 without another encoding scheme.

Moving a task from player A to B cannot mutate an existing roleId. Explicitly delete the old identity and create the new one, accepting that the two operations have no cross-group atomic transaction.

## 3. Single

**D-SINGLE-01** Cache hits return the same entity reference. Absence returns an empty result and may be negatively cached. Storage failure must synchronously report load failure, never absence or negative caching.

**D-SINGLE-02** insert declares a new entity without an extra existence SELECT. update saves all mutable state. Both immediately update cache and record change without waiting for the database. Background error handling reports storage conflicts.

**D-SINGLE-03** delete needs no prior load. Once accepted, cache immediately shows absence; while that cached state remains valid, get must not reload the not-yet-deleted old row.

## 4. Group

**D-GROUP-01** Load a complete group by its complete key and build a Map by in-group key. Zero rows means a loaded empty group. Load failures, duplicate in-group keys, and foreign-group rows are load errors.

**D-GROUP-02** Before insert/update/delete/deleteGroup, the group must still be cached. Unloaded/expired groups reject synchronously without implicit database access. delete accepts an entity to obtain complete identity. deleteGroup records primary-key DELETE for each cached member, then clears the original Map; the empty group remains loaded.

**D-GROUP-03** getGroup returns the actual cached Map reference. Applications may iterate/read entities and modify fields, but must not put/remove/clear its structure directly. Structural changes use Repository; field persistence requires explicit update.

<a id="41-操作前提与即时效果"></a>

### 4.1 Preconditions and immediate effects

| Operation | Synchronous database load | Accepted in-memory effect | Persistence intent |
| --- | --- | --- | --- |
| Single.get | On cache miss | Cache entity or absence | None |
| Single.insert / update | No | Cache the supplied object | INSERT / UPDATE, then coalesce |
| Single.delete | No | Cache absence | DELETE |
| Group.getGroup / get | Entire group on miss | Cache actual Map, including empty | None |
| Group.insert / update | No; reject uncached group | Write in-group position | Record by entity primary key |
| Group.delete | No; reject uncached group | Remove in-group position | Primary-key DELETE |
| Group.deleteGroup | No; reject uncached group | Clear original Map, still loaded | DELETE each current primary key |
| Log.insert | No | Append to log queue | Later INSERT by physical table |

Loaded means current cache state, not historical getGroup usage. Holding an expired Map locally does not replace fetching the current group. A loaded empty group accepts its first insert; a never-loaded group does not.

<a id="42-返回原-map-的使用约定"></a>

### 4.2 Using the original Map

Returning the original Map is a confirmed choice for direct entity access without extra views/wrappers. It requires these structural boundaries:

- Iteration and in-group lookup are allowed.
- Mutable field changes are allowed with explicit update and visibility discipline.
- Direct put is prohibited because it records no INSERT.
- Direct remove/clear is prohibited because it records no DELETE.
- Map thread safety does not authorize concurrent external entity mutation.

deleteGroup clears the original Map, so holders observe it empty. It covers known cached members, not an arbitrary database predicate deletion. Rows written outside Repository are outside that guarantee.

<a id="5-缓存与保存生命周期"></a>

## 5. Cache and persistence lifecycle

**D-CACHE-01** Cache and pending changes are independent. Expiry never discards registered changes or forces persistence through eviction listeners. Repository access renews expiry; direct access through old Map/entity references does not. Fetch the current cached object through Repository for each business access.

**D-CACHE-02** A cache miss must first consult the entity's unsaved changes (including those being saved); when present, the in-memory change wins and storage older than memory must not be read. A Group load overlays the group's unsaved changes on the stored rows. Cache expiry therefore never rolls back unsaved modifications. Cache lifetime should still greatly exceed normal write delay to avoid repeated loads.

<a id="51-读取修改与保存是三个时点"></a>

### 5.1 Read, modify, and save are different moments

```text
T1 get returns cached Entity
T2 business changes fields and calls update
T3 persistence thread reads fields, encodes, and submits to database
```

T2 registers intent. T3 may see later values; V1 takes no update-time snapshot. Several updates may coalesce into one database write, so intermediate states need not be observed. Append logs when every action must be retained.

Distinguish absence from load failure: zero rows may produce negative cache; connectivity, decoding, or invalid identity reports failure. Caching failures as absence may provoke incorrect entity creation.

<a id="52-缓存过期与故障的组合"></a>

### 5.2 Expiry combined with failure

The pending set holds entity references independently, so expiry never cancels a save. While storage is unavailable, retryable changes stay pending; reading after expiry returns the same entity from the pending set instead of an older stored value.

Changes that are finally dropped (section 7) no longer overlay: storage keeps its previous state, which is what a read after expiry returns. Every such drop goes through the error Handler so the application can repair it.

## 6. Write-back and coalescing

**D-WRITE-01** Coalesce only changes with the same entity metadata and primary key that the persistence pipeline has not yet claimed. Updates retain the latest supplied entity reference; DELETE keeps only the primary key (Group deletes also keep the entity to identify its group). A claimed change no longer merges with new changes; only when a retryable failure returns it does it merge as the older side.

| Current | New operation | Result |
| --- | --- | --- |
| None | INSERT / UPDATE / DELETE | New operation |
| INSERT | UPDATE | INSERT |
| INSERT | DELETE | No pending change |
| UPDATE | UPDATE | UPDATE |
| UPDATE | DELETE | DELETE |
| DELETE | INSERT | DELETE_INSERT |
| DELETE | DELETE | DELETE |
| DELETE_INSERT | UPDATE | DELETE_INSERT |
| DELETE_INSERT | DELETE | DELETE |

Other combinations reject synchronously without replacing cache with the new object. INSERT→DELETE cancellation trusts the declaration that the entity is new; it does not inspect existing storage.

**D-WRITE-02** Only one persistence pipeline runs at a time. Each pass claims every change pending when it starts and finishes before the next pass; an older change for a primary key always executes before a newer one. Within each entity type execute DELETE → DELETE_INSERT → INSERT → UPDATE, split by batchSize, each batch taking effect atomically in storage. No business transaction or atomicity spans entity types.

**D-WRITE-03** Interval is a target start interval. If a pass exceeds it, the next may start immediately without another full wait. Claiming must be an atomic conditional removal: a change recorded after its claim stays for the next pass and is never lost to a race between claiming and clearing. Implementations must not rely on a fixed wait to ensure this.

<a id="61-合并示例与拒绝理由"></a>

### 6.1 Merge examples and rejection rationale

Same entity and primary key, before the pipeline claims the change:

```text
insert(A) → update(B)       => one INSERT using B
insert(A) → delete(id)      => no database operation
update(A) → update(B)       => one UPDATE using B
update(A) → delete(id)      => one DELETE retaining only id
delete(id) → insert(B)      => one DELETE_INSERT using B
delete(id) → insert(B) → delete(id) => one DELETE
```

Repeated INSERT, UPDATE→INSERT, DELETE→UPDATE, and other unlisted sequences reject synchronously, preserving distinct create/modify/replace intent rather than implicit save. Rejection does not replace cache, but cannot undo fields changed directly before the call.

DELETE_INSERT is delete-and-recreate intent with backend-specific implementation in §8. It is not a public Repository method or universal upsert.

<a id="62-跨缓冲与跨批次"></a>

### 6.2 Across passes and batches

If an insert has been claimed by the current pass and a delete is recorded afterwards, they do not cancel: the INSERT is saved, then the DELETE runs in the next pass. Even with the same final database state, this can cause two real I/O operations and two failure opportunities.

Phase order inside an entity is not a transaction across entities. Player gold UPDATE and reward log INSERT may succeed or fail separately; placing them in one GameData does not provide atomic commit. Batch size only controls Mapper input size and does not imply a whole-pass transaction boundary.

## 7. Errors, retries, and closure

**D-ERROR-01** Background state/log failures share one error mechanism carrying error code, entity/log type, operation, failed data, cause and attempt. A retry policy classifies failures first: retryable (transient faults such as lost connections, pool timeouts, lock wait timeouts, deadlocks) changes stay for the next pass without an attempt limit; the default policy treats only such transient faults as retryable.

**D-ERROR-02** A non-retryable multi-row batch must be split into single rows so the other rows still persist; only a non-retryable single change is dropped, reported to the error Handler, and later work continues, so saving can never block permanently. Handler/policy failures must be isolated and diagnosed. Entities are not rolled back. Storage adapters must apply each batch atomically (transaction or idempotent upsert) so retries and splitting cannot duplicate rows after partial success. An UPDATE whose target row does not exist is a non-retryable failure and must not silently succeed.

**D-ERROR-03** No failure snapshot is made on the normal path. The Handler borrows dropped entity references synchronously; asynchronous archiving must serialize/copy before the callback returns. The default Handler only records diagnostics and does not replace business failure-data archiving.

**D-CLOSE-01** close is a synchronous final barrier: reject new state/log changes, stop periodic scheduling, await ongoing persistence, then keep retrying kept changes within a bounded shutdown timeout. After the timeout one final pass treats retryable failures as non-retryable (split, drop, report), and close then releases its scheduling resources. Stop business ingress before close.

**D-CLOSE-02** Any drop during the lifecycle makes close report save failure; an empty queue does not mean persistence succeeded. close is idempotent and repeated failed closes still report failure. Database driver timeouts are application configuration.

**D-CLOSE-03** A process crash can lose data not yet written, including kept retryable changes. V1 provides no WAL or crash recovery.

### 7.1 From failure to final handling

```text
execute the current batch (atomically)
  success → continue
  retryable failure → put every change back into the pending set for the next pass
  non-retryable failure:
       single row → drop, call the error Handler, record the failure
       several rows → execute row by row; each row's failure is classified again
```

Kept changes are deduplicated by primary key, so they never exceed the number of dirty entities, regardless of update count. Returning changes merge with newer ones using the merge table; an illegal sequence keeps the newer change and drops and reports the older one as non-retryable.

### 7.2 Error Handler responsibility

The error Handler receives only dropped changes. It can alert, synchronously serialize to external failure storage (for example a local dead-letter file), or record information needed for manual recovery. Entity references remain the original mutable objects; independent failure records must copy or serialize before the callback returns. Handler exceptions cannot block later saves.

### 7.3 Successful close versus successful persistence

close first stops mutation admission, then processes accepted work. If storage recovers within the shutdown timeout, kept changes are saved; otherwise they are reported through the error Handler, dropped, and close reports failure. A previous drop still makes close fail even with an empty queue.

close does not stop upstream Runtime or network ingress and does not close external database clients. Stop business production and tasks that may still call Repositories, then close Data. Forced process termination has no normal close guarantee.

### 7.4 Explicit flush and diagnostics

**D-FLUSH-01**: Explicit flush synchronously runs one pass that claims every change accepted before the call, keeping Data open. Concurrent writes are not guaranteed to be included. A historical drop, or retryable changes still kept after the pass, makes flush report failure (kept changes wait for the next pass). Recursive flush from persistence/error callbacks is rejected to avoid deadlock. Driver timeouts remain application configuration.

**D-DIAG-01**: Pending-change, queued-log, terminal-failed-batch and cache counts are approximate observations, not durable acknowledgements or memory limits. Group cache counts identify cached groups, not entities. Flush success confirms only the covered mapper operations, not a distributed transaction or protection against process loss before earlier write-back.

Changing an entity still requires update; direct mutation of a returned Group Map still bypasses persistence. Automatic dirty tracking, immutable save snapshots and cache-capacity eviction are not added. A business-critical operation can quiesce its writers and flush from a management context, while ordinary gameplay stays asynchronous. See [DataContractTest](../../game-data/src/test/java/cn/managame/data/DataContractTest.java).

<a id="8-存储适配语义"></a>

## 8. Storage adapter semantics

**D-STORE-01** JSON fields have a default codec and require no per-field type registration. Initialization binds the declared complete value/container types and, for state fields with non-null constructor-initialized values, the compatible concrete implementation type. Default decoding must preserve that bound implementation and generic key/value/element types rather than silently substituting another implementation. Null initial values use the declared type. Null stored values remain null. Types unsupported by the default implementation report mapping/encoding/load errors; applications may explicitly replace the codec. Custom codecs control representation and must return a value assignable to the field. Raw JSON text fields retain their pass-through behavior. Malformed JSON is a load failure, never absence, and write encoding failures follow persistence failure handling.

For example, a declared Map<Integer,Long> initialized with a concurrent map must load as that concurrent map with integer keys and long values, without a manual type registry. The initializer is observed once during state mapping, not by guessing types from database content or retaining a prototype as business state. Log fields use declared type information without constructing log objects: logs have no load path or required no-argument constructor. Implementation specifications define concrete codec configuration and supported class-construction limits.

State Mappers compile mappings and required structures during initialization. One Mapper may serve several entity metadata objects without mixing equal keys across types.

MySQL: INSERT includes explicitly persistent fields; UPDATE saves all mutable fields excluding every identity field; DELETE uses primary key. DELETE_INSERT uses a small transaction covering batch deletion/insertion. Schema maintenance creates missing tables, adds fields/explicit indexes, but never drops fields/indexes or changes types. Obvious type/primary-key/same-name-index conflicts fail initialization. Group keys do not imply indexes.

MongoDB: primary key maps to _id. INSERT is bulk insertion; UPDATE is full Document replacement without upsert; DELETE_INSERT is replacement upsert; DELETE uses primary-key sets. Create only missing collections and explicit ascending indexes; no SQL field migration.

<a id="9-追加日志"></a>

## 9. Append-only logs

**D-LOG-01** Log supports only insert into an append queue, with no caching/coalescing/state EntityMapper/required state primary key. Accepted log fields, including partition fields, must not change.

**D-LOG-02** V1 logs use MySQL via MysqlAccess without table/field/index maintenance. External log services manage schemas. Group by physical table before batch INSERT. Each periodic round uses a finite queue boundary so continuous production cannot block state persistence. Closure drains all accepted logs.

**D-LOG-03** Partitioning supports VALUE, DAY, MONTH, YEAR and at most one partition field. Without one, use the base table. Names are base + underscore + suffix. Time partitioning uses event time in an explicit zone; units/default zone/types are in the Java specification.

<a id="91-日志完整流程"></a>

### 9.1 Complete log flow

```text
Create log and fix event time/partition value
→ validate and accept insert
→ append queue
→ obtain finite round boundary
→ drain batches and group by physical table
→ INSERT each group
→ success; retryable failure back to queue tail; non-retryable split, dropped and sent to the same error Handler
```

Identical logs never coalesce. DAY/MONTH/YEAR use event time, not persistence-thread time; delayed cross-day logs still go to the original date's table. External systems prepare tables; missing tables enter normal save-failure handling.

After insert returns, no log field may change, including nonpersistent partition fields. Without snapshots, mutation can change both content and destination and destroys reliable acceptance-time event records.

<a id="92-已确认取舍与扩展边界"></a>

### 9.2 Confirmed tradeoffs and extension boundaries

| Choice | Rationale and consequence | Revisit when |
| --- | --- | --- |
| Explicit insert/update/delete | Clear intent, no proxies/dirty tracking | Automatic tracking or field patches needed |
| Mutable entities and actual group Map | Direct memory access; Repository owns structural changes | Isolated views, immutable objects, snapshots needed |
| Coalesce by EntityMeta and primary key | Shared Mappers without cross-type confusion | Identity model/dynamic mapping changes |
| Independent cache/persistence; misses overlay unsaved changes | Eviction does not own saving and never rolls back | Multi-process strong consistency needed |
| Keep transient failures, split and drop bad rows | Storage outages lose no data; one bad row neither drops others nor blocks forever | Durable failure queues or backoff needed |
| Separate append logs, shared errors | Preserve events without state coalescing | Log queries/other backends needed |
| No log Schema maintenance | Separate external log-management responsibility | Unified schema management needed |

No WAL, crash recovery, unbounded-backlog protection, cross-table transactions, or multi-process consistency is promised. Such extensions need explicit behavior, capacity and recovery semantics, not just replacement containers.

<a id="10-验证与已知范围"></a>

## 10. Validation and known scope

Contract tests: [DataContractTest](../../game-data/src/test/java/cn/managame/data/DataContractTest.java), [LogContractTest](../../game-data/src/test/java/cn/managame/data/LogContractTest.java). Database mapping, JDBC transaction, and real-service test entries: [module README](../../game-data/README.md).

Implementation is not production certification. Deployments evaluate cross-thread mutable reads, unbounded queues and driver timeouts; live database validation status is maintained in the Java implementation specification.
