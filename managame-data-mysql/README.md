English | [中文](README.zh-CN.md)

# managame-data-mysql

Keeps [managame-data](../managame-data/README.md)'s entities and logs in MySQL: `MysqlEntityMapper` for
SingleRepository and GroupRepository, `MysqlLogWriter` for LogRepository. Depends on managame-data and the JDK's JDBC;
the application brings the driver and the connection pool.

```java
DataSource dataSource = ...;   // through a pool, with the URL options under "Driver settings"
GameData data = GameData.builder()
        .repositories(new MysqlEntityMapper(dataSource, json), List.of(Players.class, Items.class))
        .logRepositories(new MysqlLogWriter(logDataSource, json, ZoneId.of("Asia/Shanghai")), List.of(ItemLogs.class))
        .errorHandler(failures::save)
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
connection; `loadTable`, for a preloaded repository, one SELECT of the whole table.

## Writing entities

- **Each batch is one transaction**, so a batch that failed left nothing behind: written again, or split into single
  rows, it cannot leave duplicates. A delete followed by an insert is a DELETE and an INSERT in one transaction.
- **INSERTs and UPDATEs are plain JDBC batches**, which the driver folds into one multi-row statement when the URL has
  `rewriteBatchedStatements=true`.
- **An UPDATE is `INSERT ... ON DUPLICATE KEY UPDATE`** of the columns that are not keys, MySQL's own way of saving
  rows in bulk. A row missing from the table is written again rather than failing: memory owns the data. On a table with
  another unique key, a row taking a unique value another row still holds updates that other row instead, as MySQL
  documents for this statement; that happens only when two rows swap a unique value within one round.
- **DELETEs** go as `WHERE id IN (...)`, up to 1,000 ids per statement.
- Keep the pool larger than GameData's `writeThreads` plus the loads that run at once.

## Driver settings

Measured against MySQL 9.7 on the same machine, 1,000 rows changed a round, with managame-benchmark's `mysqlserver`
benchmarks:

| URL option | Why |
| --- | --- |
| `rewriteBatchedStatements=true` | a batch of INSERTs or UPDATEs goes as one multi-row statement, not a statement a row; a batch of plain single-row UPDATEs ran at a third of the speed |
| `cachePrepStmts=true&prepStmtCacheSqlLimit=1000000` | the driver keeps its parse of each statement, which for a wide table is longer than the default limit of 256 characters: the writing thread's CPU fell by two thirds and its allocation by 40% |
| `prepStmtCacheSize=250` | room for every table's statements |
| `useServerPrepStmts=true` | the server keeps the statements too: as many rows a second with less client work, such as 3.0 instead of 7.0 µs and 4.9 instead of 7.7 KB a row for bags of JSON |
| `useLocalSessionState=true` | settings the connection already has are not sent again |

With all of them, a round wrote 40,000 changed players (ten columns) a second, 50,000 items or inserts and deletes,
and 21,000 bags of a 50-entry JSON column.

- **Columns for JSON fields.** Prefer `TEXT` (or `MEDIUMTEXT`) to MySQL's `JSON` type unless the database queries
  inside them: MySQL parses and converts every value written to a `JSON` column. Updating a 50-entry document ran at
  about 21,000 rows a second in `JSON` and 30,000 to 38,000 in `TEXT`.
- **Reads** are a round trip each: a cold `get` cost about 40 µs of client CPU and 14 KB, about what the driver alone
  spends on one SELECT. Load many at once with `getAll`, which read 1.1 million players a second from 8 threads in
  queries of 100.
- **TLS.** The driver uses TLS when the server offers it (`sslMode=PREFERRED`), which cost about 8 µs a query; on a
  network that needs no encryption, `sslMode=DISABLED` saves it.

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
through GameData, covering rows with JSON columns, loading JSON into the initialized collection, a preloaded table,
`getAll` of more ids
than one SELECT names, updates of many rows
in one batch, groups and a unique slot reused through deletes first, a bad row dropped and the others written,
changes kept while the database refuses connections, an insert whose commit was not acknowledged written again without
a duplicate, an updated row no longer stored written again, mappings refused at build, and logs in day partitions cut in the
partition zone, in one batch. `MysqlIntegrationTest` runs against a real MySQL when given a database that can be
thrown away:

```bash
MANAGAME_DATA_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/scratch?rewriteBatchedStatements=true
MANAGAME_DATA_MYSQL_USER=... MANAGAME_DATA_MYSQL_PASSWORD=...
```
