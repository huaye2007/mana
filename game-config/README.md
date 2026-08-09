[中文](README.zh-CN.md) | English

# game-config

`game-config` is a consumer-side configuration client. It merges one or more configuration documents in declaration order into an immutable snapshot. Later documents override duplicate keys from earlier ones. A single API provides typed reads, reloads, and change notifications.

The current implementation contains four modules:

- `game-config-core`: `ConfigCenter`, immutable `ConfigSnapshot`, SPI, factory, the `properties`, `yaml`, and `json` document formats, and the `memory`, `env`, and `system` layer types.
- `game-config-local`: local files with filesystem-event hot reload.
- `game-config-nacos`: Nacos Config; resources use `group:dataId`, falling back to the `group` property or `DEFAULT_GROUP`.
- `game-config-etcd`: each resource is an Etcd key.

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

### Layers

`ConfigOptions.builder()` stacks several backends into one center. Later layers override earlier ones, which is how file defaults, a remote backend, and per-process overrides combine without any fallback logic in application code:

```java
ConfigOptions.builder()
        .layer(ConfigLayer.builder("local").resource("config/base.yml")
                .property("required", "false").build())
        .layer(ConfigLayer.builder("nacos").endpoint("127.0.0.1:8848")
                .resource("GAME:application.properties").build())
        .systemProperties("game.")
        .environment("GAME_")
        .require("game.db.password")
        .build();
```

- `environment(prefix)` maps variable names to config keys by lowercasing and turning `_` into `.`, so `GAME_DB_URL` reads back as `game.db.url`. The prefix filters which variables are read; it is not removed from the key unless the layer sets `strip=true`. Use `__` for a literal underscore.
- `systemProperties(prefix)` uses property names as keys unchanged, so `-Dgame.db.url=...` overrides whatever is underneath.
- `require(keys...)` fails startup when a key is missing or blank, and rejects any later publish that would blank it.

Each layer keeps its own cached values, so an update from one backend re-merges from cache instead of re-reading the others.

### Precedence, and checking it

There is one rule at both levels: **declaration order, last one wins**. Later `resource(...)` calls override earlier ones within a layer, later `layer(...)` calls override earlier ones across the stack. There are no priority numbers to keep in sync.

Setting precedence is therefore easy; verifying it once a stack has several layers is the part that bites. Two methods answer it against the live snapshot:

```java
config.origins();                     // {game.server.port=env, game.db.url=nacos, game.name=local}
config.explain("game.server.port");   // [local=8080, nacos=9000, env=9090]
```

`origins()` names the layer that won each key. `explain(key)` lists every layer that defines it, lowest precedence first, so the last entry is what `snapshot()` returns and the earlier ones are what it overrode. Both walk all layers, so log them at startup or expose them on an admin endpoint rather than calling them per read.

Layers are named by their type. Give a layer an explicit `name(...)` when a stack has two of a kind, otherwise repeats are numbered by position (`local#1`, `local#2`):

```java
ConfigLayer.builder("local").name("defaults").resource("config/base.yml").build()
```

### Document formats

A format is a property of the document, not of the backend storing it, so all of them are resolved the same way and live in core. `properties`, `yaml`, and `json` are built in.

A resource's format follows its name — `.yml`/`.yaml` as YAML, `.json` as JSON, everything else as Properties — or is pinned for the whole layer with `property("format", "yaml")`. Resources of different formats may be mixed in one layer; later resources still override duplicate keys. Because the resolver is shared, a document does not have to be rewritten when it moves from a file to a Nacos dataId or an Etcd value.

A structured document must have an object root, and YAML and JSON flatten identically — they run through the same flattener, so switching between them is not a code change. Nested objects become dotted keys, so both of these are read as `game.server.port`:

```yaml
game:
  server:
    port: 8080
regions: [cn, us]
```

Lists are kept both as compact text under the base key and as indexed keys such as `regions[0]` and `servers[0].host`; `ConfigSnapshot.getList("regions")` reads them back as a list.

YAML is parsed with SnakeYAML's `SafeConstructor`, which resolves only standard scalar and collection tags. A document cannot name a Java class to instantiate, so a config file — including one fetched from a remote backend — cannot turn a load into arbitrary object construction.

To read a document type that is not built in — YAML, TOML, `.env`, anything — implement `ConfigFormat` and register it with `ServiceLoader`. No provider needs to change, and the new format is available to every backend:

```java
public final class YamlConfigFormat implements ConfigFormat {
    @Override public String name() { return "yaml"; }
    @Override public boolean claims(String resource) { return resource.endsWith(".yaml") || resource.endsWith(".yml"); }
    @Override public Map<String, String> parse(String content) { /* flatten to dotted keys */ }
}
```

```
src/main/resources/META-INF/services/cn.managame.config.spi.ConfigFormat
    com.example.YamlConfigFormat
```

Pinning a format that is not on the classpath fails at startup and lists the ones that are.

Nacos:

```java
ConfigOptions.builder("nacos")
        .endpoint("127.0.0.1:8848")
        .resource("GAME:base.properties")
        .resource("GAME:application.properties")
        .property("timeoutMillis", "3000")
        .build();
```

Nacos client properties such as `namespace`, `username`, and `password` may also be supplied through `properties`; this library consumes `group`, `timeoutMillis`, and `format` itself.

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

Separate multiple endpoints with commas. `timeoutMillis` defaults to `3000`. For local files, `required=false` permits resources that do not exist yet, and `debounceMillis` (default `100`) sets the quiet period that collapses the burst of events a single editor save produces.

### Reading on a hot path

`snapshot()` is a lock-free, allocation-free read, but typed accessors parse their string on every call. Hold a `ConfigRef` for anything read per tick or per request, so the value is parsed once per config change:

```java
ConfigRef<Integer> tickMillis = config.ref(snapshot -> snapshot.getInt("game.tick.millis", 50));
int millis = tickMillis.get();
```

### Testing code that reads config

`ConfigFactory.open(ConfigSource)` takes a source directly, and `MemoryConfigSource` drives it from a test — no file, no container, no SPI registration:

```java
MemoryConfigSource source = new MemoryConfigSource(Map.of("game.server.port", "8080"));
try (ConfigCenter config = ConfigFactory.open(source)) {
    source.emit(Map.of("game.server.port", "9090"));
}
```

`MemoryConfigSource.named("...")` registers a shared instance that a `ConfigLayer.memory("...")` layer binds to, which lets a test drive one layer of a stack that otherwise looks like production.

### Multi-resource consistency

Local and Nacos do not expose source revisions. When multiple resources are declared, each load reads and merges their current contents in declaration order. Updates across resources are not atomic and may briefly produce a snapshot that mixes old and new content. For atomic publication, keep related configuration in one document or use a backend with native consistency guarantees.

Etcd reads every key in a single transaction, so all keys are observed at one revision and a load costs one round trip regardless of how many resources the layer declares. Publish a multi-key change in one Etcd transaction so the entire release occupies one revision.

Layers are independent of one another: a merged snapshot may combine a new value from one layer with an older value from another.

## Semantics

- `ConfigFactory.open` completes the initial load synchronously and never returns a partially initialized center. The watch is registered before that load, so no update published during startup is lost, and startup costs one read rather than two.
- The initial snapshot and every update pass through the optional `ConfigValidator` first. A rejected candidate leaves the last-known-good snapshot in place; inspect `isHealthy()` / `lastError()` for status.
- A push creates a new version and `ConfigChange` only when the merged content actually changes.
- Each listener runs independently with at most one notification executing and one pending. A slow listener coalesces intermediate changes and eventually receives the latest snapshot without blocking other listeners, snapshot publication, or provider threads.
- `listen(listener, true)` delivers the current snapshot as the first event, atomically with registration, so there is no gap between reading `snapshot()` and subscribing. The initial event carries an empty `previous`, so `changedKeys()` lists every key.
- A failed provider watch is recreated with exponential backoff and followed by a fresh snapshot load, on a thread of its own so a slow health check cannot delay recovery.
- Liveness and freshness are separate. Every 30 seconds each source is probed with `ping()`, which transfers no documents; every 5 minutes the whole stack is reloaded as a safety net for an update no watch reported. A source becomes unhealthy after 90 seconds without successful contact. Configure these with `healthCheckInterval(Duration)`, `refreshInterval(Duration)`, and `staleAfter(Duration)`; `Duration.ZERO` disables the corresponding mechanism.
- `isHealthy()` requires a healthy watch, no current load, probe, or validation error, and non-stale data. `lastError()` returns the latest failure or staleness reason.
- Empty/deleted Nacos content and deleted Etcd keys become empty documents; a missing local file with `required=false` is also treated as an empty document.
- Missing keys and malformed typed values raise `ConfigException` naming the key.
- Duplicate keys resolve by declaration order, last one wins; `origins()` and `explain(key)` report which layer a value actually came from.
- `close()` unregisters listeners and closes filesystem watchers or remote clients. `snapshot()` stays readable afterwards so shutdown code does not have to order itself around the center.

## Testing

Regular tests require no external services:

```powershell
mvn "-Dmaven.repo.local=..\.m2" -f game-config\pom.xml test
```

Real Nacos and Etcd read, watch, and multi-resource consistency tests use Testcontainers and require Docker:

```powershell
mvn "-Dmaven.repo.local=..\.m2" -f game-config\pom.xml -Pintegration-tests verify
```
