English | [中文](README.zh-CN.md)

# managame-data-mongo

Keeps [managame-data](../managame-data/README.md)'s entities in MongoDB: `MongoEntityMapper` for SingleRepository
and GroupRepository. Depends on managame-data and the MongoDB sync driver. Logs are the application's to write, with
any `LogWriter`.

```java
MongoDatabase database = MongoClients.create("mongodb://127.0.0.1:27017").getDatabase("game");
GameData data = GameData.builder()
        .repositories(new MongoEntityMapper(database), List.of(Players.class, Mails.class))
        .build();
```

## Collections

```java
@Collection(name = "player")
public class Player {
    @Id private long id;                                            // saved as _id
    @Field(name = "nick") private String name;                     // a field named otherwise
    private Map<Integer, Long> items = new ConcurrentHashMap<>();   // a document
    Player() {}
}
```

- An entity needs `@Collection(name)`; `prepare` refuses a class without it with `IllegalArgumentException`. Its `@Id`
  is `_id`, and every other saved field is named after the field in snake case, or by `@Field(name)`. The annotations
  are in `cn.managame.data.mongo.annotation`; the mapper in `cn.managame.data.mongo`.
- Collections and maps (with String, Integer or Long keys) are arrays and documents of their elements, created again
  as the class the field is initialized with. byte, short, float and char are saved as int32, double and string.
  Anything else comes from the database's codec registry, where the application registers its own classes.
- A field missing from a document keeps the value the constructor gives it, so fields can be added without migrating.

## Reading

`load` finds a document by `_id`, `loadGroup` by the group fields, `loadAll`, behind `getAll`, with one `_id $in`
query, and `loadTable`, for a preloaded repository, reads every document.

## Writing

Every write is idempotent, so a batch that failed part way is simply written again, with no record of which documents
made it:

| Operation | Written as |
| --- | --- |
| INSERT, DELETE_INSERT | replace the document with this `_id`, creating it when missing |
| UPDATE | replace the document with this `_id`; fails when fewer documents matched than the batch has |
| DELETE | `deleteMany` of the batch's ids |

An insert therefore also replaces a document another writer stored with the same `_id`; the application assigns ids
uniquely. A failed UPDATE batch is split into single writes, and the missing document goes to the DataErrorHandler.

## Build and test

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

Install managame-common and managame-data first. The tests cover encoding and decoding every kind of field without a
server, initialized collections kept, and a class without `@Collection` refused. `MongoIntegrationTest` runs against a
real MongoDB when given its address (`MANAGAME_DATA_MONGO_URI=mongodb://127.0.0.1:27017`), in a database of its own
that it drops.
