# game-data

[English](README.md) | **[简体中文](README.zh-CN.md)**

## 规范文档

| 标准规范（语言无关） | Java 开发规范 |
| --- | --- |
| [OGBS Data Specification](../docs/ogbs/OGBS-Data-1.0.zh-CN.md) | [OGBS Data Java Development Specification](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md) |

组件行为与 Java 实现分别维护在上述两份规范中，本文提供使用入口。

OGBS Data 的 Java 25 参考实现：数据缓存在内存里，业务读写只碰内存；变更由后台按表批量、异步写回 MySQL 或 MongoDB。另有 MySQL 追加日志。

## 快速上手

**1. 实体。** 每个非 static、非 transient 的实例字段都会保存，不需要逐个加注解（[D-MAP-01](../docs/ogbs/OGBS-Data-1.0.zh-CN.md#d-map-01)）；集合、Map、对象自动存为 JSON 列。

```java
@Table(name = "player")
public class Player {
    @Id private long id;
    private String name;
    private int level;
    private Map<Integer, Long> items = new ConcurrentHashMap<>();   // 保存线程会读它，用线程安全集合
    private transient long lastSeen;                                // transient：不保存

    Player() {}                          // 加载用的无参构造器
    Player(long id) { this.id = id; }    // getOrCreate 的工厂
    void levelUp() { level++; }
}
```

只有改列名、指定类型、默认值或 VARCHAR 长度时才写 `@Column`。字段不能是 final（启动时报错并提示去掉 final 或标为 transient）。

**2. Repository。** 继承一个基类即可，不用写方法。

```java
public class Players extends SingleRepository<Long, Player> {}
```

**3. 构建。** DataSource 与连接池由应用提供；URL 上加 `rewriteBatchedStatements=true`。

```java
GameData data = GameDataBuilder.builder()
        .mysql(dataSource)                  // jdbc:mysql://host:3306/game?rewriteBatchedStatements=true
        .repositories(Players.class)
        .build();                           // schema 默认 CREATE：建缺失的表和列
Players players = data.repository(Players.class);
```

**4. 读写。**

```java
Player p = players.getOrCreate(id, Player::new);       // 不存在时创建并登记 INSERT
players.update(id, Player::levelUp);                   // 取出、修改、登记一步完成
players.delete(id);
```

`update(id, change)` 代替"`get` → 改字段 → `update(entity)`"三步，不会出现改完忘记登记的情况；实体不存在时返回 false。直接改字段后再调用 `update(entity)` 也可以。Group 实体（一个玩家的多条任务等）用 `GroupRepository`，`getGroup(key)` 返回只读视图，`update(groupKey, mapKey, change)` 用法相同。日志用 `LogRepository.insert`。

Spring 应用使用 [game-spring](../game-spring/README.zh-CN.md)：`@Repository` Bean 自动注册，调优项通过 `game.data.*` 属性设置（[Spring Java 规范](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.zh-CN.md)）。

## 性能

业务线程的读写都只碰内存。保存线程每 `flushInterval`（1 秒）一轮：同一张表的变更合并成批次一起保存，不同的表在 `saveParallelism`（4）个线程上并行，同一张表永远只有一个线程在写。MySQL 上 UPDATE 由组件合并成每条最多 100 行的多行语句，INSERT 由驱动在 `rewriteBatchedStatements=true` 时合并。

本机 MySQL 9.7 回环、HikariCP 连接池下实测（[测量记录](../docs/reviews/2026-10-21-data-save-path-parallel.zh-CN.md)）：

| 场景 | 行/秒 |
| --- | --- |
| 单表 UPDATE，batchSize 500 | 约 45,700（逐行 UPDATE 时约 14,300） |
| 单表 UPDATE，batchSize 2000 | 约 72,500（逐行 UPDATE 时约 9,300） |
| 4 张表 UPDATE，saveParallelism 1 → 4 | 约 46,300 → 119,800 |
| 4 张表 INSERT，saveParallelism 1 → 4 | 约 51,200 → 133,900 |

常用设置（完整列表与边界见 [Java 规范 §3](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md#3-构建与生命周期)）：

| 设置 | 默认值 | 什么时候调 |
| --- | --- | --- |
| batchSize | 500 | INSERT 为主或单表写入量很大时用 2000 |
| saveParallelism | 4 | 表多、数据库能承受更多并发连接时加大；连接池要比它大 |
| flushInterval | 1 秒 | 崩溃最多丢失约一个间隔的变更 |
| maxPendingChanges | 100,000 | 等待保存的实体上限；满了之后新增实体的写入先等待，再被拒绝 |
| maxCacheEntries | 每个 Repository 1,000,000 | 超过时淘汰最久未读的干净条目；未保存的条目从不淘汰 |

## 必须遵守的约定

- **集合字段用线程安全集合。** 保存线程编码时会读取实体字段，不做快照。ConcurrentHashMap、CopyOnWriteArrayList 等；启动时会对 HashMap、ArrayList 或未初始化的集合字段记 WARNING（[D-OWN-01](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md#21-缓存与修改路径)）。
- **不要在 Netty EventLoop 上读取未缓存的数据。** 加载是同步的；在默认的虚拟线程 Route 上调用即可。
- **连接池要足够大。** 同时在保存的表数加上并发加载数。
- **MySQL 保持 `useAffectedRows=false`（驱动默认）。** 多行 UPDATE 靠"找到的行数"判断有没有缺行。
- **保存失败的处理。** 可重试的失败（断线、超时、死锁）保留到下一轮；不可重试的坏行被单独拆出、丢弃并交给 `errorHandler`，在那里归档。关闭时在 `shutdownTimeout` 内尽量保存，剩下的也交给 `errorHandler`。

## 存储

MySQL 使用应用提供的 DataSource/JDBC Driver；Mongo 使用应用提供的 MongoDatabase/CodecRegistry（`MongoEntityMapper`），框架不关闭这些外部资源。Mongo driver 在 game-data 中为 optional，使用 Mongo 时需显式声明 org.mongodb:mongodb-driver-sync。日志是独立存储，由 `logMysql(DataSource)` 指定，`mysql(DataSource)` 不会服务日志。

`schema` 决定启动时对数据库做什么：`CREATE`（默认）建缺失的表、列与声明的索引并校验已有结构；`VALIDATE` 不发 DDL，缺失或过窄即失败；`NONE` 不读目录。`CREATE` 容忍同一服务的另一个实例同时应用同样的结构（[D-STORE-02](../docs/ogbs/OGBS-Data-1.0.zh-CN.md#8-存储适配语义)）。JSON 列按字段的完整泛型类型与初始化值的类绑定，例如 `ConcurrentHashMap<Integer,Long>`，不需要手动注册类型（[JSON 绑定](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md#default-json-field-binding)）。

`GameData.flush()` 同步保存调用前接纳的变更；`GameData.stats()` 提供待保存数、缓存条目、丢弃数等计数（[flush 与 DataStats](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md#11-显式-flush-与-datastats)）。

## 测试

```shell
mvn -pl game-data -am test
```

默认测试不需要外部服务：内存 Mapper（用法见 [DataContractTest](src/test/java/cn/managame/data/DataContractTest.java)）、H2 的 MySQL 模式与 BSON 编解码。实机测试默认跳过，必须显式提供环境变量：

```powershell
$env:OGBS_DATA_MYSQL_URL = 'jdbc:mysql://127.0.0.1:3306/ogbs_data_test?rewriteBatchedStatements=true'
$env:OGBS_DATA_MYSQL_USER = 'test_user'
$env:OGBS_DATA_MYSQL_PASSWORD = 'test_password'
$env:OGBS_DATA_MONGO_URI = 'mongodb://127.0.0.1:27017'
mvn -pl game-data -am test "-Dtest=DatabaseIntegrationTest" -Dsurefire.failIfNoSpecifiedTests=false
```

MySQL 使用专门的可丢弃测试数据库：测试创建并删除 ogbs_data_probe、ogbs_data_flood 表，检测到已有同名表会拒绝运行。Mongo 创建随机 ogbs_data_test_* 数据库并在结束后删除。不要指向生产库。保存吞吐的压测见 [game-benchmark](../game-benchmark/README.zh-CN.md) 的 DataSavePathLoad。
