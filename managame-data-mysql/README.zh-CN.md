[English](README.md) | 中文

# managame-data-mysql

把 [managame-data](../managame-data/README.zh-CN.md) 的实体和日志存进 MySQL：`MysqlEntityMapper` 用于 SingleRepository
和 GroupRepository，`MysqlLogWriter` 用于 LogRepository。依赖 managame-data 和 JDK 自带的 JDBC；驱动和连接池由应用提供。

```java
DataSource dataSource = ...;   // 经连接池，URL 带上"驱动设置"一节的参数
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
  `@Column(name)` 指定。注解在 `cn.managame.data.mysql.annotation` 包里，Mapper、`JsonCodec` 和 `ColumnCodec` 在
  `cn.managame.data.mysql`。
- 基本类型及其包装类、String、char、byte[] 各占一列。其他类型（集合、Map、对象、枚举）通过交给 Mapper 或 Writer 的
  `JsonCodec` 存成 JSON 列，`JsonCodec` 由应用提供，例如用 Jackson 实现；JSON 列会解码成字段初始化时的那个类。想用别的
  方式存的字段可以有自己的 codec，见[按自己的方式存字段](#按自己的方式存字段)。
- 列是 NULL 时，字段保留实体构造器给的值；所以给类加一个字段后，之前存的行读出来，这个字段和新建实体时一样。
- 类没有 `@Table` 时构建失败并抛 `IllegalArgumentException`，有 JSON 字段却没有 codec 抛 `IllegalStateException`。

## 自动建表

构建 GameData 时，`MysqlEntityMapper` 会把实体的表缺的东西建出来，所以新增一个实体类、或给类加一个字段，都不用手写 SQL：

- **表**：没有就建，`@Id` 列作主键。
- **之后新增字段的列**：逐个 `ALTER TABLE ... ADD COLUMN`。
- **分组类型的唯一键**：建在 `@GroupKey` 和 `@MapKey` 列上；表上已经有以组键列开头的索引时不建。每次加载一个组都按组键
  查，而同一个组里有两行相同 map key 的话，这个组根本加载不出来。

它从不删除或修改任何东西：类型不同的列、已经没有字段对应的列，都保持原样。每建一样东西记一条 INFO。列按字段的类型创建：

| 字段 | 列 |
| --- | --- |
| long、int、short、byte | `BIGINT`、`INT`、`SMALLINT`、`TINYINT` |
| boolean、double、float、char | `TINYINT(1)`、`DOUBLE`、`FLOAT`、`CHAR(1)` |
| String | `VARCHAR(255)` |
| byte[] | `MEDIUMBLOB` |
| 存成 JSON 的字段 | `MEDIUMTEXT`：不用考虑长度，写入也比 MySQL 的 `JSON` 类型快，见[驱动设置](#驱动设置) |
| 有 `ColumnCodec` 的字段 | codec 的 `columnType()` |

- **key 和基本类型的列是 `NOT NULL`**，其他列可以是 NULL。
- **基本类型的列，默认值取无参构造器给字段的值**，所以给类加上 `int level = 1`，之前存的行里它就是 1。
- **`@Column` 可以为单个字段另行指定**，每个属性都可以不写：

```java
@Column(type = "VARCHAR(64)") private String name;                        // 列的类型
@Column(type = "JSON") private Map<Integer, Long> items = ...;            // MySQL 的 JSON 类型，用于要在库里按内容查询的字段
@Column(defaultValue = "'none'") private String title;                    // 列的 DEFAULT，按 SQL 的写法
@Column(name = "lv", type = "SMALLINT", defaultValue = "1") private int level;
```

自己管理表结构的应用，用 `createMissing(false)` 构建 Mapper。这时 Mapper 什么都不建：表或列不存在时构建失败并抛
`DataException`（`DATA_LOAD_FAILED`）；分组类型的表在组键列上没有索引时记一条 WARNING。默认建表时，如果账号没有权限执行
某条语句，同样以这个异常让构建失败。两种方式下，JDBC URL 没带 `rewriteBatchedStatements=true` 都会记一次 WARNING。

```java
MysqlEntityMapper mapper = MysqlEntityMapper.builder(dataSource).json(json).createMissing(false).build();
```

日志表不会自动建：日志类的表由应用创建，`MysqlLogWriter` 和以前一样，在构建时检查它的列能不能读。

## 按自己的方式存字段

按规则会存成 JSON 的字段，或者应用想用别的列类型存的字段，可以给它一个 `ColumnCodec`：列的类型、JDBC 绑定的值，以及
从列里读出的字段值。

```java
/** 枚举按名字存。 */
record EnumAsName(Class<? extends Enum<?>> type) implements ColumnCodec {
    public String columnType() { return "VARCHAR(32)"; }
    public Object encode(Object value) { return ((Enum<?>) value).name(); }
    public Object decode(ResultSet resultSet, int index) throws SQLException {
        String name = resultSet.getString(index);
        return name == null ? null : Enum.valueOf((Class) type, name);
    }
}

MysqlEntityMapper mapper = MysqlEntityMapper.builder(dataSource)
        .json(json)                                              // 没有 codec 又不是标量的字段，仍然由它存
        .columnCodecs(field -> field.type().isEnum() ? new EnumAsName((Class<? extends Enum<?>>) field.type()) : null)
        .build();
```

- **所有字段共用一个函数**，构建 GameData 时对每个实体类型的每个字段问一次。它拿到的是 `EntityField`：字段名、类型，
  以及可以用来读应用自己注解的 Java 字段，按哪一个判断都行。返回 null 表示这个字段按规则存。
- **`@Id` 和组键、map key 不会问**：按它们查行时，绑定的就是原值。
- **`decode` 返回 null**（列是 NULL）时，字段保留构造器给的值。
- codec 会被写入线程和加载线程同时调用。`MysqlLogWriter` 只按规则存日志。

## 读实体

每张表的语句在 prepare 时一次建好，之后不会发送别的 SQL：INSERT、upsert、按 id 的 DELETE、按 id / 按组 / 整表的 SELECT，以及一条
固定 100 个 id 的批量 SELECT，所以连接的语句缓存每张表只占七条。`load` 和 `loadGroup` 各一条 SELECT；`loadAll`（`getAll`
用它）每 100 个 id 执行一次批量 SELECT，不足 100 个时重复最后一个 id 补齐（重复的 id 只查出一行），用同一个连接；`loadTable`
（整表常驻的 Repository 用它）一条 SELECT 读整张表。

## 写实体

- **每批一个事务**，所以失败的批次不会留下任何东西：重写或拆成单行时都不会产生重复。删除后又插入，在同一个事务里先 DELETE
  再 INSERT，一批里所有的删除都在插入之前；managame-data 把同一个组里各行的替换放在同一批，所以在唯一的
  `(player_id, slot)` 下互换槽位的物品不管有多少都能写入。
- **INSERT 和 UPDATE 都是普通的 JDBC 批处理**，URL 带 `rewriteBatchedStatements=true` 时由驱动合并成一条多行语句。
- **UPDATE 用 `INSERT ... ON DUPLICATE KEY UPDATE`** 更新非 key 列，这是 MySQL 自己的批量保存方式。表里已没有的行会被重新
  写入，而不是失败：数据以内存为准。表上除主键外还有别的唯一索引时，一行改成另一行仍在用的唯一值，会更新到那一行上，这是
  MySQL 文档对这条语句的说明；只有两行在同一轮里互换唯一值时才会发生。
- **DELETE** 是按 id 删除那一条语句的 JDBC 批处理。
- **哪些失败会重试。** 和所有 mapper 一样的：连接断开或被拒、超时、死锁或锁等待；再加上 MySQL 此刻做不到、和数据本身无关的：
  只读（主库被切走之后就是这样，错误 1290、1792、1836）、磁盘或表满（1021、1114）、内存或资源不足（1037、1038、1041）、
  用户的连接数或资源用尽（1203、1226）、语句被杀（1317）。这样的批次整批留到下一轮。其它失败把批次拆成单行再写一遍：有的行
  写成功了，仍然失败的交给 `DataErrorHandler`；一行都没写成功（例如服务器对每次写入都回一个这里没列出的错误），全部留到
  下一轮。`MysqlLogWriter` 的做法相同。
- 连接池要大于 GameData 的 `writeThreads` 加上同时进行的加载数。

## 驱动设置

在同一台机器上的 MySQL 9.7 上测得，每轮修改 1,000 行，用的是 managame-benchmark 的 `mysqlserver` 基准：

| URL 参数 | 作用 |
| --- | --- |
| `rewriteBatchedStatements=true` | 一批 INSERT 或 UPDATE 合并成一条多行语句发送，而不是每行一条；逐行 UPDATE 的批处理只有三分之一的速度 |
| `cachePrepStmts=true&prepStmtCacheSqlLimit=1000000` | 驱动缓存每条语句的解析结果，列多的表语句会超过默认的 256 个字符上限：写入线程的 CPU 降了三分之二，分配降了 40% |
| `prepStmtCacheSize=250` | 给每张表的语句都留出缓存位置 |
| `useServerPrepStmts=true` | 服务器也缓存语句：每秒行数不变，客户端开销更少，例如 JSON 背包每行从 7.0 µs、7.7 KB 降到 3.0 µs、4.9 KB |
| `useLocalSessionState=true` | 连接已有的设置不再重复发送 |

全部打开后，每轮每秒写入 40,000 个修改过的玩家（10 列）、50,000 个道具或插入加删除、21,000 个带 50 项 JSON 列的背包。

- **JSON 字段的列类型。** 除非要在数据库里查询 JSON 内部，优先用 `TEXT`（或 `MEDIUMTEXT`），不用 MySQL 的 `JSON` 类型：
  写入 `JSON` 列的每个值 MySQL 都要解析并转换。更新一个 50 项的文档，`JSON` 列约每秒 21,000 行，`TEXT` 列 30,000 到 38,000 行。
- **读取**每次一个往返：冷加载一次 `get` 约耗客户端 40 µs CPU、14 KB，和驱动单独执行一条 SELECT 的开销相当。一次读多个
  用 `getAll`：8 个线程、每次查 100 个，每秒读出 110 万个玩家。
- **TLS。** 服务器支持时驱动会用 TLS（`sslMode=PREFERRED`），每次查询约多 8 µs；在不需要加密的网络里，`sslMode=DISABLED`
  可以省掉这部分。

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

## 日志

`cn.managame.data.mysql`，经 `System.Logger` 输出：INFO 记录实体 Mapper 建的每张表、每一列和每个唯一键，以及每张新建的
日志分表。WARNING 记录 JDBC URL 没带 `rewriteBatchedStatements=true`，以及不建表的 Mapper 发现分组类型的表在组键列上没有
索引。写入轮次与失败由 managame-data 的 `cn.managame.data` 记录。

## 构建与测试

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

先安装 managame-common 和 managame-data。测试通过 GameData 运行在 MySQL 模式的 H2 上，这是一个真正的内存数据库，覆盖：带
JSON 列的行，JSON 加载成初始化时的集合，整表常驻，超过一条 SELECT 上限的 `getAll`，在一批里更新大量行，分组以及先删后插使唯一的格子
可以复用，互换唯一槽位的物品不管批次在哪里结束都能写入，坏行丢弃而其他照常写入，
数据库拒绝连接期间保留修改，服务器以 Mapper 不认识的原因拒绝全部行时一行都不丢，提交未确认的插入重写而不产生重复，更新已不存在的行时把它重新写入，构建时拒绝不合适的映射，
为没有表的实体建表，为之后新增的字段加列、之前存的行读出构造器的值，为分组类型建唯一键、表上已有的组键索引保持原样，
被告知不动表的 Mapper 什么都不建，字段按应用的 codec 存，列按注解创建，以及按分区
时区切分的按天分区日志在一批里写入。提供一个可以丢弃的库时，`MysqlIntegrationTest` 会在真实 MySQL 上运行：

```bash
MANAGAME_DATA_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/scratch?rewriteBatchedStatements=true
MANAGAME_DATA_MYSQL_USER=... MANAGAME_DATA_MYSQL_PASSWORD=...
```
