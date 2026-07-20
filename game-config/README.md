[中文](README.zh-CN.md) | English

# game-config

`game-config` is a consumer-side configuration client. It merges one or more configuration documents in declaration order into an immutable snapshot. Later documents override duplicate keys from earlier ones. A single API provides typed reads, reloads, and change notifications.

The current implementation contains four modules:

- `game-config-core`: `ConfigCenter`, immutable `ConfigSnapshot`, SPI, and factory.
- `game-config-local`: local Properties or JSON files with filesystem-event hot reload.
- `game-config-nacos`: Nacos Config; resources use `group:dataId`, falling back to the `group` property or `DEFAULT_GROUP`.
- `game-config-etcd`: each resource is an Etcd key whose value is a Properties document.

## Usage

Compile against `game-config-core` and put the selected backend on the runtime classpath:

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-config-core</artifactId>
</dependency>
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-config-local</artifactId>
    <scope>runtime</scope>
</dependency>
```

```java
try (ConfigCenter config = ConfigFactory.open(ConfigOptions.builder("local")
        .resource("config/base.properties")
        .resource("config/application.json")
        .validator(candidate -> {
            if (candidate.getInt("game.server.port", 0) <= 0) {
                throw new IllegalArgumentException("game.server.port must be positive");
            }
        })
        .build())) {
    int port = config.snapshot().getInt("game.server.port", 8080);
    AutoCloseable listener = config.listen(change ->
            System.out.println(change.changedKeys()));
}
```

The local provider recognizes JSON by the `.json` extension and parses other files as Properties. A JSON document must have an object root. Nested objects are flattened to dotted keys, so `{"game":{"server":{"port":8080}}}` is available as `game.server.port`. Arrays remain available as compact JSON strings and are also flattened to indexed keys such as `regions[0]` and `servers[0].host`; nested arrays work the same way. Properties and JSON resources may be mixed; later resources still override duplicate keys.

Nacos:

```java
ConfigOptions.builder("nacos")
        .endpoint("127.0.0.1:8848")
        .resource("GAME:base.properties")
        .resource("GAME:application.properties")
        .property("timeoutMillis", "3000")
        .build();
```

Nacos client properties such as `namespace`, `username`, and `password` may also be supplied through `properties`; this library consumes `group` and `timeoutMillis` itself.

Etcd:

```java
ConfigOptions.builder("etcd")
        .endpoint("http://127.0.0.1:2379")
        .resource("/game/config/base")
        .resource("/game/config/application")
        .property("username", "game")
        .property("password", "secret")
        .build();
```

Separate multiple endpoints with commas. `timeoutMillis` defaults to `3000`. For local files, `required=false` permits resources that do not exist yet.

### Multi-resource consistency

Local and Nacos do not expose source revisions. When multiple resources are declared, each load reads and merges their current contents in declaration order. Updates across resources are not atomic and may briefly produce a snapshot that mixes old and new content. For atomic publication, keep related configuration in one document or use a backend with native consistency guarantees.

Etcd reads every key at one server revision. Publish a multi-key change in one Etcd transaction so the entire release occupies one revision.

## Semantics

- `ConfigFactory.open` completes the initial load synchronously and never returns a partially initialized center.
- The initial snapshot and every update pass through the optional `ConfigValidator` first. A rejected candidate leaves the last-known-good snapshot in place; inspect `isHealthy()` / `lastError()` for status.
- A push creates a new version and `ConfigChange` only when the merged content actually changes.
- Each listener runs independently with at most one notification executing and one pending. A slow listener coalesces intermediate changes and eventually receives the latest snapshot without blocking other listeners, snapshot publication, or provider threads.
- A failed provider watch is recreated with exponential backoff and followed by a fresh snapshot load.
- By default, the source is actively loaded every 30 seconds and becomes unhealthy after 90 seconds without successful contact. Configure these with `healthCheckInterval(Duration)` and `staleAfter(Duration)`; `Duration.ZERO` disables the corresponding mechanism.
- `isHealthy()` requires a healthy watch, no current load or validation error, and non-stale data. `lastError()` returns the latest failure or staleness reason.
- Empty/deleted Nacos content and deleted Etcd keys become empty documents; a missing local file with `required=false` is also treated as an empty document.
- `close()` unregisters listeners and closes filesystem watchers or remote clients.

## Testing

Regular tests require no external services:

```powershell
mvn "-Dmaven.repo.local=..\.m2" -f game-config\pom.xml test
```

Real Nacos and Etcd read, watch, and multi-resource consistency tests use Testcontainers and require Docker:

```powershell
mvn "-Dmaven.repo.local=..\.m2" -f game-config\pom.xml -Pintegration-tests verify
```
