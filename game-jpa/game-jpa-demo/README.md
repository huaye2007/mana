[中文](README.zh-CN.md) | English

# game-jpa-demo

`game-jpa-demo` is an integration-style demo module for validating `game-jpa`'s main runtime paths without depending on MySQL or MongoDB.

The demo defines a player scenario spanning both storage models:

- `PlayerAccount`: an RDB entity with table mapping, indexes, a JSON field, a version field, and a non-id shard key.
- `PlayerProfile`: a DocDB document with indexed fields, embedded fields and a shard field.

The in-memory executors under `game.jpa.demo.executor` isolate data per `ExecutorContext`, so tests can verify that Repository calls route to the expected data source, physical table or collection.

Run the demo module along with all `game-jpa` module tests:

```bash
mvn "-Dmaven.repo.local=..\.m2" -f game-jpa\pom.xml test
```

Run only the demo module and its required upstream modules:

```bash
mvn "-Dmaven.repo.local=..\.m2" -pl game-jpa/game-jpa-demo -am test
```
