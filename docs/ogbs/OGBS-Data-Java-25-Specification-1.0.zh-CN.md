# OGBS Data Java 25 Development Specification 1.0

[English](OGBS-Data-Java-25-Specification-1.0.md) | **[简体中文](OGBS-Data-Java-25-Specification-1.0.zh-CN.md)**

文档类型：**Java 开发规范**。对应标准：[OGBS Data Specification](OGBS-Data-1.0.zh-CN.md)。

本文规定 Java 实现的公开 API、默认配置、异常形式、线程与资源机制、扩展接入以及验证要求。Java 实现必须同时满足本文和对应标准规范；不能只满足方法签名而忽略行为契约。代码与规范冲突时应修正实现，设计变更则同步修订两层规范。下文明确标注的待实现、未验证能力不代表已完成。

状态：Java 25 参考实现，Maven `cn.managame:game-data:1.0.0-SNAPSHOT`。行为定义以 [Data Specification](OGBS-Data-1.0.zh-CN.md) 为准。

## 1. 模块与公共入口

一个 game-data artifact 内包含 Repository、Caffeine 缓存、写回、MySQL/JDBC、MongoDB 适配和日志。依赖 game-core，并按 [Core Java 规范](OGBS-Core-Java-25-Specification-1.0.zh-CN.md#1-模块与职责) 传递引入共享 Caffeine；MongoDB Sync Driver 5.5.1 是 optional 依赖，使用 Mongo 的应用需显式添加。JDBC 接收应用提供的 DataSource，不绑定连接池；应用提供 MySQL JDBC Driver。默认 JSON 编解码依赖 Jackson Databind 2.21.3，与 Runtime 使用相同版本。

| 包 | 职责 |
| --- | --- |
| cn.managame.data | GameData、GameDataBuilder、三种 Repository；包级 PendingBuffer/WriteBehindManager |
| annotation / key | 身份字段注解、GroupKey、MapKeys |
| meta / mapper | 面向存储 SPI 的身份元数据、VarHandle 字段访问、EntityMapper |
| codec / error | JSON/BINARY 编码接口、内部默认 Jackson 编解码、异常、失败上下文、重试及错误回调 |
| mysql | SQL 注解、MysqlAccess、JdbcMysqlAccess、MysqlEntityMapper、日志映射 |
| mongo | BSON 注解、MongoAccess、DriverMongoAccess、MongoEntityMapper |

MysqlEntityMeta、MysqlFieldMeta、MysqlSchema、MongoEntityMeta、MongoValueCodec 均为包级实现。MysqlLogWriter 是跨包内部装配桥，不是业务扩展 SPI。不存在 RepositorySupport、CacheGroup 或 Map view。

## 2. Repository API

以下为签名摘要：

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

业务 Repository 必须直接继承一个具体参数化基类，有无参构造；不支持中间泛型 Repository、raw type、未解析类型变量。由 build 创建，使用 `data.repository(Players.class)` 获取。同一个实体类型只能注册一个状态 Repository，重复注册在初始化数据库之前拒绝。

Single 的 K 必须等于 Id 字段装箱类型；Group 的 K 是 MapKey，单字段为字段装箱类型，多字段固定 String。GroupKey 对象与同名注解分别在 key/annotation 包；避免同时 wildcard import 产生歧义。

缓存使用 expireAfterAccess：Single 是 LoadingCache<K, CacheEntity<E>>，entity=null 表示负缓存；Group 是 LoadingCache<GroupKey, ConcurrentHashMap<K,E>>。只有 get/getGroup 触发加载；修改组只 getIfPresent。无容量驱逐配置、手动 eviction API 或缓存包装视图。

### 2.1 Repository 直接持有依赖

业务 Repository 的职责是声明具体 K/E，公共操作在基类中 final 实现。SingleRepository 与 GroupRepository 各自直接持有 EntityMeta、EntityMapper、WriteBehindManager 和 Caffeine cache，不再委托 SingleRepositorySupport/GroupRepositorySupport。

包级初始化签名为：

```java
// SingleRepository / GroupRepository；内部装配，不是业务扩展 API
final void initialize(EntityMeta meta, EntityMapper mapper,
                      WriteBehindManager writer, Duration expiry);

// LogRepository；内部装配
final void initialize(MysqlLogWriter logWriter, WriteBehindManager writer);
```

业务不应手动 new Repository 后自行填充这些依赖。initialize 不向业务公开，Builder 负责在开始接纳操作之前完整装配。自定义数据库后端通过 EntityMapper 扩展，不通过重写 final CRUD 改变契约。

Spring 集成由应用负责，game-data 不引入 Spring 依赖。[game-demo 适配](../../game-demo/src/main/java/cn/managame/demo/common/data/GameDataConfig.java) 收集扫描到的三种 Repository 基类的单例 `@Repository` 定义，将具体类型注册到 Builder，并以 `data.repository(type)` 提供对应 Spring Bean 实例。保留名称、限定符及 Spring 字段/setter 注入，确保 Data 初始化先于注入回调和业务使用。仍要求无参构造，此适配不支持构造器依赖注入及非单例作用域。Repository Bean 依赖 GameData，GameData 依赖借用的 DataSource，关闭按此依赖顺序执行。验证入口：[DataRepositoryRegistrationTest](../../game-demo/src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java) 与 [DataSpringWiringTest](../../game-demo/src/test/java/cn/managame/demo/DataSpringWiringTest.java)。这是应用集成示例，不新增框架初始化 API。

### 2.2 读写路径

Single.get 校验初始化和运行状态、精确 Key 类型，随后调用 LoadingCache.get。Loader 使用 Mapper.load；返回 null 用 CacheEntity 包装成负缓存，抛异常则转换为 DataLoadException。不能直接把 null 存进 Caffeine，也不能把异常改写为 null。

Group.getGroup 先验证 GroupKey 的分量数量和类型，再加载整组。Loader 检查每个 Entity 的组键，使用 putIfAbsent 检查重复 MapKey。Group.get 只是整组缓存上的读取，不新增单行 SELECT 路径。

修改路径如下，属于实现顺序说明：

```text
校验 Repository 运行状态、参数与身份
→ 进入 writer.mutate 的接纳读锁并再次确认 RUNNING
→ Group 检查当前缓存中仍存在目标组
→ writer.record 合并意图（可能拒绝非法序列）
→ 修改缓存实体/组 Map
→ 退出接纳范围
```

先 record 后修改缓存，确保非法合并不会把缓存指向新对象。该顺序不是实体字段事务：调用者在 update 前已经改过的字段不会回滚。deleteGroup 按当前 Map 中实体逐个登记删除后清空原 Map，不调用 Mapper 的条件删除接口。


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
        .retryPolicy(failure -> isRetryable(failure.cause()))
        .maxAttempts(3)
        .build()) {
    PlayerRepository players = data.repository(PlayerRepository.class);
}
```

DataSource、业务类和失败归档/重试函数由应用提供；常见 JSON 字段不需要配置 codec。只有希望改变默认格式或具体类型构造行为时才实现 JsonCodec。DataMemoryDemo 尚未落入当前仓库；无需外部数据库的内存 Mapper/Repository 用法见 [DataContractTest](../../game-data/src/test/java/cn/managame/data/DataContractTest.java)。

| 配置 | 默认 | 校验/含义 |
| --- | --- | --- |
| cacheExpire | 30 分钟 | 正值且大于 flushInterval；部署应远大于实际保存延迟 |
| flushInterval | 1 秒 | 正值，单调时间目标周期 |
| batchSize | 500 | 正整数 |
| maxAttempts | 3 | 含第一次执行；正整数；只有 RetryPolicy 返回 true 才重试 |
| retryPolicy | 总是 false | 默认执行一次，无内建 SQLState/Mongo 错误分类 |
| errorHandler | System.Logger | 最终失败诊断；业务负责归档失败数据 |
| partitionZone | UTC | 显式 ZoneId，可配置 Asia/Shanghai |
| mysql(DataSource) | 未配置 | 借用 DataSource，为无 backend 参数的 Repository 注册建立 JdbcMysqlAccess/MysqlEntityMapper |
| jsonCodec(JsonCodec) | 默认 Jackson | 无 backend 参数的 MySQL 状态及未覆盖的日志；null 拒绝 |
| binaryCodec(BinaryCodec) | 无 | 同上；复杂 BINARY 仍需提供 codec，null 拒绝 |
| logCodecs(json,binary) | 继承上述配置 | 只覆盖日志；显式 null JSON 使用默认值，null Binary 表示无 codec |

repositories/logRepositories 也接受 List<Class<?>>。不带 backend 的注册在 build 时解析到 mysql(DataSource)，因此设置与注册顺序无关；缺少 DataSource 时 build 抛 IllegalArgumentException，mysql(null) 抛 NullPointerException。mysql 多次设置时使用最后的 DataSource，不创建或接管连接池。原 repositories(EntityMapper,...) 和 logRepositories(MysqlAccess,...) 保留，显式 backend 不受 mysql 配置影响；显式 Mapper 的 codec 仍由其构造参数控制。先校验 Repository 泛型、身份元数据和日志映射；随后状态 Mapper.initialize 编译各自存储映射并执行 Schema 初始化，再装配缓存并启动一个保存线程。Schema DDL 不承诺整体回滚，后续初始化失败时此前新增字段/表可能已存在；不会因此接管/关闭应用资源。

GameData 只关闭自己的调度资源，不关闭 DataSource、MongoClient 或外部 Mapper。close 可同步等待数据库操作；不要从 Mapper、RetryPolicy、DataErrorHandler 回调内调用 close，框架同步拒绝以避免死锁。关闭后所有 Repository 访问均拒绝。

### 3.1 build 的装配阶段

| 阶段 | 执行内容 | 失败后状态 |
| --- | --- | --- |
| 配置校验 | Duration 可转纳秒且为正、cacheExpire 大于 interval、正整数限制 | 不创建后台保存线程 |
| Repository 解析 | 直接参数化父类、具体 Entity、无参 Repository 构造、重复注册校验 | 不返回半成品 GameData |
| 身份/日志预编译 | 状态 EntityMeta 与 K 匹配；日志 MysqlLogWriter | 不启动保存 |
| 状态 Mapper.initialize | 编译后端映射、初始化 Schema | 已执行 DDL 不整体回滚 |
| 创建 WriteBehindManager | 两个缓冲、错误/重试配置、调度资源 | 仅内部装配 |
| initialize Repository | 直接注入依赖、创建缓存；日志加入保存管理 | 不暴露未初始化 Repository |
| GameData 建立 | 固定 Repository class 到实例映射，启动周期保存 | 返回可用入口 |

注意第三阶段不意味着所有数据库映射都已经验证：MySQL/Mongo 的存储映射由对应 Mapper.initialize 完成。前一个实体 Schema 成功、后一个实体映射失败时，前一个实体的 DDL 可能已经生效。应用可以修正配置后重建，但不能期待自动撤销建表或补字段。

repositories 和 logRepositories 的每次调用是追加绑定，不是替换。一个 Mapper 可以绑定多个实体，同一个实体不能注册两个状态 Repository。读取 GameData.repository(type) 只查已注册实例；关闭后仍可取得引用，但 Repository 的访问方法会拒绝使用，不会重新初始化。


## 4. Entity 与身份元数据

Entity 支持父类持久化字段；全继承链检查身份及存储列重名。状态 Entity 必须有可访问无参构造，加载通过预编译构造器创建，再直接写字段，不调用 setter。注解只用于字段。映射字段必须为非 static、非 final；启动反射、运行期 VarHandle。JPMS 场景需要向实现模块开放字段所属包。

当前身份字段支持 byte/short/int/long 及包装类型、String；不允许 null。该受限键域是当前 Java binding 的约束。单字段 order 可省略；多字段每个 order 必须显式填写且互异，Integer.MIN_VALUE 为未指定标记。

`GroupKey.of(Object...)` 保存原始数组、预计算 hash，不做 defensive copy；调用方不得修改传入数组及分量。equals 保留类型，1L 与 1 不相等。为 Mapper 提供只读 size()/valueAt(index)，不暴露可替换数组。

`MapKeys.of(a,b,...)` 至少两个非 null 的整数/String，拒绝包含冒号的字符串；只简单拼接。无 CompositeKey、转义、长度协议。

EntityMeta 是公开 Mapper SPI 元数据，不含存储注解语义。EntityMapper 签名：

```java
void initialize(EntityMeta meta);
Object load(EntityMeta meta, Object id);
List<?> loadGroup(EntityMeta meta, GroupKey key);
void insertBatch(EntityMeta meta, List<?> entities);
void updateBatch(EntityMeta meta, List<?> entities);
void deleteBatch(EntityMeta meta, List<Object> ids);
void deleteInsertBatch(EntityMeta meta, List<?> entities);
```

### 4.1 身份编译与存储编译的分工

EntityMeta 负责 Single/Group 身份、字段访问以及键类型，不应包含 MySQL 列类型或 Mongo 索引定义。一个实体首先在身份层合法，随后还必须在所选存储层合法。例如 @Id 字段未标 @Column，在身份层能识别主键，但 MySQL 映射仍会拒绝。

多字段顺序只要求显式且唯一，不要求 0、1、2 连续。GroupKey 保留类型，GroupKey.of(42L) 与 GroupKey.of(42) 不相等；调用方应使用与实体字段相同的装箱类型。MapKeys 的组合字符串则必须由相同顺序构造，不自行排序。

Mapper.loadGroup 应返回非 null 集合，空组返回空集合；Repository 对身份和 MapKey 做防御检查，但不会把任意第三方 Mapper 变成完整验证器。自定义 Mapper 仍需遵守 load 的实体类型、loadGroup 的完整组、批量操作及异常契约。


## 5. 写回实现与适用边界

固定两个 PendingBuffer，各自预创建 EntityMeta → ConcurrentHashMap<PrimaryId, PendingChange>；PendingChange 只保存操作与实体引用，DELETE 不保存实体。record 每次重新读取 volatile activeBuffer，使用 compute 按规范合并，不缓存 buffer 引用。

唯一保存线程交换 A/B，等待固定 100ms grace，处理旧缓冲全部实体批次，最终失败也上报后清空；不会跨缓冲合并。下一轮调度延迟为 max(0, interval - 上轮耗时)，不累计补跑历史周期。

**100ms 固定宽限期用于简化缓冲切换，不是严格并发屏障。** 若业务线程取得旧缓冲后停顿超过宽限期，可能错过本轮遍历或被 clear 覆盖。实现没有 writer counter、sealed buffer、epoch 或第三缓冲，不宣称在任意线程暂停下无丢失。正常 record 必须短小，实体业务顺序由应用保证。

接纳路径使用共享读锁，close 使用写锁关闭接纳；该锁只解决停服与已经接纳操作的竞争，不参与缓冲切换、不串行化不同业务实体。close 等待 pipeline 后清理两缓冲，累计出现过的最终失败会使 close 抛 DataSaveException。批次最终失败不会因后续批次成功而恢复成关闭成功。

缓存和日志队列/待保存集合不设容量上限。持续写入速度超过保存速度会增加内存占用；V1 未实现容量背压或生产容量验证。

### 5.1 PendingBuffer 的数据形状

```text
WriteBehindManager
  activeBuffer ──→ A 或 B（volatile）
  A: EntityMeta → ConcurrentHashMap<PrimaryId, PendingChange>
  B: EntityMeta → ConcurrentHashMap<PrimaryId, PendingChange>

PendingChange
  operation
  entityReference（DELETE 为 null）
```

外层键是 EntityMeta，不是 EntityMapper。玩家与公会可以共用一个 MysqlEntityMapper，并各自有 id=42；这两条变更必须落在不同表的不同 pending map。每次 record 重新读取 activeBuffer，同一主键通过 compute 合并；没有为每次 update 建立深拷贝。

### 5.2 一轮 flush 的时间线

```text
记录本轮单调开始时间
→ A/B 交换：生产者后续写新 active
→ 等待固定 100ms
→ 遍历旧缓冲：每个 EntityMeta 按操作阶段切 batch
→ 各 batch 执行、按用户策略重试或最终报错
→ 清空已处理旧缓冲
→ 对日志进行本轮有限 drain
→ 按 max(0, interval - 本轮耗时) 安排下一轮
```

保存 pipeline 互斥，周期调用与 close 不会同时执行 Mapper 批次。外层实体遍历次序不构成业务事务顺序；ConcurrentHashMap 内主键遍历顺序也不是更新调用顺序。需要依赖多实体操作顺序的业务不能把 batch 遍历当成串行事务。

固定宽限期的失效例子必须保留在实现文档中：线程取得旧 A 后暂停；保存线程切到 B、等 100ms 并遍历/清空 A；暂停线程随后才写入 A。此时变更可能错过本轮，或与 clear 竞争被覆盖。当前设计没有严格完成握手，因此不能宣称任意暂停下无丢失。若未来改成计数/封存/epoch 协议，应同步更新此机制、性能取舍和并发验证，不能只删除限制说明。

### 5.3 批次错误与尝试次数

以下为语义伪代码，不是可调用 API：

```text
attempt = 1
执行 batch
失败时:
    构造 DataFailure（包含本次 attempt）
    若 attempt < maxAttempts 且 RetryPolicy 同意:
        attempt 加一，立即重试相同 batch
    否则:
        记住生命周期内发生过最终失败
        同步调用 DataErrorHandler
        继续下一 batch
```

当前没有退避、随机抖动或单独重试线程。RetryPolicy 和 DataErrorHandler 在保存流水线上同步执行，耗时会延长整轮写回。RetryPolicy 自身抛异常时附加诊断并转最终失败；DataErrorHandler 抛异常时记录后继续。

DataFailure 的批次列表不允许回调修改其结构，但元素仍是实体引用，不是不可变快照。DELETE 批次携带 ID；实体写入和日志批次携带对象。只有实际失败时才构造失败上下文，成功路径不生成失败归档副本。

### 5.4 close 的内部屏障

close 在接纳写锁内切为 CLOSING，阻止新的 mutate；停止后续周期调度，然后等待正在运行的 pipeline 完成，处理较旧的非活动缓冲、活动缓冲及全部已接纳日志，最后进入 CLOSED。运行期已经记录的最终失败也会使 close 抛 DataSaveException，重复调用仍报告该失败。

不允许在保存线程执行的 Mapper/策略/错误回调里调用 close；否则会等待自己正在执行的流水线。框架检测并拒绝这种使用。外部数据库驱动调用若不返回，close 也可能一直等待，数据库 timeout 仍需要由应用配置。


## 6. MySQL 映射

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

Table.name 必填，只有 @Column 字段持久化，身份字段缺少 @Column 会初始化失败。默认字段名 camelCase → snake_case。表/列/索引名只接受字母或下划线开头的 ASCII 字母、数字、下划线，MySQL 最长 64 字符；自动长索引名截断并加稳定 hash。SQL 标识符加反引号，值使用参数绑定。

| Java DEFAULT 类型 | MySQL | 自动 DEFAULT |
| --- | --- | --- |
| byte / short / int / long | TINYINT / SMALLINT / INT / BIGINT | 0 |
| float / double / boolean | FLOAT / DOUBLE / TINYINT | 0 |
| char / String | CHAR(1) / VARCHAR(255) | '' |
| byte[] | BLOB | 无 |

包装类型相同映射；primitive 与身份列 NOT NULL，其余可 null。读取 SQL NULL 到 primitive 报加载错误。TEXT 仅 String；JSON 的 String 是原始 JSON 文本，其他类型自动使用默认 JsonCodec，传入自定义 codec 可覆盖；BINARY 的 byte[] 原样传递，其他类型需要 BinaryCodec。null 不调用 codec。DEFAULT 不接受复杂对象。

`Column.defaultValue` 为受信任的 SQL 表达式，原样用于 DDL、不自动加引号。JSON/BINARY/TEXT 不自动推断默认值。JsonCodec/BinaryCodec 的 decode 接收 java.lang.reflect.Type 并返回 Object，保留 List<Item> 等泛型信息。

字段读写转换、SELECT/INSERT/UPDATE/DELETE SQL、参数字段顺序在初始化时生成。UPDATE 排除 Id/GroupKey/MapKey，只有身份字段的实体 UPDATE 为无操作。DELETE_INSERT 先编码，再在一个显式事务内删除并插入当前批次。

Schema 查询 information_schema，只创建缺失结构。已有字段检查基本类型；不同 VARCHAR 长度不做完整兼容判定，也不自动迁移默认值/nullable。检查单列主键与显式索引的列顺序及唯一性；不会推断 GroupKey 索引。并发 Schema 迁移应由部署编排串行执行。

MysqlAccess 提供 queryOne/query/update/batchUpdate/transaction。queryOne 零行 null，多行抛 MysqlException；query 零行空 List。批量参数由写回层切分，Access 不再切分。事务回调获得 MysqlTransaction，同一连接执行、成功 commit、异常 rollback、恢复 autoCommit 并关闭；对象只在回调线程/作用域有效。

JdbcMysqlAccess 每次操作从 DataSource 获取连接并释放。普通操作遵循 DataSource 的默认 autoCommit，应用通常应配置 true。MysqlException 保留 SQL、SQLException cause、sqlState()/vendorCode()。底层不会自动重试或映射数据库业务错误。

### 6.1 预编译和运行期工作

初始化时确定列顺序、字段 VarHandle、值编码器、SELECT/INSERT/UPDATE/DELETE 模板和索引描述。运行期按既定顺序读取字段并绑定参数，不重新扫描全部注解或拼接业务条件。

| 操作 | 关键约束 |
| --- | --- |
| load | 按唯一主键查询，创建 Entity 并直接写字段 |
| loadGroup | 使用全部组键条件，返回整组 |
| INSERT | 写入所有显式持久化列，不提前 SELECT 检测存在 |
| UPDATE | 主键定位，仅更新非身份列；无可变列时不发空 UPDATE |
| DELETE | 只依赖主键，不需要读取实体 |
| DELETE_INSERT | 编码当前 batch 后，在同一小事务内删除并插入 |

JdbcMysqlAccess 是数据库访问边界，不负责 Repository 变更合并和业务重试。transaction 回调只可在当前线程和作用域使用，不能把 MysqlTransaction 缓存起来供后台异步任务继续操作。事务针对该回调，不把整个 flush 或多个 Repository 自动纳入其中。

Schema 初始化属于保守增量维护：发现缺表/缺列/显式索引时补齐，发现明显冲突则失败。不支持的迁移由部署工具负责；不能因初始化成功就推断已有 VARCHAR 长度、默认值或 nullable 均与 Java 声明完全一致。


<a id="default-json-field-binding"></a>

### 6.2 默认 JSON 字段类型绑定

JsonCodec.defaultCodec() 返回配置固定的共享默认 Jackson codec。JsonCodec.decoder(Type) 在初始化时绑定解码器；decoder(Type, Class<?> initializedType) 还接收状态字段初始实现类，或 null。已有只实现 encode/decode 的自定义 codec 通过默认方法保持源码兼容，可覆盖任一 decoder 方法决定表示。MysqlEntityMapper(access) 和 null JSON codec 自动使用默认值，日志映射也相同。logCodecs 只覆盖日志；jsonCodec/binaryCodec 配置 Builder 的隐式 MySQL Mapper 及未覆盖的日志默认值。

包含非 String JSON 列的状态映射会在初始化时调用一次实体无参构造，读取字段初始化结果。默认解码按完整字段 Type 与非 null 初始实现类特化 Jackson JavaType，每个字段复用一个 ObjectReader。Map<Integer,Long> 初始化为 new ConcurrentHashMap<>() 时，加载后仍为 ConcurrentHashMap<Integer,Long>；ArrayList/LinkedHashSet 初始化实现同样保留。原型及其可变字段值不会保留为已加载实体或共享集合默认值。构造与初始化代码应稳定，不能获取外部资源。仅顶层持久化 JSON 字段提供初始化实现类，不递归发现任意运行期子类型。日志映射不构造原型，不要求日志无参构造。

初始值为 null 时使用声明 Type 和 Jackson 通常的容器实现。raw/擦除类型无法恢复缺失泛型。默认 codec 可读取私有字段；普通 POJO/record 与嵌套声明集合按 Jackson 构造规则支持。JDK 内部不可变包装、抽象类、特殊构造及应用特定多态可能需要自定义 codec。默认解码检查初始化类型，发生实现替换时明确拒绝，不静默更换；不承诺保持任意对象身份或集合比较器/配置。非法输入和多余 JSON token 抛带原因的 IllegalArgumentException，Repository 加载包装为 DataLoadException 且不建立负缓存；编码失败走既有保存/重试/错误处理流程。SQL null 不调用 codec，String JSON 原样传递。默认不启用类型自动识别。JSON 格式迁移及既有存储兼容由应用负责。

源码及验证：[MysqlFieldMeta](../../game-data/src/main/java/cn/managame/data/mysql/MysqlFieldMeta.java)、[MysqlMappingTest](../../game-data/src/test/java/cn/managame/data/mysql/MysqlMappingTest.java)、[MysqlBuilderTest](../../game-data/src/test/java/cn/managame/data/MysqlBuilderTest.java)。测试覆盖启动字段绑定、泛型数字键/值、初始化容器类型、嵌套私有对象、自定义覆盖、DataSource 借用关闭、写入/日志以及修正非法 JSON 后重新加载。Builder 测试用 H2 适配 MySQL 元数据查询，并以 CLOB 保存 JSON，验证框架行为，不代表真实 MySQL JSON/DDL 集成验证。\
\
## 7. MongoDB 映射

Collection.name 必填，只有 @Field 持久化。普通字段名默认 snake_case；Id 固定 _id，配置成其他名字初始化失败。只接受简单 ASCII 存储名称。MongoIndex.fields 使用数据库字段名，全部升序；不支持 TTL/text/geo/partial 等高级索引。

MongoEntityMapper 可接收 MongoDatabase 或 MongoAccess。DriverMongoAccess 使用数据库 CodecRegistry，不绕经 JSON 文本。支持标量、byte[]、List/Set/Collection、Map<String/Integer/Long,V>；嵌套值按 Type 编译。具体复杂类由应用在 MongoDatabase 上配置 CodecRegistry（例如 POJO codec 或自定义 Codec）；其他参数化自定义类暂不支持，初始化明确失败。

加载通过无参构造 + VarHandle；缺失字段保留构造器默认值，显式 BSON null 不可写 primitive。UPDATE 使用 ordered bulk replacement 且 upsert=false；DELETE_INSERT 使用 upsert=true；DELETE 为 _id 的 $in。默认驱动批量可能部分成功，重试须考虑这一点。

### 7.1 与 MySQL 的相同点和差异

两个 Mapper 都按 EntityMeta 和持久化主键接收变更，失败均回到统一保存流程。它们不会为了接口统一而伪装成完全相同的数据库操作：

| 意图 | MySQL | MongoDB |
| --- | --- | --- |
| UPDATE | 非身份列全量更新 | 完整 Document replacement，upsert=false |
| DELETE_INSERT | 删除+插入的小事务 | replacement，upsert=true |
| 类型扩展 | JsonCodec / BinaryCodec | MongoDatabase CodecRegistry |
| 结构初始化 | 表、字段与显式索引 | collection 与显式升序索引 |

Mongo 的 replacement 不是字段级 $set，因此外部系统添加但不在映射中的 Document 字段不能依赖本组件替它保留。ordered bulk 也不是批次事务：前面操作可能已经成功，重试策略仍需处理部分成功。


## 8. Log

LogRepository 不要求 Id 或无参构造，不调用 EntityMapper。使用 @Table/@Column 编译 INSERT 映射，不查询或修改 Schema；日志表由外部服务准备。

@PartitionKey 至多一个，可以不持久化该字段。VALUE 支持整数/String，后缀只接受 ASCII 字母、数字、下划线；DAY/MONTH/YEAR 要求 long/Long Unix epoch milliseconds，按 partitionZone 生成 uuuuMMdd/uuuuMM/uuuu。没有分表字段则直接写基础表。

insert 先验证类型与分表值，再进入 ConcurrentLinkedQueue；不序列化快照。保存线程分批 drain、按物理表分组，并通过相同错误/重试路径执行。进入队列后不得修改日志。

### 8.1 drain 与分表的运行边界

每次 drain 先取得本轮队列数量边界，再按 batchSize 轮流 poll。并发生产可能影响实际观察到的边界，但本轮不会为了追上持续追加而无限循环。取出一个 batch 后按物理表分组，每组经同一 execute/error/retry 路径写入。

例如同一批有昨天与今天的事件，会得到两个物理表 INSERT；它们独立成功或失败，不构成跨表事务。close 先阻止继续插入，再排空剩余日志，避免“有限周期处理”被误解为停机也只处理一个 batch。

分表字段在接纳时校验，但排队期间不会冻结 Java 对象。接纳后篡改分表值仍可能在保存时造成错误或写错表，这也是禁止修改已提交日志的原因。

### 8.2 扩展实现时需要保留的边界

扩展 EntityMapper 时，以 EntityMeta 区分实体，保证加载失败不会返回不存在，明确批量部分成功与 DELETE_INSERT 语义，并保留异常原因。新增存储类型前应同时补标准中的可观察差异和本 Java 规范的映射规则。

新增 codec 不应把 DEFAULT 复杂对象悄悄变成 JSON，也不应丢失字段 Type 的泛型信息。新增日志后端、日志 Schema 管理、缓存容量、失败回灌或严格缓冲交换协议，都属于尚未实现的扩展，不能通过添加一个配置示例就声称已支持。


## 9. 异常与验证

DataLoadException：存储加载或加载结果非法。DataOperationException：未初始化、已关闭、组未加载、非法变更序列。DataSaveException：close 发现最终保存失败。启动配置/元数据错误通常为 IllegalArgumentException，Schema/数据库错误保留底层异常。共享失败码见 [Core](OGBS-Core-1.0.zh-CN.md#data-常量)。

已验证：Repository/缓冲契约测试、H2 MySQL 模式上的真实 JDBC 查询与事务、SQL 映射/Schema 生成、BSON CodecRegistry 编解码及 Mapper 调用契约。H2 的 Schema 测试使用信息表适配桩，不能等同 MySQL 实机验证；Mongo 测试使用记录型 MongoAccess，不能等同服务器验证。

实机测试 [DatabaseIntegrationTest](../../game-data/src/test/java/cn/managame/data/DatabaseIntegrationTest.java) 由 OGBS_DATA_MYSQL_URL / OGBS_DATA_MONGO_URI 启用；默认跳过。未配置上述环境变量时不运行实机测试，当前尚未验证真实 MySQL/MongoDB 服务器、生产性能或长时间故障恢复。


### 9.1 错误扩展接口

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

DataOperation 包括 INSERT、UPDATE、DELETE、DELETE_INSERT、LOG_INSERT。这里的 operation 表示实际失败的持久化意图，未必等于最后一次 Repository 方法名，例如 DELETE 后 INSERT 合并后报告 DELETE_INSERT。attempt 从 1 开始；重试成功不会再调用最终错误 Handler。

异常诊断保留底层 cause。策略可据 MysqlException 的 sqlState/vendorCode 或 Mongo 驱动异常自行判断，框架不内置可重试错误清单。错误码的数值仍以 Core 为唯一来源，不在这里重新分配。

### 9.2 易错契约的测试定位

| 契约 | 测试类与方法 |
| --- | --- |
| insert/delete 不提前 SELECT，加载错误不负缓存 | DataContractTest.negativeCacheDeleteAndInsertNeverSelect / loadFailuresAreNotNegativeCached |
| 必须先加载组且返回同一 Map | DataContractTest.groupsRequireExplicitLoadAndReturnSameMap |
| 按数据库主键删除组内实体 | DataContractTest.loadedGroupDeletesByDatabaseId |
| 合法/非法合并矩阵 | DataContractTest.legalMergeTable / illegalMergeTable |
| 非法 INSERT 不替换缓存对象 | DataContractTest.illegalInsertDoesNotReplaceCachedEntity |
| 阶段顺序、batch 切分、不跨缓冲合并 | DataContractTest.batchesOrderedAndSplitAndNeverMergeAcrossBuffers |
| 重试可选、次数有限、失败后继续处理 | DataContractTest.retryIsOptInBoundedAndKeepsBatchContext / finalFailureAndHandlerFailureDoNotStopLaterBatchesAndCloseReportsIt |
| 关闭等待当前 pipeline 并处理下一缓冲 | DataContractTest.closeWaitsForActivePipelineAndFlushesNextBuffer |
| 错误回调中关闭不死锁 | DataContractTest.closeFromCallbackIsRejectedWithoutDeadlock |
| 日志分表、关闭 drain、不初始化 Schema | LogContractTest.closeDrainsPartitionsAndBatchesWithoutSchemaInitialization |
| 日志失败使用同一 Handler 且继续后续分区 | LogContractTest.logFailuresReachSameHandlerAndDoNotBlockLaterPartitions |

这些入口不能证明固定 100ms 宽限期在任意线程暂停下安全，也不能替代真实数据库的故障与兼容性验证。涉及这类保证的新增需求应增加相应实现及验证，而不是扩大现有测试结论。
