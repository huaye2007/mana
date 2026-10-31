English | [中文](README.zh-CN.md)

# managame-data-mysql

Keeps [managame-data](../managame-data/README.md)'s entities and logs in MySQL: `MysqlEntityMapper` for
SingleRepository and GroupRepository, `MysqlLogWriter` for LogRepository. Depends on managame-data and the JDK's JDBC;
the application brings the driver and the connection pool.

```java
DataSource dataSource = ...;   // jdbc:mysql://host:3306/game?rewriteBatchedStatements=true, through a pool
GameData data = GameData.builder()
        .repositories(new MysqlEntityMapper(dataSource, json), List.of(Players.class, Items.class))
        .logRepositories(new MysqlLogWriter(logDataSource, json, ZoneId.of("Asia/Shanghai")), List.of(ItemLogs.class))
        .build();
```

## Tables

```java
@Table(name = "player")
public class Player {
    @Id private long id;
    private String name;
    @Column(name = "lv") private int level;                         // a column named otherwise
    private Map<Integer, Long> items = new ConcurrentHashMap<>();   // a JSON column
    Player() {}
}
```

- An entity or log needs `@Table(name)`. Each saved field is a column named after the field in snake case
  (`configId` is `config_id`), or by `@Column(name)`. The annotations are in `cn.managame.data.mysql.annotation`; the
  mappers and `JsonCodec` in `cn.managame.data.mysql`.
- The primitives, their boxes, String, char and byte[] are columns of their own. Anything else (collections, maps,
  objects, enums) is a JSON column through the `JsonCodec` given to the mapper or writer, which the application
  provides, for example over Jackson; a JSON column decodes into the class its field is initialized with.
- The tables belong to the application, which creates and migrates them; `prepare` reads each once at build, so a
  missing table or column fails the build with `DataException` (`DATA_LOAD_FAILED`), a class without `@Table` with
  `IllegalArgumentException`, and a JSON field without a codec with `IllegalStateException`.

## Reading entities

`load` and `loadGroup` are one SELECT each; `loadAll`, behind `getAll`, is one `WHERE id IN (...)` per 1,000 ids, on one
connection.

## Writing entities

- **Each batch is one transaction**, so a batch that failed left nothing behind: written again, or split into single
  rows, it cannot leave duplicates. A delete followed by an insert is a DELETE and an INSERT in one transaction.
- **INSERTs** are folded by the driver into multi-row statements when the URL has `rewriteBatchedStatements=true`.
- **UPDATEs** are sent as statements of up to 100 rows, each column taking its value by id through `CASE`, one round
  trip per statement instead of one per row. A statement that matches fewer rows than it names fails (keep the driver's
  default `useAffectedRows=false`, so matched rows are counted): a row is missing, and succeeding would lose its change.
  Split into single rows, the missing one goes to the DataErrorHandler.
- **DELETEs** go as `WHERE id IN (...)`, up to 1,000 ids per statement.
- Keep the pool larger than GameData's `writeThreads` plus the loads that run at once.

## Writing logs

```java
@Table(name = "item_log")
public class ItemLog {
    private long playerId;
    private int configId, delta;
    @PartitionKey(type = PartitionType.DAY) private long createdAt;   // epoch milliseconds: item_log_20261031
    ItemLog() {}
}
```

- A log class follows the entity rules (fields not final, a no-argument constructor), without keys.
- `@PartitionKey` splits the table: VALUE uses an integral or String field as the suffix, DAY, MONTH and YEAR format
  epoch milliseconds in the writer's partition zone (UTC by default). A partition table missing when its first logs
  arrive is created with `CREATE TABLE IF NOT EXISTS ... LIKE` the base table, outside the transaction, since DDL commits.
- A batch is one transaction, whatever partition tables its logs go to.

## Build and test

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

Install managame-common and managame-data first. The tests run against H2 in MySQL mode, a real database in memory,
through GameData, covering rows with JSON columns, loading JSON into the initialized collection, `getAll` of more ids
than one SELECT names, updates of many rows
in few statements, groups and a unique slot reused through deletes first, a bad row dropped and the others written,
changes kept while the database refuses connections, an insert whose commit was not acknowledged written again without
a duplicate, an update of a missing row reported, mappings refused at build, and logs in day partitions cut in the
partition zone, in one batch. `MysqlIntegrationTest` runs against a real MySQL when given a database that can be
thrown away:

```bash
MANAGAME_DATA_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/scratch?rewriteBatchedStatements=true
MANAGAME_DATA_MYSQL_USER=... MANAGAME_DATA_MYSQL_PASSWORD=...
```
