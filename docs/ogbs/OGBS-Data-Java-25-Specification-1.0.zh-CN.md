# OGBS Data Java 25 Development Specification 1.0

[English](OGBS-Data-Java-25-Specification-1.0.md) | **[简体中文](OGBS-Data-Java-25-Specification-1.0.zh-CN.md)**

文档类型：**Java 开发规范**。行为由 [OGBS Data 规范](OGBS-Data-1.0.zh-CN.md) 定义；本文定义 Java API、默认值、异常、线程与验证。

状态：Java 25 参考实现，Maven `cn.managame:game-data:1.0.0-SNAPSHOT`。

## 1. 模块与公共入口

一个 artifact 包含 Repository、缓存、写回、MySQL/JDBC、MongoDB 与日志。依赖 game-core 与 Jackson Databind 2.21.3（默认 JSON）。MongoDB Sync Driver 5.5.1 为可选依赖，使用 Mongo 的应用自行加入。JDBC 使用应用提供的 DataSource 和 MySQL 驱动；Data 不选择连接池。

| 包 | 职责 |
| --- | --- |
| cn.managame.data | GameData、GameDataBuilder、三种 Repository 基类；包级 EntityCache、PendingBuffer、WriteBehindManager |
| annotation / key | 身份注解、GroupKey、MapKeys |
| meta / mapper | EntityMeta（供存储 SPI 使用的身份元数据）、EntityMapper |
| codec / error | JSON/BINARY codec、异常、DataFailure、RetryPolicy、DataErrorHandler |
| mysql | SQL 注解、MysqlAccess、JdbcMysqlAccess、MysqlEntityMapper、日志映射 |
| mongo | BSON 注解、MongoAccess、DriverMongoAccess、MongoEntityMapper |

## 2. Repository API

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

业务 Repository 直接继承一个具体参数化的基类，并有无参构造器；不支持中间泛型 Repository。由 `build` 创建，通过 `data.repository(Players.class)` 获取。每种实体只能有一个状态 Repository。Single 的 K 为 Id 的包装类型；Group 的 K 为 MapKey 的包装类型，复合 MapKey 时为 String。Repository 只由 Builder 初始化；自定义后端请扩展 EntityMapper。

<a id="21-repository-直接持有依赖"></a>
<a id="22-读写路径"></a>

### 2.1 缓存与修改路径

每个 Repository 持有一个 EntityCache：Single 按 id 缓存实体或已确认的不存在（null）；Group 按 GroupKey 缓存实际的 `ConcurrentHashMap<K,E>`。只有 get/getGroup 会加载；缺失的 Key 只加载一次，同 Key 的并发调用等待该次加载。加载失败抛 DataLoadException，不会缓存为不存在。

修改先校验状态与身份，进入准入读锁，然后在该 Key 的 `EntityCache.update` 中执行：Group 检查分组仍在缓存中，`writer.record` 合并意图（非法序列抛异常，缓存不变），再修改缓存条目或分组 Map。登记在缓存的单 Key 原子操作内完成，这是淘汰安全的前提（见 5.1）。登记不是字段事务：调用前直接修改的字段不会回滚。deleteGroup 为每个当前成员登记按主键 DELETE，再清空原 Map。

Spring 接入属于应用代码：[game-demo 适配](../../game-demo/src/main/java/cn/managame/demo/common/data/GameDataConfig.java) 把扫描到的单例 `@Repository` Bean 注册到 Builder，并以 `data.repository(type)` 作为 Bean 实例（见 [DataRepositoryRegistrationTest](../../game-demo/src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java)、[DataSpringWiringTest](../../game-demo/src/test/java/cn/managame/demo/DataSpringWiringTest.java)）。

## 3. 构建与生命周期

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

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| cacheExpire | 30 分钟 | 干净条目空闲多久后淘汰；须大于 flushInterval |
| flushInterval | 1 秒 | 两轮保存之间的目标间隔 |
| batchSize | 500 | 每批行数 |
| shutdownTimeout | 30 秒 | close 持续重试可重试失败多久，之后丢弃并报告 |
| retryPolicy | RetryPolicy.transientFailures() | true 保留到下一轮；false 拆分、丢弃并报告（见第 5 节） |
| errorHandler | System.Logger | 接收被丢弃的变更，在此归档 |
| partitionZone | UTC | DAY/MONTH/YEAR 日志分表使用的时区 |
| mysql(DataSource) | 无 | 未显式指定后端的 Repository 借用的 DataSource |
| jsonCodec / binaryCodec | 默认 Jackson / 无 | 隐式 MySQL Mapper 及日志（除非 logCodecs 覆盖）使用的 codec |
| logCodecs(json, binary) | 继承 | 仅日志使用的 codec；JSON 传 null 表示默认 |

repositories(...)、logRepositories(...) 接受类或列表；显式传入 EntityMapper 或 MysqlAccess 的重载不使用 mysql(DataSource)。存在未指定后端的注册但没有 DataSource 时，build 抛 IllegalArgumentException。build 先校验 Repository、身份与日志映射，再执行各状态 Mapper 的 initialize（映射与 Schema；后续步骤失败时已执行的 DDL 不回滚），最后创建缓存并启动一个保存线程。

GameData 只关闭自己的线程，从不关闭 DataSource、MongoClient 或 Mapper。close 可能等待数据库调用；在 Mapper、RetryPolicy、DataErrorHandler 回调中调用 close 或 flush 会被拒绝，以免死锁。关闭后所有 Repository 操作抛 DataOperationException。

## 4. Entity 与身份元数据

支持继承字段；注解标在非 static、非 final 字段上。状态实体需要可访问的无参构造器；加载时通过 VarHandle 直接写字段（JPMS 下需开放包）。身份字段为 byte/short/int/long、其包装类型或 String，不能为 null。复合 Key 需要显式且互不相同的 `order`。`GroupKey.of(Object...)` 不复制数组（不要修改它），按类型比较值：`GroupKey.of(42L)` ≠ `GroupKey.of(42)`。`MapKeys.of(a, b, …)` 用 ':' 连接至少两个整数/String 值，值内不能含 ':'。

```java
public interface EntityMapper {
    void initialize(EntityMeta meta);
    Object load(EntityMeta meta, Object id);
    List<?> loadGroup(EntityMeta meta, GroupKey key);      // 非 null，无数据时为空列表
    void insertBatch(EntityMeta meta, List<?> entities);
    void updateBatch(EntityMeta meta, List<?> entities);
    void deleteBatch(EntityMeta meta, List<Object> ids);
    void deleteInsertBatch(EntityMeta meta, List<?> entities);
}
```

EntityMeta 只包含身份与字段访问；存储规则归各 Mapper（只有 @Id 没有 @Column 是合法身份，但 MySQL 映射会拒绝）。自定义 Mapper 必须区分实体类型，不能把加载失败当作不存在，每批原子执行，并保留原因异常。

## 5. 写回实现与适用边界

PendingBuffer 为每个 EntityMeta 保存一个 `ConcurrentHashMap<id, Change>`；Change 持有操作与最新实体引用，按对象身份比较。`record` 用一次 `compute` 按标准中的合并矩阵合并。每轮保存以条件删除 `pending.remove(id, change)` 认领条目：认领之后的登记会新建条目留给下一轮，因此遍历与删除之间不会丢失变更；单一保存线程保证同一 id 的旧变更先于新变更执行。

失败处理。Mapper 每批原子执行（MySQL 一个事务，Mongo 按 `_id` 替换），失败的批次不留部分行：

1. RetryPolicy 返回 true（默认：连接丢失或被拒、连接池超时、I/O、锁等待超时 1205、死锁 1213、SQLState 08xxx/40xxx、Mongo 网络/超时/主节点切换）：整批合并回新变更之下，下一轮重试，无次数上限。保留的变更数不超过脏实体数。
2. 不可重试且多于一行：拆成单行逐个执行，其他行照常保存。
3. 不可重试的单行：丢弃，计入 failedBatches，同步报告给 DataErrorHandler（attempt 1 表示原本就是单行批次，2 表示拆分后隔离出的行）；之后 flush/close 抛 DataSaveException。
4. 合并回去时与新变更冲突（旧 DELETE 后接 UPDATE）：保留新变更，旧变更丢弃并报告。

MySQL UPDATE 未匹配任何行时按不可重试单行失败（保持 Connector/J 默认 `useAffectedRows=false`）；Mongo 更新的文档不存在时同样失败。

保存线程读取实体时不做快照。业务代码会修改的实体内集合必须线程安全；需要一致的多个字段应在同一次 update 前一起修改。没有 WAL：进程崩溃最多丢失一个保存间隔加上被保留的失败变更。

### 5.1 缓存淘汰

只有空闲超过 cacheExpire 且没有待保存变更的条目才会被淘汰。淘汰在保存线程上、一轮保存之后进行，最多每 max(flushInterval, cacheExpire/4) 一次，此时没有已认领但未完成的变更。Single 检查 `pending.containsKey(id)`；Group 每次清理时收集待保存变更所属的 GroupKey（分组删除因此保留实体）。清理先读取时钟再构建该集合，并在单 Key 原子操作内重新检查空闲时间：集合漏掉的修改一定发生在读取时钟之后，已刷新其条目访问时间，条目会被保留。读取最多每 min(cacheExpire/16, 1 秒) 刷新一次访问时间，避免多线程读同一热点实体时反复写同一缓存行；因此淘汰等待 cacheExpire 再加上该间隔。因此加载从不查询缓冲：缺失的 Key 没有待保存变更，存储就是最新的。存储故障期间脏实体一直留在缓存中，直到保存成功或被丢弃。

### 5.2 一轮保存

```text
对每个 EntityMeta：
  认领全部待保存条目（条件删除）
  按 DELETE → DELETE_INSERT → INSERT → UPDATE 分组，按 batchSize 拆批；保存或按失败分类处理
日志：有限排空，按分表批量插入，失败按同样规则分类
随后（到期时）淘汰空闲的干净缓存条目；下一轮在 max(0, interval − 本轮耗时) 后开始
```

周期保存、显式 flush 与 close 从不并发执行 Mapper 批次。

### 5.3 RetryPolicy、错误与关闭

RetryPolicy 只在“保留到下一轮”和“拆分/丢弃”之间选择；没有立即重试或退避线程。RetryPolicy 抛异常时按不可重试处理。DataErrorHandler 只接收被丢弃的变更，在保存线程上运行，例如写入本地死信文件。可重试失败每轮记录一条 WARNING。

准入读锁让关闭与进行中的修改有序。close 获取写锁切换到 CLOSING，停止调度，反复保存直到没有待保存变更或超过 shutdownTimeout，然后执行最后一轮：可重试失败也被丢弃并报告。生命周期内任何丢弃都会让 close 抛 DataSaveException，重复调用报告同一失败。永不返回的数据库调用也会阻塞 close；请在连接池或驱动中设置超时。

## 6. MySQL 映射

```java
@Table(name = "player_task", indexes = { @Index(name = "idx_role_task", columnList = "role_id, task_id") })
public class Task {
    @Id @Column private long id;
    @cn.managame.data.annotation.GroupKey @Column private long roleId;
    @MapKey @Column private long taskId;
    @Column private int progress;
}
```

只有 @Column 字段持久化；Table.name 必填。名称默认 snake_case，使用 ASCII 字母、数字与 '_'（≤64 字符），以反引号引用；过长的生成索引名带稳定 hash 截断。

| Java（DEFAULT） | MySQL | 默认值 |
| --- | --- | --- |
| byte / short / int / long | TINYINT / SMALLINT / INT / BIGINT | 0 |
| float / double / boolean | FLOAT / DOUBLE / TINYINT | 0 |
| char / String | CHAR(1) / VARCHAR(255) | '' |
| byte[] | BLOB | 无 |

基本类型与身份列为 NOT NULL。TEXT 只接受 String。JSON 对 String 按原始 JSON 保存，其他类型经 JSON codec；BINARY 直接保存 byte[]，其他类型需要 BinaryCodec；DEFAULT 拒绝复杂对象。`Column.defaultValue` 为受信任的原始 DDL。

初始化时编译列顺序、访问器、codec 与 SQL。INSERT 写全部列，不预先 SELECT；UPDATE 按主键写非身份列（只有身份列的实体跳过）；DELETE 按主键；DELETE_INSERT 先编码，再在一个事务中删除并插入。Schema 初始化读取 information_schema，只补充缺失的表、列与显式索引；检查基本类型、单列主键与显式索引形状，不检查 VARCHAR 长度、默认值与可空性。GroupKey 不隐含索引。

MysqlAccess 提供 queryOne（无行返回 null，多行抛 MysqlException）、query、update、batchUpdate 与 transaction（单连接，成功提交，异常回滚，只在回调线程内有效）。JdbcMysqlAccess 每次操作获取一个连接；MysqlException 保留 SQL、原因、`sqlState()` 与 `vendorCode()`。此层不自动重试。

<a id="default-json-field-binding"></a>

### 6.1 默认 JSON 字段绑定

`JsonCodec.defaultCodec()` 是共享的默认 Jackson 配置。状态实体含非 String 的 JSON 列时，初始化构造一个临时实体，把每个字段的解码器绑定到其声明的泛型类型以及初始化值的类：用 `new ConcurrentHashMap<>()` 初始化的 `Map<Integer,Long>` 字段加载后仍是 ConcurrentHashMap<Integer,Long>。构造器与初始化值必须确定且不获取外部资源。初始化值为 null 时使用声明类型；原始类型无法恢复泛型；不启用 default typing。JSON 格式错误或有多余 token 时抛 IllegalArgumentException，加载时报告为 DataLoadException 且不做负缓存。自定义 codec 可覆盖 `decoder(Type)` 或 `decoder(Type, Class<?>)`。源码与测试：[MysqlFieldMeta](../../game-data/src/main/java/cn/managame/data/mysql/MysqlFieldMeta.java)、[MysqlMappingTest](../../game-data/src/test/java/cn/managame/data/mysql/MysqlMappingTest.java)、[MysqlBuilderTest](../../game-data/src/test/java/cn/managame/data/MysqlBuilderTest.java)。

## 7. MongoDB 映射

Collection.name 必填；只有 @Field 字段持久化；id 固定为 `_id`。MongoIndex 使用数据库字段名，全部升序。MongoEntityMapper 接受 MongoDatabase 或 MongoAccess；DriverMongoAccess 使用数据库的 CodecRegistry（标量、byte[]、集合、以 String/Integer/Long 为键的 Map；其他类需注册 codec）。加载使用无参构造器；缺失字段保留构造器默认值。

| 意图 | MySQL | MongoDB |
| --- | --- | --- |
| INSERT / DELETE_INSERT | INSERT / 删除加插入事务 | 有序批量替换，upsert |
| UPDATE | 非身份列；0 行时失败 | 有序批量替换，不 upsert；文档不存在时失败 |
| DELETE | 按主键 | `_id $in` |
| Schema | 表、列、显式索引 | collection、显式升序索引 |

`MongoAccess.replaceMany` 返回匹配到的（已存在的）文档数；少于批次大小时 MongoEntityMapper 让更新失败。替换不是 `$set`：映射之外新增的字段不会保留。有序批量写不是事务；失败批次中靠前的文档可能已被替换，重试时会再次覆盖。

## 8. Log

LogRepository 不需要 Id 或无参构造器，也不使用 EntityMapper。@Table/@Column 编译为 INSERT。最多一个 @PartitionKey：VALUE 接受整数或 String（后缀为 ASCII 字母、数字、'_'）；DAY/MONTH/YEAR 接受 epoch 毫秒（long），按 partitionZone 渲染为 uuuuMMdd/uuuuMM/uuuu。没有分表键时使用基础表。

insert 校验类型与分表后直接入队，不复制；插入后不要再修改日志对象。每轮排空有限数量，按物理表分组，每组在一个事务中插入；失败按第 5 节分类（可重试的日志回到队尾）。基础表由外部管理。插入因分表不存在（MySQL 1146 / SQLState 42S02）失败时，执行 `CREATE TABLE IF NOT EXISTS 分表 LIKE 基础表` 并重试一次。close 会排空全部已接纳的日志。

## 9. 异常与验证

DataLoadException：存储加载失败或加载数据非法。DataOperationException：未初始化、已关闭、分组未加载、非法变更序列、Mongo 更新不存在的文档。DataSaveException：有变更被丢弃（由 flush/close 报告）。配置与元数据错误抛 IllegalArgumentException；Schema 与数据库错误保留原异常。错误码见 [Core](OGBS-Core-1.0.zh-CN.md#data-常量)。

```java
public record DataFailure(int errorCode, Class<?> entityType, DataOperation operation,
                          List<?> batch, Throwable cause, int attempt) {}
public interface RetryPolicy { boolean shouldRetry(DataFailure failure); }
public interface DataErrorHandler { void onError(DataFailure failure); }
```

DataOperation（INSERT、UPDATE、DELETE、DELETE_INSERT、LOG_INSERT）是实际保存的合并后意图：先 DELETE 后 INSERT 报告为 DELETE_INSERT。`RetryPolicy.transientFailures()` 会遍历原因链、next exception 与 suppressed 异常；可组合为 `f -> RetryPolicy.isTransient(f.cause()) || myRule(f)`。`RetryPolicy.never()` 不保留任何失败。

| 契约 | 测试 |
| --- | --- |
| 不预先 SELECT；加载失败不缓存 | DataContractTest.negativeCacheDeleteAndInsertNeverSelect / loadFailuresAreNotNegativeCached |
| 分组须先加载；返回同一 Map | DataContractTest.groupsRequireExplicitLoadAndReturnSameMap |
| 合并矩阵 | DataContractTest.legalMergeTable / illegalMergeTable |
| 批次顺序与拆分 | DataContractTest.batchesOrderedAndSplitAndNeverMergeAcrossFlushes |
| 可重试保留、坏行隔离 | DataContractTest.retryableFailureKeepsChangesUntilStorageRecovers / nonRetryableBadRowIsIsolatedFromItsBatch |
| 脏条目永不淘汰；干净条目淘汰后重新加载 | DataContractTest.entitiesWithUnsavedChangesAreNeverEvicted / cleanIdleEntriesAreEvictedAndReloaded |
| close 先重试再丢弃并报告 | DataContractTest.closeRetriesThenDropsAndReportsLeftovers |
| 回调中 close 被拒绝且不死锁 | DataContractTest.closeFromCallbackIsRejectedWithoutDeadlock |
| 日志分表、排空、自动创建分表 | LogContractTest.closeDrainsPartitionsAndBatchesWithoutSchemaInitialization / missingPartitionTableIsCreatedFromTheBaseTable |
| Mongo 更新不存在的文档失败 | MongoMappingTest |

已用内存 Mapper、H2 的 MySQL 模式和记录型 MongoAccess 验证。[DatabaseIntegrationTest](../../game-data/src/test/java/cn/managame/data/DatabaseIntegrationTest.java) 在设置 `OGBS_DATA_MYSQL_URL` / `OGBS_DATA_MONGO_URI` 时连接真实服务，否则跳过。真实 MySQL/MongoDB 行为、生产性能与长时间故障未验证。

<a id="11-显式-flush-与-datastats"></a>

## 10. 显式 flush 与 DataStats

`GameData.flush()` 同步执行一轮：调用前接纳的变更全部保存，并发写入的变更可能留到下一轮。已关闭或在保存回调中调用时抛 DataOperationException；曾有变更被丢弃，或仍有被保留的可重试失败时抛 DataSaveException（后者仍待保存）。不要在繁忙的游戏 Route 上执行全服 flush。

`GameData.stats()` 返回 DataStats：accepting、pendingChanges（待保存条目，含被保留的失败；正在执行的一轮已认领的变更不计入）、queuedLogs、failedBatches（被丢弃的变更）、flushes（轮次，不含 close 的最后一轮）、saveNanos、singleCacheEntries（含已缓存的不存在）、groupCacheEntries（分组数，而非实体数）。数值是近似快照；queuedLogs 为 O(n)。
