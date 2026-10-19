# OGBS Data Java 25 Development Specification 1.0

[English](OGBS-Data-Java-25-Specification-1.0.md) | **[简体中文](OGBS-Data-Java-25-Specification-1.0.zh-CN.md)**

文档类型：**Java 开发规范**。行为由 [OGBS Data 规范](OGBS-Data-1.0.zh-CN.md) 定义；本文定义 Java API、默认值、异常、线程与验证。

状态：Java 25 参考实现，Maven `cn.managame:game-data:1.0.0-SNAPSHOT`。

## 1. 模块与公共入口

一个 artifact 包含 Repository、缓存、写回、MySQL/JDBC、MongoDB 与日志。依赖 game-core 与 Jackson Databind 2.21.3（默认 JSON）。MongoDB Sync Driver 5.5.1 为可选依赖，使用 Mongo 的应用自行加入。JDBC 使用应用提供的 DataSource 和 MySQL 驱动；Data 不选择连接池。保存按 JDBC 批次执行，MySQL Connector/J 需在连接属性中设置 `rewriteBatchedStatements=true` 才会把批次合并发送（[game-demo 的 MysqlConfig](../../game-demo/src/main/java/cn/managame/demo/common/mysql/MysqlConfig.java) 已设置）；否则每行一次往返。

| 包 | 职责 |
| --- | --- |
| cn.managame.data | GameData、GameDataBuilder、三种 Repository 基类；包级 EntityCache、PendingBuffer、WriteBehindManager |
| annotation / key | 身份注解、GroupKeys、MapKeys |
| meta / mapper | EntityMeta（供存储 SPI 使用的身份元数据）、EntityMapper |
| codec / error | JSON/BINARY codec、异常、DataFailure、RetryPolicy、DataErrorHandler |
| mysql | SQL 注解、MysqlAccess、JdbcMysqlAccess、MysqlEntityMapper、日志映射 |
| mongo | BSON 注解、MongoAccess、DriverMongoAccess、MongoEntityMapper |

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
    public final Map<K, E> getGroup(GroupKeys key);   // 只读实时视图
    public final E get(GroupKeys key, K mapKey);
    public final void insert(E entity);
    public final void update(E entity);
    public final void delete(E entity);
    public final void deleteGroup(GroupKeys key);
}
public abstract class LogRepository<E> {
    public final boolean insert(E entity);   // false：日志队列已满，已报告给 DataErrorHandler
}
```

业务 Repository 直接继承一个具体参数化的基类，并有无参构造器；不支持中间泛型 Repository。由 `build` 创建，通过 `data.repository(Players.class)` 获取。每种实体只能有一个状态 Repository。Single 的 K 为 Id 的包装类型；Group 的 K 为 MapKey 的包装类型，复合 MapKey 时为 String。Repository 只由 Builder 初始化；自定义后端请扩展 EntityMapper。

<a id="21-repository-直接持有依赖"></a>
<a id="22-读写路径"></a>

### 2.1 缓存与修改路径

每个 Repository 持有一个 EntityCache：Single 按 id 缓存实体或已确认的不存在（null）；Group 按 GroupKeys 缓存一个 `ConcurrentHashMap<K,E>`，getGroup 返回它的只读实时视图（每次加载创建一次，同一缓存期内返回同一对象）；业务只能通过 insert/update/delete 修改分组，直接 put/remove 抛 UnsupportedOperationException。Single 的读取（get/getOrCreate）与 Group 的读写都会加载缺失的 Key；Single 的写入从不加载。加载在调用线程上同步执行；缺失的 Key 只加载一次，同 Key 的并发调用等待该次加载。加载在缓存 Map 的锁之外执行，其他 Key 不会因此被阻塞；加载期间对同一 Key 的修改先等待该次加载，再作用于其结果；若加载期间该 Key 被淘汰，结果作废并重新加载，保证不会装入比内存旧的数据。在加载器内再次读取同一 Key 抛 IllegalStateException。加载失败抛 DataLoadException，不会缓存为不存在。

Data 不提供异步读取。默认的虚拟线程 Route 执行器上，同步加载只挂起当前 Route 的虚拟线程，同一 Route 的后续消息本就要等它完成，顺序语义不变，且业务不需要回调。使用平台线程 Route 执行器时，应在玩家进入 Route 时预加载其数据，让游戏内读取都命中缓存。

写入在 Key 已缓存时立即校验：Single 对已存在实体 insert、对已确认不存在的 Key update 抛 DataOperationException，delete 已不存在的 Key 什么也不做；Key 未缓存时按原样登记，不为校验读取存储。Group 写入前先加载分组，因此总能校验：MapKey 已存在时 insert、不存在时 update 抛异常，update 换成另一 @Id 的实体也抛异常；delete 按 MapKey 删除分组中现有的实体，MapKey 不存在时什么也不做。`getOrCreate` 在不存在时把工厂创建的实体登记为 INSERT 并放入缓存；工厂在锁外执行，返回实体的 @Id 必须等于 key，并发时先登记者胜出，另一方返回已缓存实体。

实体字段由保存线程在编码时读取（D-OWN-01）。实体中被业务在 Route 上修改、同时可能被保存线程遍历的集合字段应使用线程安全集合（例如 ConcurrentHashMap、CopyOnWriteArrayList、ConcurrentLinkedDeque），标量字段需要跨线程可见时使用 volatile 或原子类型；否则编码可能遇到 ConcurrentModificationException 或读到半更新状态。框架不做快照，这是性能取舍。

修改先校验状态与身份，通过准入计数（一次 CAS，不使用读写锁），然后在该 Key 的 `EntityCache.update` 中执行：校验存在性（见上），`writer.record` 合并意图（非法序列抛异常，缓存不变），再修改缓存条目或分组 Map。登记在缓存的单 Key 原子操作内完成，这是淘汰安全的前提（见 5.1）。登记不是字段事务：调用前直接修改的字段不会回滚。deleteGroup 为每个当前成员登记按主键 DELETE，再清空原 Map。

Spring 接入属于应用代码：[game-demo 适配](../../game-demo/src/main/java/cn/managame/demo/common/data/GameDataConfig.java) 把扫描到的单例 `@Repository` Bean 注册到 Builder，并以 `data.repository(type)` 作为 Bean 实例（见 [DataRepositoryRegistrationTest](../../game-demo/src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java)、[DataSpringWiringTest](../../game-demo/src/test/java/cn/managame/demo/DataSpringWiringTest.java)）。

## 3. 构建与生命周期

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

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| cacheExpire | 30 分钟 | 干净条目空闲多久后淘汰；须大于 flushInterval |
| flushInterval | 1 秒 | 两轮保存之间的目标间隔 |
| batchSize | 500 | 每批行数。以 INSERT 为主与以 UPDATE 为主的存储取值相反（[§6.2](#62-批量吞吐与-jdbc-url)） |
| shutdownTimeout | 30 秒 | close 持续重试可重试失败多久，之后丢弃并报告。在容器集成下，它会在应用的 configurer 之前由关停预算为刷盘保留的那块份额设定，因此默认值改为那个值（[Spring §3.0](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md#30-关闭预算)） |
| retryPolicy | RetryPolicy.transientFailures() | true 保留到下一轮；false 拆分、丢弃并报告（见第 5 节） |
| errorHandler | System.Logger | 接收被丢弃的变更，在此归档 |
| partitionZone | UTC | DAY/MONTH/YEAR 日志分表使用的时区 |
| mysql(DataSource) | 无 | 未指定后端的状态 Repository 借用的 DataSource；它不服务日志 |
| logMysql(DataSource) | 无 | 未指定后端的日志 Repository 借用的 DataSource。日志是独立存储（D-LOG-04）；两者共用一个数据库时，把同一个 DataSource 分别传给两个方法 |
| jsonCodec / binaryCodec | 默认 Jackson / 无 | 隐式 MySQL Mapper 及日志（除非 logCodecs 覆盖）使用的 codec |
| logCodecs(json, binary) | 继承 | 仅日志使用的 codec；JSON 传 null 表示默认 |
| maxPendingChanges | 100,000 | 所有状态 Repository 合计可以等待保存的不同实体数（D-WRITE-04）。对已在等待的实体的修改会合并，永远被接纳；会新增实体的修改先等待 pendingChangeWait，之后以 DataOperationException 拒绝，并计入 DataStats.rejectedChanges。被拒绝的修改不登记、缓存保持原样、不到达 DataErrorHandler，也不会使 flush 或 close 失败 |
| pendingChangeWait | 5 秒 | 这类写入在被拒绝前等待某一轮腾出空间的时长；0 表示立即拒绝。关闭时拒绝等待者而不是挂住它们，因此它们不会拖慢 close |
| maxCacheEntries | 1,000,000 | 单个状态 Repository 的缓存条目上限（D-CACHE-03）。超过时在保存线程上淘汰最久未读的干净条目，每轮之后都检查，而不是只在空闲清理时；有待保存变更的条目永不淘汰，因此该值应高于 maxPendingChanges |
| logQueueCapacity | 100,000 | 每个 LogRepository 等待保存的日志上限。达到上限时 insert 返回 false，以 LOG_INSERT 失败（attempt 0）在插入线程上报告给 DataErrorHandler，并计入 DataStats.droppedLogs；被拒绝的日志不是已接纳的变更，不使 close 失败 |

repositories(...)、logRepositories(...) 接受类或列表；显式传入 EntityMapper 或 MysqlAccess 的重载不使用默认后端。指定了后端的注册会替换同一 Repository 类型此前未指定后端的注册并保留其位置——容器集成已经发现并注册的 Repository，应用因此仍可改接到别的后端；同一类型的其他重复注册在注册调用处抛 IllegalArgumentException。build 时仍未绑定的 Repository 抛 IllegalArgumentException 并指出能绑定它的方法：状态为 mysql(DataSource) 或 EntityMapper，日志为 logMysql(DataSource) 或 MysqlAccess；此时尚未打开任何 DataSource。build 先校验 Repository、身份与日志映射，再执行各状态 Mapper 的 initialize（映射与 Schema；后续步骤失败时已执行的 DDL 不回滚），最后创建缓存并启动一个保存线程。

GameData 只关闭自己的线程，从不关闭 DataSource、MongoClient 或 Mapper。close 可能等待数据库调用；在 Mapper、RetryPolicy、DataErrorHandler 回调中调用 close 或 flush 会被拒绝，以免死锁。关闭后所有 Repository 操作抛 DataOperationException。

## 4. Entity 与身份元数据

支持继承字段；注解标在非 static、非 final 字段上。状态实体需要可访问的无参构造器；加载时通过 VarHandle 直接写字段（JPMS 下需开放包）。身份字段为 byte/short/int/long、其包装类型或 String，不能为 null。复合 Key 需要显式且互不相同的 `order`。`GroupKeys.of(Object...)` 不复制数组（不要修改它），按类型比较值：`GroupKeys.of(42L)` ≠ `GroupKeys.of(42)`。`MapKeys.of(a, b, …)` 用 ':' 连接至少两个整数/String 值，值内不能含 ':'。

```java
public interface EntityMapper {
    void initialize(EntityMeta meta);
    Object load(EntityMeta meta, Object id);
    List<?> loadGroup(EntityMeta meta, GroupKeys key);      // 非 null，无数据时为空列表
    void insertBatch(EntityMeta meta, List<?> entities);
    void updateBatch(EntityMeta meta, List<?> entities);
    void deleteBatch(EntityMeta meta, List<Object> ids);
    void deleteInsertBatch(EntityMeta meta, List<?> entities);
}
```

EntityMeta 只包含身份与字段访问；存储规则归各 Mapper（只有 @Id 没有 @Column 是合法身份，但 MySQL 映射会拒绝）。自定义 Mapper 必须区分实体类型，不能把加载失败当作不存在，每批原子执行，并保留原因异常。

## 5. 写回实现与适用边界

PendingBuffer 为每个 EntityMeta 保存一个 `ConcurrentHashMap<id, Change>`；Change 持有操作与最新实体引用，按对象身份比较。`record` 用一次 `compute` 按标准中的合并矩阵合并，并在同一次单 Key 原子操作内维护不同待保存主键的计数，因此 `DataStats.pendingChanges` 精确且为 O(1)，不需要遍历。

接纳控制（D-WRITE-04）。Repository 在自己的修改之前调用管理器，位置在关停屏障之外、任何缓存锁之外，因此等待中的写入方不持有任何东西：脏集合低于 maxPendingChanges，或该主键已在待保存中（`containsKey`，合并不新增条目）时立即返回；否则以 1 毫秒为步长挂起，直到某一轮腾出空间、组件停止接纳（DataOperationException，因此 close 永远不会被等待者拖住）、线程被中断（DataOperationException，并恢复中断标志），或 pendingChangeWait 耗尽（DataOperationException，计入 rejectedChanges）。调用方本身就是保存线程时——Mapper 或 DataErrorHandler 回调——从不挂起，立即被拒绝。`deleteGroup` 一次登记组内每个成员，按一次调用接纳，因此可能超出上限一个组的大小，正如并发写入方可能超出它们的个数。每轮保存以条件删除 `pending.remove(id, change)` 认领条目：认领之后的登记会新建条目留给下一轮，因此遍历与删除之间不会丢失变更；单一保存线程保证同一 id 的旧变更先于新变更执行。

失败处理。Mapper 每批原子执行（MySQL 一个事务，Mongo 按 `_id` 替换），失败的批次不留部分行：

1. RetryPolicy 返回 true（默认：连接丢失或被拒、连接池超时、I/O、锁等待超时 1205、死锁 1213、SQLState 08xxx/40xxx、Mongo 网络/超时/主节点切换）：整批合并回新变更之下，下一轮重试，无次数上限。保留的变更数不超过脏实体数。
2. 不可重试且多于一行：拆成单行逐个执行，其他行照常保存。
3. 不可重试的单行：丢弃，计入 failedBatches，同步报告给 DataErrorHandler（attempt 1 表示原本就是单行批次，2 表示拆分后隔离出的行）；之后 flush/close 抛 DataSaveException。
4. 合并回去时与新变更冲突（旧 DELETE 后接 UPDATE）：保留新变更，旧变更丢弃并报告。

MySQL UPDATE 未匹配任何行时按不可重试单行失败（保持 Connector/J 默认 `useAffectedRows=false`）；Mongo 更新的文档不存在时同样失败。

保存线程读取实体时不做快照。业务代码会修改的实体内集合必须线程安全；需要一致的多个字段应在同一次 update 前一起修改。没有 WAL：进程崩溃最多丢失一个保存间隔加上被保留的失败变更。

### 5.1 缓存淘汰

条目在空闲超过 cacheExpire 时，或缓存超过 maxCacheEntries 时被淘汰，两种情况都只淘汰没有待保存变更的条目。空闲淘汰在保存线程上、一轮保存之后进行，最多每 max(flushInterval, cacheExpire/4) 一次；条目上限改为每轮之后都检查，因为超出预算的缓存应当在若干轮之内回落。超过上限时，一次清理用一个有界的堆挑出访问时间最早的干净条目——一次挑选不超过 maxCacheEntries 个——再对每个条目做单 Key 原子删除，若它在被挑中之后被读取、被修改或变脏则保留。访问时间最多每 min(cacheExpire/16, 1 秒) 刷新一次（见下），因此同一个刷新窗口内被读取的条目之间没有先后之分；填充速度快于清理频率的缓存会稳定在略高于上限处，而不是正好在上限。两种淘汰进行时都没有已认领但未完成的变更。Single 检查 `pending.containsKey(id)`；Group 每次清理时收集待保存变更所属的 GroupKeys（分组删除因此保留实体）。清理先读取时钟再构建该集合，并在单 Key 原子操作内重新检查空闲时间：集合漏掉的修改一定发生在读取时钟之后，已刷新其条目访问时间，条目会被保留。读取最多每 min(cacheExpire/16, 1 秒) 刷新一次访问时间，避免多线程读同一热点实体时反复写同一缓存行；因此淘汰等待 cacheExpire 再加上该间隔。因此加载从不查询缓冲：缺失的 Key 没有待保存变更，存储就是最新的。存储故障期间脏实体一直留在缓存中，直到保存成功或被丢弃。

### 5.2 一轮保存

```text
对每个 EntityMeta：
  最多认领此刻待保存的条数（条件删除），不再多认领
  按 DELETE → DELETE_INSERT → INSERT → UPDATE 分组，按 batchSize 拆批；保存或按失败分类处理
日志：有限排空，按分表批量插入，失败按同样规则分类
随后淘汰超出上限的缓存条目，并在到期时淘汰空闲的干净条目
下一轮在 max(0, interval − 本轮耗时) 后开始
```

认领边界是每个实体类型读取一次的计数，与 `LogRepository.drain` 对日志的既有做法一致：本轮运行期间登记的内容留给下一轮。因此一轮的开销至多是 maxPendingChanges 允许的量，这正是让它耗时有界、让 `flushes` 在生产者持续写入时仍然前进的原因。`GameData.flush()` 反复执行轮次，直到认领的数量不少于调用时待保存的数量；close 反复执行直到没有已接纳的变更可认领，切换到 CLOSED 之后再做同样的事，此时可重试失败直接丢弃而不是放回缓冲。

周期保存、显式 flush 与 close 从不并发执行 Mapper 批次。

### 5.3 RetryPolicy、错误与关闭

RetryPolicy 只在“保留到下一轮”和“拆分/丢弃”之间选择；没有立即重试或退避线程。RetryPolicy 抛异常时按不可重试处理。DataErrorHandler 只接收被丢弃的变更，在保存线程上运行，例如写入本地死信文件。可重试失败每轮记录一条 WARNING。

准入读锁让关闭与进行中的修改有序。close 获取写锁切换到 CLOSING，停止调度，反复保存直到没有待保存变更或超过 shutdownTimeout，然后执行最后一轮：可重试失败也被丢弃并报告。生命周期内任何丢弃都会让 close 抛 DataSaveException，重复调用报告同一失败。永不返回的数据库调用也会阻塞 close；请在连接池或驱动中设置超时。

## 6. MySQL 映射

```java
@Table(name = "player_task", indexes = { @Index(name = "idx_role_task", columnList = "role_id, task_id") })
public class Task {
    @Id @Column private long id;
    @GroupKey @Column private long roleId;
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

<a id="62-batch-throughput-and-the-jdbc-url"></a>
<a id="62-批量吞吐与-jdbc-url"></a>

### 6.2 批量吞吐与 JDBC URL

保存速率由应用的 JDBC URL 决定，不由本组件决定。Connector/J 只有在 `rewriteBatchedStatements=true` 时才把一个
批次合并成一条语句；驱动默认下 `batchUpdate` 的每一行都是一次独立往返，照抄上面的配置、URL 留空的部署，只能拿到
同一份代码在开启该参数时的一小部分速率。

| 设置 | INSERT | UPDATE | 原因 |
| --- | --- | --- | --- |
| `rewriteBatchedStatements=true` | batchSize 500 下约 4.4 倍，2000 下约 7 倍 | 500 下约 1.5 倍，2000 下约 1.1 倍 | 只有 INSERT 的行能合并成一条多行 `VALUES`，且合并后的语句随批次增大；`UPDATE … WHERE id=?` 无法合成单条语句，每行仍是一次往返 |
| batchSize 用 2000 而非 500 | 约快 1.7 倍 | 约慢到 0.67 倍 | 更大的 INSERT 语句摊薄往返成本；更大的 UPDATE 批次只是让一个事务开得更久，没有合并收益 |

在构建 DataSource 的 URL 上设置该参数
（`jdbc:mysql://host:3306/game?rewriteBatchedStatements=true`），并按写入构成选择 batchSize，而不是默认它对两边
都最优：500 适合以 UPDATE 为主的状态，以 INSERT 为主的存储更适合 2000。远程数据库的收益高于上述比值——这些比值
在 loopback 上测得，那是往返成本的下限。每批次新开连接而不用连接池，损失从百分之几到约一半不等：握手按批次付出，
批次自身的工作越便宜，它的占比越重——被驱动合并成一条语句的 2000 行 INSERT 和小批次一样吃亏。连接池值得有，但不是
瓶颈，而且正是它让这个开关值得设：上面两列倍率都是带池测得的，不带池时 2000 上的 INSERT 倍率掉到约 4~5 倍，
UPDATE 的倍率则完全消失（两轮都在 0.9~1.0 倍之间）——只设开关而不用连接池的部署，在状态写入上一无所得。
测量条件、绝对数值、不同次运行之间的离散，以及重跑这些测量的压测：
[保存路径压测](../reviews/2026-10-18-data-save-path-load-test.zh-CN.md)，由
[DataSavePathLoad](../../game-benchmark/src/main/java/cn/managame/benchmark/DataSavePathLoad.java) 运行。

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

DataLoadException：存储加载失败或加载数据非法。DataOperationException：未初始化、已关闭、分组未加载、非法变更序列、脏集合在 pendingChangeWait 内一直满、Mongo 更新不存在的文档。DataSaveException：有变更被丢弃（由 flush/close 报告）。配置与元数据错误抛 IllegalArgumentException；Schema 与数据库错误保留原异常。错误码见 [Core](OGBS-Core-1.0.zh-CN.md#data-常量)。

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
| Group 写入自动加载；getGroup 为只读视图 | DataContractTest.groupWritesLoadTheGroupAndGetGroupIsAReadOnlyLiveView |
| 写入立即校验存在性；未缓存写入不读存储 | DataContractTest.groupWritesAreCheckedAgainstTheLoadedGroup / cachedSingleWritesFailFastAndUncachedOnesNeverSelect |
| getOrCreate 只创建一次 | DataContractTest.getOrCreateInsertsOnceAndReturnsTheCachedEntity |
| 合并矩阵 | DataContractTest.legalMergeTable / illegalMergeTable |
| 批次顺序与拆分 | DataContractTest.batchesOrderedAndSplitAndNeverMergeAcrossFlushes |
| 可重试保留、坏行隔离 | DataContractTest.retryableFailureKeepsChangesUntilStorageRecovers / nonRetryableBadRowIsIsolatedFromItsBatch |
| 脏条目永不淘汰；干净条目淘汰后重新加载 | DataContractTest.entitiesWithUnsavedChangesAreNeverEvicted / cleanIdleEntriesAreEvictedAndReloaded |
| close 先重试再丢弃并报告 | DataContractTest.closeRetriesThenDropsAndReportsLeftovers |
| 一轮只认领开始时的集合；轮次持续结束；close 排空积压 | SavePassBoundaryTest |
| 脏集合满时先等待再拒绝且不登记；对等待中实体的修改仍然合并；回调不会等待它自己所在的那一轮 | DirtySetBoundTest |
| 条目上限淘汰最久未读的干净条目，绝不淘汰未保存的工作 | EntityCacheTest.theEntryBoundEvictsTheLeastRecentlyUsedCleanEntriesOnly / theEntryBoundNeverEvictsUnsavedWorkEvenWhenThatLeavesItOver |
| 真实 MySQL 上不限速的生产者受上限约束且不丢数据 | DatabaseIntegrationTest.unthrottledProducersAreBoundedAndEveryAcceptedChangeIsStored |
| 回调中 close 被拒绝且不死锁 | DataContractTest.closeFromCallbackIsRejectedWithoutDeadlock |
| 日志分表、排空、自动创建分表 | LogContractTest.closeDrainsPartitionsAndBatchesWithoutSchemaInitialization / missingPartitionTableIsCreatedFromTheBaseTable |
| Mongo 更新不存在的文档失败 | MongoMappingTest |
| 状态与日志是独立存储，互不推导 | StoreBindingTest.logsNeverFallBackToTheStateSource / separateSourcesKeepLogRowsOutOfTheStateDatabase |
| 同一个 GameData 里 MongoDB 存状态、MySQL 存日志 | StoreBindingTest.mongoStateAndMysqlLogsAreIndependentStores |
| 指定后端替换隐式注册，其他重复仍是重复 | StoreBindingTest.namingABackendReplacesAnEarlierImplicitRegistration / repeatsThatAreNotRedirectionsStayDuplicates |

已用内存 Mapper、H2 的 MySQL 模式和记录型 MongoAccess 验证。[DatabaseIntegrationTest](../../game-data/src/test/java/cn/managame/data/DatabaseIntegrationTest.java) 在设置 `OGBS_DATA_MYSQL_URL` / `OGBS_DATA_MONGO_URI` 时连接真实服务，否则跳过。真实 MySQL 上的表结构往返、以及不限速生产者洪水下的各项上限已验证（[压测记录](../reviews/2026-10-18-data-save-path-load-test.zh-CN.md)）；MongoDB 行为、生产性能与长时间故障未验证。

<a id="11-显式-flush-与-datastats"></a>

## 10. 显式 flush 与 DataStats

`GameData.flush()` 同步执行一轮：调用前接纳的变更全部保存，并发写入的变更可能留到下一轮。已关闭或在保存回调中调用时抛 DataOperationException；本轮有变更被丢弃（已报告给 DataErrorHandler），或仍有被保留的可重试失败时抛 DataSaveException（后者仍待保存）。更早轮次的丢弃不会让之后干净的 flush 失败；close 仍会对 GameData 生命周期内的任何丢弃抛 DataSaveException。不要在繁忙的游戏 Route 上执行全服 flush。

`GameData.stats()` 返回 DataStats：accepting、pendingChanges（待保存条目，含被保留的失败；正在执行的一轮已认领的变更不计入）、queuedLogs、failedBatches（被丢弃的变更）、flushes（轮次，不含 close 的最后一轮）、saveNanos、singleCacheEntries（含已缓存的不存在）、groupCacheEntries（分组数，而非实体数）、droppedLogs（因日志队列已满被拒绝的日志）与 rejectedChanges（因脏集合持续满而被拒绝的状态修改；从未被接纳，因此不是丢弃）。数值是快照；pendingChanges 与 queuedLogs 由计数器维护，为 O(1)。
