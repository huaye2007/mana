[English](README.en.md) | 中文

# game-jpa

## 构建与验证

`game-jpa` 使用 Java 25，与父项目保持一致。默认测试命令不需要 Docker：

```powershell
mvn "-Dmaven.repo.local=..\.m2" test
```

真实 MySQL 和 MongoDB 集成测试位于 `src/it/java`，仅在启用 `integration-tests` profile 时运行：

```powershell
mvn "-Dmaven.repo.local=..\.m2" -Pintegration-tests verify
```

`game-jpa` 是面向游戏服务器的数据访问框架。它提供轻量 Repository API、实体元数据解析、关系型数据库/文档数据库适配、本地缓存、异步批量写回、分片路由、生命周期钩子和指标采集。

它不是完整 JPA/Hibernate 替代品，也不追求复杂 ORM 能力。它更适合游戏服常见的数据模型：

- 玩家、角色、背包、邮件、活动数据等高频读写实体
- 游戏逻辑先修改内存态，再合并批量落库
- 按玩家 id、角色 id、日期、区服等维度分库分表
- 需要明确、可控、低魔法的持久化 API

## 模块说明

| 模块 | 说明 |
| --- | --- |
| `game-jpa-core` | 核心 SPI、元数据、Repository 工厂、路由、生命周期、指标采集（含默认内存实现）、写任务契约 |
| `game-jpa-starter` | 启动器和运行时上下文 |
| `game-jpa-rdb` | 关系型数据库注解、元数据、查询规格、Repository API |
| `game-jpa-rdb-mysql` | MySQL 执行器、SQL 方言、Hikari 数据源工厂、Schema 生成器 |
| `game-jpa-docdb` | 文档数据库注解、元数据、查询/更新规格、Repository API |
| `game-jpa-docdb-mongo` | MongoDB 执行器 |
| `game-jpa-cache` | 存储无关的唯一键缓存和组合键缓存 |
| `game-jpa-rdb-cache` | RDB 缓存 Repository 和批量写回 |
| `game-jpa-docdb-cache` | DocDB 缓存 Repository 和批量写回 |
| `game-jpa-async-write` | 内存异步写队列和刷盘调度器 |

## 快速开始：RDB + MySQL + 缓存写回

添加依赖：

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-jpa-starter</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-jpa-rdb-cache</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-jpa-rdb-mysql</artifactId>
    <version>1.0.0</version>
</dependency>
```

`game-jpa-rdb-mysql` 会带上 HikariCP 和 MySQL JDBC driver；业务方通常不需要再额外声明 `mysql-connector-j`。

定义实体：

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
    // 字段必须显式 @Column 才会映射为列；@Id/@ShardKey/@Version 等标记需与 @Column 同时声明
    @Id
    @ShardKey
    @Column
    private long id;

    @Column
    private long roleId;

    @Column(length = 64, defaultValue = "newbie")
    private String name;

    // 复杂类型默认按 JSON 列存储；这里显式声明 type 以同时给出 JSON 默认值
    @Column(type = ColumnType.JSON, defaultValue = "{}")
    private Map<String, Integer> bag = new LinkedHashMap<>();

    @Version
    @Column
    private long version;
}
```

定义 Repository：

```java
import cn.managame.jpa.rdb.cache.IRdbUniqueCacheRepository;

public interface PlayerRepository extends IRdbUniqueCacheRepository<Player, Long> {
}
```

启动框架：

```java
import cn.managame.jpa.rdb.cache.RdbCacheModule;
import cn.managame.jpa.rdb.mysql.MysqlDataSourceFactory;
import cn.managame.jpa.rdb.mysql.MysqlStorage;
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
        .use(MysqlStorage.using(dataSource).updateSchema())
        .use(RdbCacheModule.defaults())
        .flushIntervalMillis(5000)
        .flushThreadMode(FlushThreadMode.VIRTUAL) // 仅选择线程类型；两种模式的 worker 数都严格有界
        .flushThreadCount(2)                      // 不同物理表的最大并发数
        .maxFlushBatchSize(500)
        .maxRetries(3)
        .maxPendingWriteTasks(1_000_000)
        .bootstrap("cn.managame.game.domain");

PlayerRepository players = context.getRepository(PlayerRepository.class);
```

`bootstrap(String... basePackages)` 会递归扫描指定包及其子包，只注册当前已启用扩展能够识别的注解实体，例如 RDB 的 `@Entity` 和 DocDB 的 `@Document`。可以一次传入多个业务包，不需要再维护实体类清单。

`MysqlStorage` 统一持有 DataSource、MySQL 执行器和可选 Schema 策略，因此 DataSource 只配置一次；`RdbCacheModule` 只选择缓存 Repository，不再负责创建数据库执行器。`updateSchema()` 只在持久化上下文初始化阶段同步普通表，并跳过带 `@ShardKey` 的实体。运行期写路径不会自动创建缺失表或物理分表；分表 DDL 必须通过显式迁移或预生成脚本管理。`columnAutoWiden` 默认关闭，不会在写路径修改列；字符串/二进制字段长度不足会翻译为 `DataTooLargeException` 并按异步策略重试。只有明确接受写路径执行 `ALTER TABLE` 时才应显式调用 `columnAutoWiden(true)`。

游戏库和日志库使用不同 DataSource 时，仍然只启用一个 `MysqlStorage`：

```java
MysqlStorage mysql = MysqlStorage.using(gameDataSource)
        .addDataSource("log", logDataSource)
        .updateSchema();

GameJpaContext context = new GameJpaBootstrap()
        .use(mysql)
        .use(RdbCacheModule.defaults())
        .dataSource("cn.managame.log", "log")
        .bootstrap("cn.managame.game", "cn.managame.log");
```

也可以在日志实体上声明 `@Table(dataSource = "log")`。读写和 `updateSchema()` 使用相同的最终 home DataSource 解析规则；Schema 更新会按数据源拆分元数据，游戏表只更新游戏库，日志表只更新日志库。带 `@ShardKey` 的实体仍由受控 migration 管理。

异步写回只调度发生过提交的物理表，不周期扫描空桶。同一实体通道、同一物理表始终只有一个在途批次，超过 `maxFlushBatchSize` 的数据按顺序分片；不同物理表最多按 `flushThreadCount` 并行。瞬时批次错误整批回灌，数据级错误使用二分拆批定位，不再直接放大为整批逐条写入。

使用缓存读写：

```java
Player player = players.cacheLoad(10001L);
// 修改 player 的内存状态
players.cacheUpdate(player);

// 异步写回会从实体 @ShardKey 自动路由
players.cacheDelete(10001L);
```

关闭服务器时必须关闭上下文：

```java
context.close();
```

`close()` 会先拒绝新的异步写提交，再停止刷盘调度器，并对已经进入内存队列的任务执行最后一次 flush。
内置异步队列不再提供本地文件持久化；进程崩溃不能保证内存任务不丢。必须零丢失的数据应使用同步 Repository 写入或外部可靠队列/流水线。

## 常用接入模式

### 普通 RDB

使用 `MysqlStorage.using(dataSource)` 配合 `RdbModule.defaults()`，业务 Repository 继承 `RdbRepository<T, ID>`。

适合强一致写路径、后台管理、低频实体。

### RDB 缓存写回

使用 `MysqlStorage.using(dataSource)` 配合 `RdbCacheModule.defaults()`，业务 Repository 继承：

- `IRdbUniqueCacheRepository<T, ID>`：主键唯一缓存
- `IRdbMultiCacheRepository<T, ID>`：组合键多记录缓存

如果某张小表属于配置/字典类数据，需要在服务启动时全量加载并长期驻留内存，可以在实体类上标记
`@Warmup`，并让业务 Repository 继承 `IRdbUniqueCacheRepository<T, ID>`：

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

启用 `RdbCacheModule` 后，`GameJpaBootstrap.bootstrap(...)` 会在上下文创建完成后自动调用
`findAll()` 预热这类实体；后续 `cacheLoad(id)` 会复用同一份无过期、无容量淘汰的内存缓存。
该能力只适合数据量可控的非分片表。

创角流程里，如果业务能判断某个 role 刚创建、相关表在数据库里一定没有历史记录，可以把这个判断交给缓存配置：

```java
RdbCacheModule cacheModule = RdbCacheModule.defaults()
        .newRoleDetector(roleId -> roleService.isNewlyCreated(roleId), Duration.ofMinutes(10))
        .defaultConfig(CacheConfig.defaults());

GameJpaContext context = new GameJpaBootstrap()
        .use(MysqlStorage.using(dataSource).updateSchema())
        .use(cacheModule)
        .bootstrap("cn.managame.game.domain");
```

角色相关表需要显式标出 roleId 字段，避免框架靠字段名或 key 顺序猜测：

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

`newRoleDetector` 是全局设置，不跟单张表的 `CacheConfig` 绑定。业务使用方法不变，仍然调用 `cacheLoad(...)`。缓存未命中时，框架会先按 `@RoleId` 提取 roleId，并短时间缓存新号判断；如果判定为新号，唯一键返回 `null`，组合键返回空列表，不触发数据库 loader。RDB 组合键会从带 `@RoleId` 的 `@CacheKey` 字段对应的 part 取值；唯一键只有在 `@RoleId` 同时是 `@Id`，或 `cacheLoad(id, routingKey)` 的 routingKey 对应 `@RoleId @ShardKey` 时才能在查库前判断。

适合游戏服热数据。注意它是最终一致写回，进程崩溃可能丢失尚未刷盘的内存任务。

### Mongo DocDB

使用 `DocdbModule` 或 `DocdbCacheModule`，执行器为 `MongoDocExecutor`。

适合结构灵活、文档型的数据。缓存写回与 RDB 侧能力对齐：

- `IDocUniqueCacheRepository<T, ID>`：主键唯一缓存
- `IDocMultiCacheRepository<T, ID>`：组合键多记录缓存（实体字段标 `@CacheKey(order = N)`）
- `@Warmup` 文档实体在启动时自动 `findAll()` 预热并常驻内存（仅限非分片小集合）

嵌套 POJO 字段自动映射为嵌套文档，无需注解；注册了自定义 `TypeConverter` 的类型优先走转换器。

`MongoDocExecutor` 按驱动异常类型和官方 retryable error label 翻译异常，不维护可重试错误码表：网络故障为
`ConnectionException`，选主切换/可重试写冲突为 `ConcurrentWriteException`，超时为 `WriteTimeoutException`，
文档超过 BSON 上限为 `DataTooLargeException`。这些异常均继承 `RetriableWriteException`，异步写回会重试到
`maxRetries`；重复键包装为 `DuplicateKeyException`，其余确定性失败在通知 permanent failure handler 后丢弃。

## 分片

内置策略：

- `HashShardingStrategy`
- `RangeShardingStrategy`
- `DateShardingStrategy`
- `ConsistentHashShardingStrategy`

实体上最多标记一个 `@ShardKey`。写入、批量写入和缓存写回会从实体上的 `@ShardKey` 字段自动路由；条件查询在 `RdbQuerySpec` / `DocQuerySpec` 中包含单值 `@ShardKey` 条件时也会自动路由。只有按 id 查询/删除且 `@ShardKey` 不是主键时，框架无法从 id 推导分片，需要显式 routingKey：

```java
repo.findById(id, routingKey);
repo.deleteById(id, routingKey);
cacheRepo.cacheLoad(id, routingKey);
cacheRepo.cacheDelete(id, routingKey);
```

如果希望唯一键缓存的 `cacheLoad(id)` 完全不传路由参数，最简单的建模方式是让 `@ShardKey` 和 `@Id` 标在同一个字段上。`cacheInsert(entity)`、`cacheUpdate(entity)` 和组合键缓存写回都会从实体注解字段自动读取路由值。

RDB 条件查询和计数使用 `RdbQuerySpec`，字段名同样使用 Java 属性名：

```java
repo.findBySpec(new RdbQuerySpec().eq("roleId", roleId).limit(100));
long count = repo.count(new RdbQuerySpec().eq("roleId", roleId).gte("level", 10));
```

## 生产注意事项

- 不要在大表热路径使用 `findAll()` 或 `warmUpAll()`。
- `@Warmup` 会在启动时全量加载整张表并常驻内存，只用于配置表、字典表等小表；带 `RoutingStrategy` 的分片表不支持自动全量预热。
- `newRoleDetector` 只适用于业务已明确知道后端无数据的路径，例如新角色初始化；角色相关表应标注 `@RoleId`，不要把普通登录判断成新号，否则可能把真实数据库数据当成不存在。
- 缓存写回是最终一致，不适合作为必须落库成功后才能响应的强一致路径。
- 缓存写回保留实体对象引用以避免快照开销；提交后不要继续并发修改同一实体对象。
- RDB 缓存写回会对带 `@Version` 的 MySQL 实体执行版本感知 upsert；版本不匹配按确定性失败处理，通知 permanent failure handler 后丢弃。
- 关注 `asyncWrite.pending` 指标；它同时包含排队和在途任务，也是 `maxPendingWriteTasks` 背压依据。达到阈值后会拒绝新的唯一键写任务，同一 pending key 的更新仍会合并。
- 配置 async write permanent failure handler，用于告警或补偿。
- 服务停机必须调用 `GameJpaContext.close()`。
- 缓存写回分片路由由执行层从实体 `@ShardKey` 自动提取；`cacheLoad(id)` / `cacheDelete(id)` 只有在实体已在缓存中或 `@ShardKey` 就是主键时能自动路由，其他场景使用存储相关 Repository 的 `cacheLoad(id, routingKey)` / `cacheDelete(id, routingKey)`。
- 查询 API 使用 Java 属性名，不使用裸 SQL 列名。
- 生产环境如果要启动时自动补表结构，在 `MysqlStorage.using(dataSource)` 上显式调用 `updateSchema()`；它只会创建缺失表、添加缺失列/索引，不会删除列、重命名列或修改已有列类型/默认值。上线前可用 `generateSchemaOnly()` 生成 diff 报告，`-- MANUAL MIGRATION REQUIRED` 注释项应进入受控 migration。
- 带 `@ShardKey` 且安装了 `RoutingStrategy` 的 RDB 实体不支持自动 schema 同步；分片物理表/多数据源 DDL 应使用受控 migration 或预生成脚本。
- `@Column(defaultValue = "...")` 只对 `null` 生效；声明默认值的字段应使用包装类型，例如 `Integer`、`Boolean`，不要使用 `int`、`boolean`。

## 测试

运行 `game-jpa` 全模块测试：

```bash
mvn "-Dmaven.repo.local=..\\.m2" test
```

当前测试覆盖：

- 分片策略
- RepositoryFactory 优先级
- 多层泛型 Repository 类型解析
- 异步写队列提交期路由、按物理表合并/追加与并发 drain
- 组合键缓存一致性
- 分片提交期路由（RDB/DocDB）与日志异步追加

## 扩展

新增存储后端的一般步骤：

1. 定义注解和元数据解析器，或复用现有元数据模型。
2. 实现存储执行器。
3. 实现 `RepositoryFactory`。
4. 实现 `GameJpaExtension` 注册 resolver、factory、executor。
5. 如果需要缓存写回，向 `WriteChannelRegistry` 注册对应 `WriteChannel`（实体缓存用 `BatchFlusher` 合并通道，日志用 `AppendFlusher` 追加通道）。
6. 补充元数据、路由、Repository 创建、失败行为测试。

新增缓存实现：

1. 实现 `CacheStore<K, V>`。
2. 实现 `CacheStoreFactory`。
3. 通过 `CacheConfig.builder().cacheStoreFactory(...)` 注入。
