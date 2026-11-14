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
  mappers, `JsonCodec` and `ColumnCodec` in `cn.managame.data.mysql`.
- The primitives, their boxes, String, char and byte[] are columns of their own. Anything else (collections, maps,
  objects, enums) is a JSON column through the `JsonCodec` given to the mapper or writer, which the application
  provides, for example over Jackson; a JSON column decodes into the class its field is initialized with. A field the
  application wants kept otherwise gets a codec of its own, see [Fields kept your way](#fields-kept-your-way).
- A column that is NULL leaves its field as the entity's constructor made it, so a field added to a class reads, in the
  rows stored before, as a new entity has it.
- A class without `@Table` fails the build with `IllegalArgumentException`, and a JSON field without a codec with
  `IllegalStateException`.

## Tables created for you

While GameData is built, `MysqlEntityMapper` creates what an entity's table lacks, so a new entity class, or a field
added to one, needs no SQL written by hand:

- **The table**, when there is none, with the `@Id` column as its primary key.
- **The columns of fields added since**, each with `ALTER TABLE ... ADD COLUMN`.
- **A grouped type's unique key** on its `@GroupKey` and `@MapKey` columns, unless an index of the table already starts
  with the group columns. Every load of a group searches by them, and a group with two rows at one map key could not be
  loaded at all.

It never drops or changes anything: a column of another type, or one no field has any more, stays as it is. Each thing
created is logged at INFO. A column is created as its field's type:

| Field | Column |
| --- | --- |
| long, int, short, byte | `BIGINT`, `INT`, `SMALLINT`, `TINYINT` |
| boolean, double, float, char | `TINYINT(1)`, `DOUBLE`, `FLOAT`, `CHAR(1)` |
| String | `VARCHAR(255)` |
| byte[] | `MEDIUMBLOB` |
| saved as JSON | `MEDIUMTEXT`: no length to think of, and written faster than MySQL's `JSON` type, see [Driver settings](#driver-settings) |
| with a `ColumnCodec` | the codec's `columnType()` |

- **Keys and primitives are `NOT NULL`**; any other column may be NULL.
- **A primitive's column defaults to the value the no-argument constructor gives the field**, so `int level = 1` added
  to a class is 1 in the rows stored before.
- **`@Column` says otherwise for one field**, each attribute optional:

```java
@Column(type = "VARCHAR(64)") private String name;                        // the column's type
@Column(type = "JSON") private Map<Integer, Long> items = ...;            // MySQL's JSON type, for a field the database queries into
@Column(defaultValue = "'none'") private String title;                    // the column's DEFAULT, as written in SQL
@Column(name = "lv", type = "SMALLINT", defaultValue = "1") private int level;
```

An application that keeps its tables itself builds the mapper with `createMissing(false)`. The mapper then creates
nothing: a missing table or column fails the build with `DataException` (`DATA_LOAD_FAILED`), as does a statement the
account has no right to when it does create, and a grouped type's table without an index on its group columns is warned
of. Either way a JDBC URL without `rewriteBatchedStatements=true` is warned of once.

```java
MysqlEntityMapper mapper = MysqlEntityMapper.builder(dataSource).json(json).createMissing(false).build();
```

Log tables are not created: the application makes the table of a log class, and `MysqlLogWriter` checks at build that
its columns can be read, as before.

## Fields kept your way

A field the rules would keep as JSON, or as a column of another type than the application wants, gets a `ColumnCodec`:
the column's type, the value JDBC binds for the field, and the field's value from the column.

```java
/** An enum kept as its name. */
record EnumAsName(Class<? extends Enum<?>> type) implements ColumnCodec {
    public String columnType() { return "VARCHAR(32)"; }
    public Object encode(Object value) { return ((Enum<?>) value).name(); }
    public Object decode(ResultSet resultSet, int index) throws SQLException {
        String name = resultSet.getString(index);
        return name == null ? null : Enum.valueOf((Class) type, name);
    }
}

MysqlEntityMapper mapper = MysqlEntityMapper.builder(dataSource)
        .json(json)                                              // still keeps what has no codec and is no scalar
        .columnCodecs(field -> field.type().isEnum() ? new EnumAsName((Class<? extends Enum<?>>) field.type()) : null)
        .build();
```

- **One function for every field**, asked once for each field of each entity type while GameData is built. It gets
  the `EntityField`: the field's name, its type, and the Java field for an annotation of the application's own, so it
  picks by any of them. Null leaves the field to the rules.
- **Not asked for the `@Id` or a group or map key**, which are bound as they are where rows are looked for by them.
- **`decode` returning null**, for a column that is NULL, leaves the field as the constructor made it.
- A codec is called from write and load threads at once. `MysqlLogWriter` keeps logs by the rules only.

## Reading entities

Each table's statements are built once, when it is prepared, and no other SQL is sent: an INSERT, an upsert, a DELETE
by id, SELECTs by id, by group and of the whole table, and a batch SELECT of a hundred ids, so a connection's statement
cache holds seven a table. `load` and `loadGroup` are one SELECT each; `loadAll`, behind `getAll`, runs the batch
SELECT once per hundred ids on one connection, filling the last hundred by repeating an id, whose row comes back once;
`loadTable`, for a preloaded repository, is one SELECT of the whole table.

## Writing entities

- **Each batch is one transaction**, so a batch that failed left nothing behind: written again, or split into single
  rows, it cannot leave duplicates. A delete followed by an insert is a DELETE and an INSERT in one transaction, all the
  deletes of the batch before its inserts; managame-data keeps the replacements of one group's rows in one batch, so
  items that trade slots under a unique `(player_id, slot)` are written whatever their number.
- **INSERTs and UPDATEs are plain JDBC batches**, which the driver folds into one multi-row statement when the URL has
  `rewriteBatchedStatements=true`.
- **An UPDATE is `INSERT ... ON DUPLICATE KEY UPDATE`** of the columns that are not keys, MySQL's own way of saving
  rows in bulk. A row missing from the table is written again rather than failing: memory owns the data. On a table with
  another unique key, a row taking a unique value another row still holds updates that other row instead, as MySQL
  documents for this statement; that happens only when two rows swap a unique value within one round.
- **DELETEs** are a JDBC batch of the DELETE by id.
- **What is tried again.** A lost or refused connection, a timeout, a deadlock or lock wait, as with every mapper, and
  what MySQL cannot do now, which says nothing about the rows: read-only, as a primary is once another took over (errors
  1290, 1792, 1836), the disk or a table full (1021, 1114), out of memory or resources (1037, 1038, 1041), the user's
  connections or resources used up (1203, 1226) and a statement killed (1317). Such a batch waits whole for the next
  round. Any other failure has the batch written again one row at a time: when some rows are written, those that still
  fail go to the `DataErrorHandler`; when none is, as when the server answers every write with an error not listed
  here, all wait for the next round. `MysqlLogWriter` does the same.
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

## Logging

`cn.managame.data.mysql`, through `System.Logger`: INFO for each table, column and unique key the entity mapper created,
and for each log partition table created. WARNING for a JDBC URL without `rewriteBatchedStatements=true`, and, from a
mapper told to create nothing, for a grouped type's table without an index on its group columns. Rounds and failures
are logged by managame-data's `cn.managame.data`.

## Build and test

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

Install managame-common and managame-data first. The tests run against H2 in MySQL mode, a real database in memory,
through GameData, covering rows with JSON columns, loading JSON into the initialized collection, a preloaded table,
`getAll` of more ids
than one SELECT names, updates of many rows
in one batch, groups and a unique slot reused through deletes first, items that trade unique slots written wherever a
batch ends, a bad row dropped and the others written,
changes kept while the database refuses connections, every row kept when the server refuses them all for a reason the
mapper does not know, an insert whose commit was not acknowledged written again without
a duplicate, an updated row no longer stored written again, mappings refused at build, a table created for an entity
that has none, the columns of fields added since with the constructor's values in the rows stored before, a grouped
type's unique key created and an index the table has on its group left as it is, nothing created by a mapper told to
leave the tables alone, a field kept by a codec of the application's, a column created as its annotation says, and
logs in day partitions cut in the partition zone, in one batch. `MysqlIntegrationTest` runs against a real MySQL when given a database that can be
thrown away:

```bash
MANAGAME_DATA_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/scratch?rewriteBatchedStatements=true
MANAGAME_DATA_MYSQL_USER=... MANAGAME_DATA_MYSQL_PASSWORD=...
```
