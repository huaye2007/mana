English | [中文](README.zh-CN.md)

# managame-data

A game server's data in memory, written behind it. Business code reads and changes entities in memory only; every
second a round writes what changed, in batches, wherever the application chose. Game data comes in two shapes, and
there is a repository for each, plus one for logs:

| Repository | In memory | Loaded by | For example |
| --- | --- | --- | --- |
| `SingleRepository<K, E>` | `Map<K, E>` by `@Id` | the id | players, guilds |
| `GroupRepository<K, E>` | `Map<GroupKeys, Map<K, E>>`: groups by their `@GroupKey` values, each a map by `@MapKey` | every `@GroupKey` field equal | a player's items, quests, mails |
| `LogRepository<E>` | nothing | never read back | item flows, logins |

Depends on managame-common and [Caffeine](https://github.com/ben-manes/caffeine), which keeps the repositories'
entities in memory. This project knows no database: an `EntityMapper` loads and writes entities, a `LogWriter` writes
logs, and the application picks them per repository:

| Project | Gives |
| --- | --- |
| [managame-data-mysql](../managame-data-mysql/README.md) | `MysqlEntityMapper` and `MysqlLogWriter` |
| [managame-data-mongo](../managame-data-mongo/README.md) | `MongoEntityMapper` |
| the application | any `LogWriter`, such as one that reports logs over HTTP, or its own `EntityMapper` |

| Package | Holds |
| --- | --- |
| `cn.managame.data` | what business code uses: `GameData`, the repositories, `GroupKeys`, `MapKeys`, `DataException`, `DataFailure`, `DataErrorHandler`, `DataOperation` |
| `cn.managame.data.annotation` | `@Id`, `@GroupKey`, `@MapKey` |
| `cn.managame.data.spi` | what a database or log destination implements: `EntityMapper`, `LogWriter`, and what they read, `EntityType`, `EntityField`, `Change`, with `RetryableFailures`, the default retry rule |
| `cn.managame.data.internal` | the implementation; public only so the other packages can reach it, not API, and it may change in any release |

## Quick start

An entity is a plain class: every field that is neither static nor transient is saved.

```java
public class Player {                // @Table(name = "player") for MySQL, @Collection(name = "player") for MongoDB
    @Id private long id;
    private String name;
    private int level;
    private Map<Integer, Long> items = new ConcurrentHashMap<>();
    private transient long lastSeen;                                // not saved
    Player() {}
}

public class Item {
    @Id private long uid;            // the entity's primary key
    @GroupKey private long playerId; // a group is loaded by it
    @MapKey private int slot;        // unique within its group; the group's map is keyed by it
    private int configId, count;
    Item() {}
}

public record ItemLog(long playerId, int configId, int delta) {}    // a log can be any class
```

A repository is a subclass with its type arguments and nothing else:

```java
public class Players extends SingleRepository<Long, Player> {}
public class Items extends GroupRepository<Integer, Item> {}    // K is the @MapKey field's type
public class ItemLogs extends LogRepository<ItemLog> {}
```

Build once, at startup, naming where each repository's data goes; a collection of classes, as a container
integration finds them, or the classes one by one:

```java
LogWriter logService = (logType, logs) -> http.post("/logs/" + logType.getSimpleName(), json.encode(logs));

GameData data = GameData.builder()
        .repositories(new MysqlEntityMapper(dataSource, json), List.of(Players.class, Items.class))
        .logRepositories(logService, List.of(ItemLogs.class))
        .build();
Players players = data.repository(Players.class);

Player player = players.getOrCreate(id, Player::new);    // inserted when the database has none
players.update(id, p -> p.levelUp());                    // change and record in one call
Map<Long, Player> friends = players.getAll(friendIds);   // those not in memory read in one load
Map<Integer, Item> bag = data.repository(Items.class).getGroup(GroupKeys.of(id));
data.repository(ItemLogs.class).insert(new ItemLog(id, 5001, -1));
...
data.close();                                            // writes what is left
```

## Entities

- **Fields.** Every instance field that is neither static nor transient is saved, superclass fields first; none may be
  final. How a field is named and stored is the mapper's.
- **A no-argument constructor**, which may be private. Loads create the entity with it, so a collection field
  initialized there is filled again as the same class: `items = new ConcurrentHashMap<>()` loads as a
  ConcurrentHashMap (`EntityField.valueType()` gives a mapper that type).
- **Keys.** `@Id`, `@GroupKey` and `@MapKey` fields are integral or String, assigned by the application (there are no
  auto-increment ids, since inserts are written later) and never changed. Several GroupKey or MapKey fields each need a
  distinct `order`: the group key is then `GroupKeys.of(a, b)` in that order, and the map key `MapKeys.of(a, b)`, the
  values joined by `':'`.

## Reading and changing

- **Reads come from memory.** An entity or group not in memory is loaded on the asking thread, once however many
  threads ask for it at the same time: they all wait for that load and get the same instances. The asking threads block
  while the database answers: fine on a virtual thread, as managame-runtime's Routes are by default, never on a Netty
  EventLoop. A group is always loaded whole, by reads and changes alike. What reads return is live and for reading:
  change entities and groups through the repository.
- **Several at once.** `getAll(ids)` returns those of the ids that exist, by id in the order asked. Those not in memory
  are read with one call of the mapper's `loadAll` (in MySQL a `WHERE id IN (...)` per thousand ids). A `get` of one of
  them meanwhile may read it too; whichever stores it first is the instance both return.
- **After changing an entity, record it**: `update(entity)`, or change it through `update(id, change)` /
  `update(groupKey, mapKey, change)`, which cannot forget. A change is written with the state the entity has when its
  round writes it, read then by a write thread.
- **Keep an entity's collections thread-safe** (ConcurrentHashMap, CopyOnWriteArrayList), since a write thread may read
  them while business code changes them. `build` logs a warning for a collection field initialized with a plain
  java.util class, or not initialized.
- **One entity at a time.** Every method may be called from any thread, but changes to one entity are the application's
  to make one at a time, such as on the entity's Route.
- **Memory knows which rows exist.** `insert` of an id in memory throws `IllegalStateException`; `update` of an id known
  not to exist throws; `delete` of one does nothing. `get` of an id the database does not have remembers that, so asking
  again does not ask the database. Changes of ids not in memory never read the database.
- **Leaving memory.** An entity or group unused for `cacheExpire` (30 minutes) leaves memory, as does one `evict`ed. A
  later read loads it again, with its changes not yet written laid over what the database returns: the very same
  instances, so a reload never returns older data than memory had.
- **Whole tables.** A table that fits in memory and is read all the time, such as guilds, registers with
  `preloadedRepositories(mapper, types)`: build reads it whole through the mapper's `loadTable`, and nothing leaves
  memory after. An id or group not in memory does not exist, so no read asks the database, and `all()` lists every
  entity, or group. Changes are written as usual; `evict` throws, since dropping an entity would make it look deleted.

## Writing

Every `flushInterval` (1 second) a round writes what changed since the last one:

- **A row is written once per round**, with its latest state, however often it changed. An insert followed by an update
  is written as one insert, an insert followed by a delete not at all, and a delete followed by an insert as
  `DELETE_INSERT`, a replacement. A change that cannot follow the waiting one, such as an update of a deleted row,
  throws on the calling thread.
- **Types are written in parallel**, each entity or log type by one of `writeThreads` (4) threads at a time, so the
  changes of a row are written in order. A type's deletes are written first, so a row can take the unique value of one
  deleted in the same round. Changes go to the mapper in batches of `batchSize` (500), one operation per batch.
- **Failures.** A failure the mapper or writer calls retryable (`isRetryable`: by default a lost or refused connection,
  a timeout, a deadlock or lock wait, from JDBC, MongoDB or `java.net`) keeps the batch for the next round; after two
  in a row, the round writes nothing more through that mapper or writer, so an outage costs about two timeouts per
  round. An insert whose outcome such a failure left unknown is written again as `DELETE_INSERT`, so it never meets its
  own earlier row. Any other failure splits the batch into single changes; each that still fails goes to the
  `DataErrorHandler` and is dropped, and the others are written.
- **`flush()`** writes everything changed before the call, now, and returns when it is written; it throws
  `DataException` (`DATA_SAVE_FAILED`) when something failed. Use it before another process reads the data, such as
  when a player moves to another server: `flush()`, then `evict`.
- **`close()`** refuses further changes, then writes what is waiting, trying again every flush interval for up to
  `shutdownTimeout` (30 seconds) while writes fail. What is still not written goes, one by one, to the
  `DataErrorHandler`, where the application can keep it elsewhere, and close throws. Close it on shutdown: its threads
  keep the JVM running until then.
- **A crash** loses at most about one flush interval of changes.

The `DataErrorHandler` receives a `DataFailure`: the code (`DATA_SAVE_FAILED`, or `DATA_LOG_SAVE_FAILED` for logs),
the entity type, the operation, the entity (its id for a delete of one never in memory) or log, and the cause. It is
called one failure at a time; by default it logs an error.

## Logs

A `LogRepository` queues logs, at most `logQueueCapacity` (100,000), and hands them to its `LogWriter` in batches each
round, in the order they were inserted; with the queue full, as while the destination is down, `insert` returns false
and hands the log to the `DataErrorHandler`. A log is never read back, so any class will do, records included, and the
writer decides what it reads. Do not change a log once inserted.

A writer that reports over HTTP is a lambda. To have answers such as 503 tried again in the next round, it throws its
own exception for them and recognizes it:

```java
class LogService implements LogWriter {
    public void write(Class<?> logType, List<Object> logs) throws Unavailable { ... }
    public boolean isRetryable(Throwable failure) { return failure instanceof Unavailable || LogWriter.super.isRetryable(failure); }
}
```

## The database directly

The application keeps the `DataSource` or `MongoDatabase` it gave the mapper and uses it as it likes, such as for an
admin backend's queries and reports; the framework wraps none of it. Memory owns the data the repositories hold, and
the database follows it a round behind:

- **To read the newest state**, call `flush()` first.
- **Change the rows repositories hold only through the repositories**, on the server that holds them, never with SQL
  or a driver call: the next round writes memory's version of a row over whatever the database has. Tables no
  repository registers are the application's to change as it likes.

## Stats

`data.stats()` reads, from any thread, what each repository holds and what the rounds did with it since build, cheap
enough to read every few seconds and hand to the application's monitoring:

```java
for (EntityStats stats : data.stats().entities())
    if (stats.waiting() > 100_000) alert(stats.entityType().getSimpleName() + ": " + stats.waiting() + " changes waiting");
```

| `EntityStats` | |
| --- | --- |
| `cached` | entities in memory, or groups for a GroupRepository, as Caffeine estimates it |
| `waiting` | changes waiting to be written, the round in progress included: one that keeps growing means the database falls behind |
| `written`, `failures`, `dropped` | since build: changes written, writes that failed (tried again or not), changes handed to the DataErrorHandler |
| `cache` | Caffeine's `CacheStats`: hits, misses, loads, failed loads, load time and evictions; `minus` gives what changed between two reads |

`LogStats` has `queued`, `written`, `refused` (by a full queue), `failures` and `dropped`. Each number is read on its
own, so they need not be from the same instant.

## Your own EntityMapper

An `EntityMapper` (in `cn.managame.data.spi`) serves the entity types registered with it: `prepare(EntityType)` once
per type at build, then `load`, `loadAll` (by default a `load` per id; override it to read them in one query),
`loadTable` (for preloaded repositories only; by default it throws, failing the build),
`loadGroup` and `write(type, operation, batch)`, each `Change` of the batch giving the
entity's id and the entity to read. `EntityType` lists the saved fields (`EntityField`: name, type, `valueType`, get,
set, and the Java field for annotations), the key fields, and makes new instances. Writing a batch again after it
failed must do no harm: write a batch in one transaction, or every entity idempotently. An UPDATE that finds an entity
missing should fail. `isRetryable` starts from `RetryableFailures.isRetryable`, which a mapper can extend.

## Configuration

| Setting | Default | |
| --- | --- | --- |
| `repositories(EntityMapper, Collection)` | | entity repositories and the mapper that keeps them; also as varargs |
| `preloadedRepositories(EntityMapper, Collection)` | | entity repositories kept whole in memory, read at build; also as varargs |
| `logRepositories(LogWriter, Collection)` | | log repositories and the writer that writes them; also as varargs |
| `flushInterval` | 1 s | how often changes are written; a crash loses about this much |
| `batchSize` | 500 | changes or logs per batch |
| `writeThreads` | 4 | entity and log types written at once |
| `cacheExpire` | 30 min | how long an unused entity or group stays in memory |
| `shutdownTimeout` | 30 s | how long close keeps trying |
| `logQueueCapacity` | 100,000 | logs one LogRepository holds waiting |
| `errorHandler` | logs | receives what is given up on |

`build` reads every entity class and lets each mapper and writer prepare before anything starts: a class that does not
fit throws `IllegalArgumentException`, a missing setting `IllegalStateException`, and what a mapper or writer throws
while preparing comes out as it is. A load that fails throws `DataException` (`DATA_LOAD_FAILED`).

## Threads

Business threads read, load what is not in memory and record changes; one thread named `game-data` starts the rounds
and drops expired entries, and `game-data-write-N` write the entity and log types of a round. Recording a change takes
a lock that covers a
sixty-fourth of a type's rows, so business threads rarely wait for each other; nothing a business thread does waits for
a write.

## Build and test

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

Install managame-common first. The tests run against an in-memory `EntityMapper` that behaves as a database does
(copies of rows, all-or-nothing batches, duplicate ids and missing rows refused) and can be made unreachable, lose an
acknowledgement or refuse bad rows. They cover:
- writing inserts, updates and deletes; loading once and keeping the entity; remembering ids that do not exist;
- a row changed many times written once, inserted and deleted written not at all, deleted and inserted replaced;
- groups: loading, changing, keys of several fields, an evicted group coming back with its unwritten changes;
- one load for threads asking for the same id or group at once; unused entries leaving memory, and an evicted entity
  coming back unwritten as the same instance;
- `getAll`: one load of the ids neither in memory nor changed, a `get` and a `getAll` loading one id at once ending with
  one instance, a failed `getAll` keeping nothing;
- stats: changes waiting, written, failed and dropped, cache hits, misses and loads, logs queued, refused and written;
- preloaded repositories: the whole table read at build and no read asking the database after, `all()`, changes as
  usual, `evict` refused, a mapper without `loadTable` failing the build;
- logs through the application's writer: in order, kept when the writer calls a failure retryable, a refused log
  dropped and the others written, a full queue refusing logs;
- failures: a bad row dropped and the others written, changes kept while the database cannot be reached, an insert whose
  acknowledgement was lost written again without a duplicate, an update of a missing row reported, close handing over
  what it could not write;
- the build refusing classes and registrations that do not fit, passing on what a mapper throws, and taking
  repositories as a collection;
- business threads changing players and their items while rounds write every few milliseconds.
