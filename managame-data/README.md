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
| `cn.managame.data.spi` | what a database or log destination implements: `EntityMapper`, `LogWriter`, and what they read, `EntityType`, `EntityField`, `Change`, with `RetryableFailures`, the default retry rule, and `DelegatingEntityMapper`, for a mapper around another |
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
        .errorHandler(failures::save)                     // required: keeps what could not be written
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
  final. How a field is named and stored is the mapper's, and so is making room for it: the MySQL and MongoDB mappers
  create what a table or collection lacks while GameData is built, unless told to leave that to the application.
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
  are read with one call of the mapper's `loadAll` (in MySQL a `WHERE id IN (...)` per hundred ids). A `get` of one of
  them meanwhile may read it too; whichever stores it first is the instance both return.
- **After changing an entity, record it**: `update(entity)`, or change it through `update(id, change)` /
  `update(groupKey, mapKey, change)`, which cannot forget. A change is written with the state the entity has when its
  round writes it, read then by a write thread.
- **Keep an entity's collections thread-safe** (ConcurrentHashMap, CopyOnWriteArrayList), since a write thread may read
  them while business code changes them. `build` logs a warning for a collection field initialized with a plain
  java.util class, or not initialized.
- **One entity at a time.** Every method may be called from any thread, but changes to one entity are the application's
  to make one at a time, such as on the entity's Route.
- **Get entities by id in each task**, and keep none across tasks, in a session object for instance: an entity unused
  for `cacheExpire` leaves memory, a later `get` loads another instance, and changing the one kept then replaces it,
  losing what was changed on the other.
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

## Skipping loads of new data

When a server opens, thousands of roles are created within minutes, and each asks dozens of repositories for its data:
reads of rows no table has yet. Every read of the database, by a SingleRepository or a GroupRepository alike, goes
through three methods of the `EntityMapper`, each with the `EntityType`: `load`, `loadAll` and `loadGroup`. So an
application that wants to spare those reads puts a mapper of its own around the real one, which answers for a new role
without asking. `DelegatingEntityMapper` (in `cn.managame.data.spi`) hands every call to the mapper it is given, so the
application overrides only the loads; an application that needs none of this uses the real mapper as it is. The
repositories go on as when the database has nothing: `get` returns null and remembers it, a group starts empty, and what
is inserted is written as usual.

```java
/** By the application's rule that a role's id is always a field named roleId. */
final class NewRoleMapper extends DelegatingEntityMapper {
    private final Set<Long> newRoles;   // added when a role is created; removed ten minutes later, or when its data is evicted
    /** The types kept by role, found once at build: -1 for an @Id named roleId, or else its place among the @GroupKey fields. */
    private final Map<EntityType, Integer> roleKey = new ConcurrentHashMap<>();

    NewRoleMapper(EntityMapper mapper, Set<Long> newRoles) {
        super(mapper);
        this.newRoles = newRoles;
    }

    @Override public void prepare(EntityType type) {
        super.prepare(type);
        if (type.groupKeys().isEmpty()) {
            if (type.id().name().equals("roleId")) roleKey.put(type, -1);
            return;
        }
        for (int i = 0; i < type.groupKeys().size(); i++)
            if (type.groupKeys().get(i).name().equals("roleId")) roleKey.put(type, i);
    }

    @Override public Object load(EntityType type, Object id) throws Exception {
        return roleKey.containsKey(type) && newRoles.contains(id) ? null : super.load(type, id);
    }

    @Override public List<Object> loadAll(EntityType type, Collection<?> ids) throws Exception {
        if (!roleKey.containsKey(type)) return super.loadAll(type, ids);
        List<Object> stored = new ArrayList<>(ids);
        stored.removeIf(newRoles::contains);
        return stored.isEmpty() ? List.of() : super.loadAll(type, stored);
    }

    @Override public List<Object> loadGroup(EntityType type, GroupKeys key) throws Exception {
        Integer place = roleKey.get(type);
        return place != null && newRoles.contains(key.valueAt(place)) ? List.of() : super.loadGroup(type, key);
    }
}

GameData data = GameData.builder()
        .repositories(new NewRoleMapper(new MysqlEntityMapper(dataSource, json), newRoles), List.of(Players.class, Items.class))
        .errorHandler(failures::save)
        .build();
```

- **Decide what a type is once, in `prepare`.** GameData calls it for each entity type at build, so a load pays one
  lookup and no walk over the fields. The example goes by the name of the key field, which needs no annotation; an
  application that prefers one reads its own from `EntityField.field()`. Types kept by another key pass through.
- **Answer nothing only while the database is sure to have nothing for the key.** Once what the key got has left
  memory, unused for `cacheExpire` or evicted, answering nothing would bring it back empty, and later writes would go
  against the rows the database has. So take a role out of the new ones after a time shorter than `cacheExpire` from its
  creation, and as soon as the application evicts its data. And only this process may write the role's rows meanwhile:
  rows another one writes straight to the database would be missed.
- A key whose changes are not written yet comes back with them: the repository lays them over what the mapper returns.
- Loads run on the asking thread while the others asking for the same key wait, so an override answers from memory or
  hands the call on.

## Writing

Every `flushInterval` (1 second) a round writes what changed since the last one:

- **A row is written once per round**, with its latest state, however often it changed. An insert followed by an update
  is written as one insert, an insert followed by a delete not at all, and a delete followed by an insert as
  `DELETE_INSERT`, a replacement. A change that cannot follow the waiting one, such as an update of a deleted row,
  throws on the calling thread. A row changed while a round is writing it is written again by the next round.
- **Types are written in parallel**, each entity type by one of `writeThreads` (4) threads at a time, so the
  changes of a row are written in order. A type's deletes are written first, so a row can take the unique value of one
  deleted in the same round. Changes go to the mapper in batches of `batchSize` (500), one operation per batch. The
  replacements of one group's rows always go in one batch, which grows past `batchSize` for them: rows that trade map
  keys, such as items moved to each other's slots, only go through together, their deletes before their inserts.
- **Failures.** A failure the mapper or writer calls retryable (`isRetryable`: by default a lost or refused connection,
  a timeout, a deadlock or lock wait, from JDBC, MongoDB or `java.net`) keeps the batch for the next round; after two
  in a row, the round writes nothing more through that mapper or writer, so an outage costs about two timeouts per
  round. An insert a round could not write is written as `DELETE_INSERT` from then on, since a write that failed may
  have reached the database, so it never meets its own earlier row.
- **A row's failure or the database's.** Any other failure may be either, and no list of errors tells for sure, so the
  batch is written again one row at a time, the replacements of one group's rows still together. When some rows are
  written, the database works: each that still fails goes
  to the `DataErrorHandler` and is dropped. When none is written, all are kept for the next round and a WARNING names
  the failure, so a database that answers every write with an error no mapper knows, such as a column missing after a
  migration, loses no change. A row that fails alone in its batch is kept the same way, until a round writes another
  row in a batch with it or `close()` hands it over; `flush()` throws while it waits.
- **`flush()`** writes every entity change made before the call, now, and returns when it is written; it throws
  `DataException` (`DATA_SAVE_FAILED`) when something failed. Use it before another process reads the data, such as
  when a player moves to another server: `flush()`, then `evict`. It does not wait for logs.
- **`close()`** refuses further changes, then writes what is waiting, trying again every flush interval for up to
  `shutdownTimeout` (30 seconds) while writes fail. What is still not written goes, one by one, to the
  `DataErrorHandler`, where the application can keep it elsewhere, and close throws. Close it on shutdown: its threads
  keep the JVM running until then. It is the last step: stop the business threads that change data and insert logs
  first. A change made while it closes is written or refused, but a log inserted meanwhile may be left unwritten; the
  framework tracks no insert in flight, and the order of shutting down is what keeps it correct.
- **A crash** loses at most about one flush interval of changes.

The `DataErrorHandler` receives a `DataFailure`: the code (`DATA_SAVE_FAILED`, or `DATA_LOG_SAVE_FAILED` for logs),
the entity type, the operation, the entity (its id for a delete of one never in memory) or log, and the cause. It is
called one failure at a time. It is required, and `build` throws `IllegalStateException` without it: logging a failure
loses it, so save it where it can be written again, such as a local file.

## Logs

A `LogRepository` queues logs, at most `logQueueCapacity` (100,000), and hands them to its `LogWriter` in batches each
round, in the order they were inserted; with the queue full, as while the destination is down, `insert` returns false
and hands the log to the `DataErrorHandler`. A log is never read back, so any class will do, records included, and the
writer decides what it reads. Do not change a log once inserted.

Logs are written apart from entities: in rounds of their own, every `flushInterval`, on a thread of their own named
`game-data-log`, one log type after another. A log destination that is slow or cannot be reached, such as a log
service timing out, never delays the entities' rounds, and its failures never fail `flush()`; `flushLogs()` writes the
logs now and throws `DataException` (`DATA_LOG_SAVE_FAILED`) when some failed. `close()` writes entities and logs at
once, on threads of their own, so neither takes the other's share of `shutdownTimeout`.

A writer that reports over HTTP is a lambda. Logs fail as changes do: a batch whose failure the writer calls retryable
waits whole for the next round; any other is written again one log at a time, and its logs are dropped only when some
of the batch are written. To have answers such as 503 leave the batch whole at once, without that second pass, the
writer throws its own exception for them and recognizes it:

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

## Metrics

`metrics(Metrics)` reports the same numbers to the application's monitoring, through common's `Metrics`, which the
application binds to its backend as it bridges `System.Logger`; `Metrics.noop()` by default. Everything is counted
already, by the caches, the change sets and the log queues, and read when the backend collects, so a read, a change or
a write costs what it did without metrics. `close()` removes the meters.

By entity repository, with the tag `entity` for the entity class's name:

| Metric | Kind | Tells |
| --- | --- | --- |
| `managame.data.changes.waiting` | gauge | Changes waiting to be written, the round in progress included: one that keeps growing means the database falls behind |
| `managame.data.changes.written` | counter | Changes written |
| `managame.data.changes.write_failures` | counter | Writes that failed, a batch or a single change each counting once, tried again or not: rising with nothing dropped, the database cannot be reached or a row keeps failing |
| `managame.data.changes.dropped` | counter | Changes given up on and handed to the `DataErrorHandler`: data lost unless the handler keeps it |
| `managame.data.cache.entries` | gauge | Entities in memory, or groups for a GroupRepository, ids remembered as missing among them |
| `managame.data.cache.hits`, `managame.data.cache.misses` | counter | Reads answered from memory, and those that were not |
| `managame.data.cache.loads`, `managame.data.cache.load_failures` | counter | Loads from the database that returned, and those that threw; `getAll`'s reads are not among them |
| `managame.data.cache.load_nanos` | counter | Time spent loading: over `loads`, how long a business thread waits for the database |
| `managame.data.cache.evictions` | counter | Entities or groups that left memory unused |

By log repository, with the tag `log` for the log class's name:

| Metric | Kind | Tells |
| --- | --- | --- |
| `managame.data.logs.queued` | gauge | Logs waiting in the queue: near `logQueueCapacity`, the destination falls behind |
| `managame.data.logs.written` | counter | Logs written |
| `managame.data.logs.refused` | counter | Logs refused by a full queue, which went to the `DataErrorHandler` |
| `managame.data.logs.write_failures` | counter | Writes that failed, tried again or not |
| `managame.data.logs.dropped` | counter | Logs a round gave up on and handed to the `DataErrorHandler` |

## Your own EntityMapper

An `EntityMapper` (in `cn.managame.data.spi`) serves the entity types registered with it: `prepare(EntityType)` once
per type at build, then `load`, `loadAll` (by default a `load` per id; override it to read them in one query),
`loadTable` (for preloaded repositories only; by default it throws, failing the build),
`loadGroup` and `write(type, operation, batch)`, each `Change` of the batch giving the
entity's id and the entity to read. `EntityType` lists the saved fields (`EntityField`: name, type, `valueType`, get,
set, and the Java field for annotations), the key fields, and makes new instances. Writing a batch again after it
failed must do no harm: write a batch in one transaction, or every entity idempotently. An UPDATE of an entity whose row
is missing may write the row again (MySQL's mapper does) or fail (MongoDB's does). `isRetryable` starts from
`RetryableFailures.isRetryable`, which a mapper can extend. It names the failures that leave a batch whole for the next
round; a failure it does not know only costs writing the batch one row at a time, and drops no row unless others of the
batch are written.

To change what an existing mapper does for some calls, extend `DelegatingEntityMapper` around it rather than write a
mapper anew: it hands on every call, `isRetryable` and `loadTable` among them, and the application overrides the ones it
answers itself, as in [Skipping loads of new data](#skipping-loads-of-new-data).

## Configuration

| Setting | Default | |
| --- | --- | --- |
| `repositories(EntityMapper, Collection)` | | entity repositories and the mapper that keeps them |
| `preloadedRepositories(EntityMapper, Collection)` | | entity repositories kept whole in memory, read at build |
| `logRepositories(LogWriter, Collection)` | | log repositories and the writer that writes them |
| `flushInterval` | 1 s | how often changes are written; a crash loses about this much |
| `batchSize` | 500 | changes or logs per batch |
| `writeThreads` | 4 | entity types written at once; logs have a thread of their own |
| `cacheExpire` | 30 min | how long an unused entity or group stays in memory |
| `shutdownTimeout` | 30 s | how long close keeps trying |
| `logQueueCapacity` | 100,000 | logs one LogRepository holds waiting |
| `errorHandler` | required | receives what is given up on, to save it |
| `metrics` | `Metrics.noop()` | where the repositories report, see [Metrics](#metrics) |

`build` reads every entity class and lets each mapper and writer prepare before anything starts: a class that does not
fit throws `IllegalArgumentException`, a missing setting `IllegalStateException`, and what a mapper or writer throws
while preparing comes out as it is. A load that fails throws `DataException` (`DATA_LOAD_FAILED`).

## Threads

Business threads read, load what is not in memory and record changes; one thread named `game-data` starts the entities'
rounds and drops expired entries, `game-data-write-N` write the entity types of a round, and `game-data-log` writes the
logs in rounds of its own. Recording a change takes a lock that covers a
sixty-fourth of a type's rows, so business threads rarely wait for each other; nothing a business thread does waits for
a write.

## Logging

Through `System.Logger`, so any backend can take it; see the workspace README.

| Logger | Logs |
| --- | --- |
| `cn.managame.data` | INFO: GameData started (its repositories, interval and write threads), each table preloaded with its rows and time, and closed. DEBUG: each repository, each write round that had something to do (written, kept for later, dropped, time). WARNING: an entity collection that is not thread-safe; a batch of which nothing could be written, kept for the next round, with its first failure. ERROR: a round or a DataErrorHandler that failed. |

## Build and test

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

Install managame-common first. The tests run against an in-memory `EntityMapper` that behaves as a database does
(copies of rows, all-or-nothing batches, duplicate ids and missing rows refused) and can be made unreachable, lose an
acknowledgement or refuse bad rows. They cover:
- writing inserts, updates and deletes; loading once and keeping the entity; remembering ids that do not exist;
- a row changed many times written once, inserted and deleted written not at all, deleted and inserted replaced;
- groups: loading, changing, keys of several fields, an evicted group coming back with its unwritten changes, an item
  moved to another slot or to another group with its id coming back where it is now, items that trade slots written
  wherever a batch ends and together when a batch is written in parts;
- one load for threads asking for the same id or group at once; unused entries leaving memory, and an evicted entity
  coming back unwritten as the same instance;
- `getAll`: one load of the ids neither in memory nor changed, a `get` and a `getAll` loading one id at once ending with
  one instance, a failed `getAll` keeping nothing;
- stats: changes waiting, written, failed and dropped, cache hits, misses and loads, logs queued, refused and written;
- preloaded repositories: the whole table read at build and no read asking the database after, `all()`, changes as
  usual, `evict` refused, a mapper without `loadTable` failing the build;
- logs through the application's writer: in order, kept when the writer calls a failure retryable, a refused log
  dropped and the others written, every log of a batch kept when none of it can be written, a full queue refusing logs, a destination that hangs and then fails never holding up
  the writing of entities;
- failures: a bad row dropped and the others written, changes kept while the database cannot be reached, every row of a
  batch kept when none of it can be written, a row that fails alone kept until another of its batch is written, an
  insert whose acknowledgement was lost written again without a duplicate, an update of a missing row reported, a change
  or a delete made while its row is being written carried out by the next round, close handing over what it could not
  write;
- a mapper around the mapper (`DelegatingEntityMapper`): nothing read for a new role, by `get`, `getOrCreate`, `getAll`
  or a group, the database read for other roles, for types not kept by role and again once the role is no longer new,
  unwritten changes kept through an early evict, everything not overridden handed on;
- the build refusing classes and registrations that do not fit, passing on what a mapper throws, and taking
  repositories as a collection;
- business threads changing players and their items while rounds write every few milliseconds.
