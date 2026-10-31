[English](README.md) | 中文

# managame-data-mysql

把 [managame-data](../managame-data/README.zh-CN.md) 的实体和日志存进 MySQL：`MysqlEntityMapper` 用于 SingleRepository
和 GroupRepository，`MysqlLogWriter` 用于 LogRepository。依赖 managame-data 和 JDK 自带的 JDBC；驱动和连接池由应用提供。

```java
DataSource dataSource = ...;   // jdbc:mysql://host:3306/game?rewriteBatchedStatements=true，经连接池
GameData data = GameData.builder()
        .repositories(new MysqlEntityMapper(dataSource, json), List.of(Players.class, Items.class))
        .logRepositories(new MysqlLogWriter(logDataSource, json, ZoneId.of("Asia/Shanghai")), List.of(ItemLogs.class))
        .errorHandler(failures::save)
        .build();
```

## 表

```java
@Table(name = "player")
public class Player {
    @Id private long id;
    private String name;
    @Column(name = "lv") private int level;                         // 列名另取
    private Map<Integer, Long> items = new ConcurrentHashMap<>();   // JSON 列
    Player() {}
}
```

- 实体或日志需要 `@Table(name)`。每个要保存的字段是一列，列名是字段名的下划线形式（`configId` 是 `config_id`），或由
  `@Column(name)` 指定。注解在 `cn.managame.data.mysql.annotation` 包里，Mapper 和 `JsonCodec` 在 `cn.managame.data.mysql`。
- 基本类型及其包装类、String、char、byte[] 各占一列。其他类型（集合、Map、对象、枚举）通过交给 Mapper 或 Writer 的
  `JsonCodec` 存成 JSON 列，`JsonCodec` 由应用提供，例如用 Jackson 实现；JSON 列会解码成字段初始化时的那个类。
- 表属于应用，由应用创建和迁移；`prepare` 在构建时把每张表读一次：表或列不存在时构建失败并抛 `DataException`
  （`DATA_LOAD_FAILED`），类没有 `@Table` 抛 `IllegalArgumentException`，有 JSON 字段却没有 codec 抛 `IllegalStateException`。

## 读实体

`load` 和 `loadGroup` 各一条 SELECT；`loadAll`（`getAll` 用它）每 1,000 个 id 一条 `WHERE id IN (...)`，用同一个连接；
`loadTable`（整表常驻的 Repository 用它）一条 SELECT 读整张表。

## 写实体

- **每批一个事务**，所以失败的批次不会留下任何东西：重写或拆成单行时都不会产生重复。删除后又插入，在同一个事务里先 DELETE
  再 INSERT。
- **INSERT** 在 URL 带 `rewriteBatchedStatements=true` 时由驱动合并成多行语句。
- **UPDATE** 以每条最多 100 行的语句发送，每一列通过 `CASE` 按 id 选值，每条语句一次往返，而不是每行一次。语句匹配的行数
  少于它列出的行数时会失败（保持驱动默认的 `useAffectedRows=false`，统计的才是匹配行数）：说明有一行不存在，若当作成功就会
  丢掉这次修改。拆成单行后，不存在的那一行交给 DataErrorHandler。
- **DELETE** 以 `WHERE id IN (...)` 发送，每条最多 1,000 个 id。
- 连接池要大于 GameData 的 `writeThreads` 加上同时进行的加载数。

## 写日志

```java
@Table(name = "item_log")
public class ItemLog {
    private long playerId;
    private int configId, delta;
    @PartitionKey(type = PartitionType.DAY) private long createdAt;   // 毫秒时间戳：item_log_20261031
    ItemLog() {}
}
```

- 日志类遵守实体的规则（字段不能是 final、有无参构造器），但没有 key。
- `@PartitionKey` 用于分表：VALUE 直接用整数或 String 字段作为后缀，DAY、MONTH、YEAR 把毫秒时间戳按 Writer 的分区时区
  （默认 UTC）格式化。首次用到时不存在的分区表会在事务之外用 `CREATE TABLE IF NOT EXISTS ... LIKE` 基表创建，因为 DDL 会
  提交事务。
- 一批日志一个事务，不管它们分到几张分区表。

## 构建与测试

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

先安装 managame-common 和 managame-data。测试通过 GameData 运行在 MySQL 模式的 H2 上，这是一个真正的内存数据库，覆盖：带
JSON 列的行，JSON 加载成初始化时的集合，整表常驻，超过一条 SELECT 上限的 `getAll`，用少量语句更新大量行，分组以及先删后插使唯一的格子
可以复用，坏行丢弃而其他照常写入，
数据库拒绝连接期间保留修改，提交未确认的插入重写而不产生重复，更新已不存在的行被报告，构建时拒绝不合适的映射，以及按分区
时区切分的按天分区日志在一批里写入。提供一个可以丢弃的库时，`MysqlIntegrationTest` 会在真实 MySQL 上运行：

```bash
MANAGAME_DATA_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/scratch?rewriteBatchedStatements=true
MANAGAME_DATA_MYSQL_USER=... MANAGAME_DATA_MYSQL_PASSWORD=...
```
