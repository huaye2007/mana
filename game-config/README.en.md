[中文](README.md) | English

# game-config

`game-config` is the unified configuration module of the game server framework. It composes configuration from local files, classpath, command line, JVM properties, environment variables, defaults and remote config sources. Business code obtains type-safe configuration snapshots through `GameConfigManager`, and can listen for changes, hot-reload, and apply runtime overrides.

## Module Structure

| Module | Description |
| --- | --- |
| `game-config-core` | Core API, config manager, local config loaders, config source abstraction, remote provider SPI |
| `game-config-local` | Business config provider backed by a local file or directory, type `local`; supports manual loading or reload triggered by a commit signal |
| `game-config-nacos` | Remote config implementation based on Nacos ConfigService, with push support |
| `game-config-apollo` | Config implementation based on Apollo Config Service, with notification long polling |
| `game-config-consul` | Config implementation based on Consul KV, with blocking-query long polling |
| `game-config-etcd` | Remote config implementation based on jetcd, with watch/push support |
| `game-config-starter` | Aggregating starter that transitively pulls in core by default; the built-in remote providers are declared as optional dependencies in this module — business projects must add them explicitly as needed |

## Design Principles

- Business code depends only on the `GameConfigManager`, `GameConfigOptions`, `ConfigSource` and listener APIs exposed by `game-config-core`.
- Config sources are prioritized by insertion order — the smaller the index, the higher the priority.
- The runtime override layer has the highest priority, above all `ConfigSource`s.
- Remote implementations are mounted via Java SPI; adding a remote provider requires no change to core.
- Config snapshots are immutable; changes take effect via `reloadNow()`, hot-reload polling, remote push, or the dynamic override API.

## Quick Start

### 1. Add dependencies

Using only local config and the common API:

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-config-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Using the starter for the core entry points:

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-config-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Provider dependencies are optional and are not transitively delivered through the starter. When integrating a local or remote config backend, add the corresponding module explicitly:

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-config-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-config-local</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

By default `GameConfigStarter` does not add all JVM system properties or environment variables to the snapshot, so `snapshot()` neither leaks runtime-sensitive information nor picks up non-business keys. Enable those sources explicitly when compatibility truly requires them:

```java
GameConfigManager manager = GameConfigStarter.builder()
        .systemProperties(true)
        .environmentVariables(true)
        .start();
```

The starter's default priority order is:

```text
runtime overrides > command line > explicit JVM > explicit env vars > remote config > local file > local directory > classpath > defaults
```

### 2. Create the config manager

```java
GameConfigOptions options = new GameConfigOptions();
options.setHotReloadEnabled(true);
options.setRefreshIntervalMillis(3000);

options.addSource(new CommandLineConfigSource(args));
options.addSource(new JvmConfigSource(true));
options.addSource(new LocalFileConfigSource("config/application.properties"));
options.addSource(new EnvironmentConfigSource());
options.addSource(new DefaultConfigSource(Map.of(
        "server.port", "8080",
        "feature.match.enabled", "false"
)));

GameConfigManager manager = GameConfigFactory.create(options);
manager.start();
```

The priority order in the example above is:

```text
runtime overrides > command line > JVM > local file > env vars > defaults
```

### 3. Read configuration

```java
String host = manager.get("server.host", "127.0.0.1");
int port = manager.getInt("server.port", 8080);
long timeout = manager.getLong("rpc.timeoutMillis", 3000L);
double rate = manager.getDouble("drop.rate", 0.0D);
boolean enabled = manager.getBoolean("feature.match.enabled", false);

Map<String, String> snapshot = manager.snapshot();
```

When type conversion fails or the key is missing, the supplied default is returned. `getBoolean` recognizes `true`/`1` as `true` and `false`/`0` as `false` (case-insensitive); any other unrecognized value returns the supplied default — not `false` — same as a parse failure.

### 4. Listen for changes

```java
manager.addChangeListener(event -> {
    for (String key : event.getChangedKeys()) {
        System.out.println(key + " " + event.getChangeType(key));
    }
});
```

Change types:

| Type | Description |
| --- | --- |
| `ADDED` | New key |
| `UPDATED` | Value of an existing key changed |
| `DELETED` | Key removed |

### 5. Dynamic overrides

```java
manager.put("feature.match.enabled", "true");
manager.putAll(Map.of("server.port", "9000", "room.maxPlayers", "100"));

manager.update("room.maxPlayers", oldValue -> String.valueOf(Integer.parseInt(oldValue) + 10));
manager.removeOverride("server.port");
manager.clearOverrides();
```

The dynamic override layer suits GM commands, canary switches, runtime experiment parameters and the like. Values in the override layer take effect immediately and trigger one change notification.

### 6. Validation

```java
options.addValidator(config -> {
    int port = Integer.parseInt(config.getOrDefault("server.port", "8080"));
    if (port <= 0 || port > 65535) {
        throw new IllegalArgumentException("server.port out of range");
    }
});
```

Validators run before a snapshot takes effect; if one throws, the change is rejected.

## Local Config Formats

### properties

```properties
server.host=127.0.0.1
server.port=8080
feature.match.enabled=true
```

### JSON

```json
{
  "server": {
    "host": "127.0.0.1",
    "port": 8080
  },
  "feature": {
    "match": {
      "enabled": true
    }
  }
}
```

JSON is flattened into dot-separated keys:

```text
server.host=127.0.0.1
server.port=8080
feature.match.enabled=true
```

Arrays use the index as the key, e.g. `servers.0.host`.

### conf/cfg/ini

```ini
[server]
host = 127.0.0.1
port = 8080

[feature.match]
enabled = true
```

The section becomes the key prefix, e.g. `server.port=8080`. Both `=` and `:` separators are supported; blank lines, `#` comments and `//` comments are ignored.

### Directory loading

```java
options.addSource(new LocalDirectoryConfigSource("config/conf.d"));
options.addSource(new LocalDirectoryConfigSource("config/conf.d", true));
```

A directory source loads all regular files under the directory, merged in lexicographic order of relative path; later files override same-name keys of earlier files. Not recursive by default; pass `true` as the second constructor argument to recurse into subdirectories. A missing directory or an empty/blank path yields an empty config; a path that exists but is not a directory throws `ConfigOperationException`.
To restrict file types, use `LocalDirectoryConfigSource(String directoryPath, boolean recursive, Set<String> allowedExtensions)`, e.g. loading only `properties/json/conf`.

## Remote Config

### Creating a provider via SPI

```java
RemoteConfigProvider provider = RemoteConfigProviderFactory.create("local");

Properties local = new Properties();
local.setProperty("directory", "config/conf.d");
local.setProperty("recursive", "true");
local.setProperty("watchMode", "manual");

options.addSource(new RemoteConfigSource(provider, local));
```

The local provider's type is `local`, with `file` and `directory` also accepted as aliases. Parameters:

| Parameter | Default | Description |
| --- | --- | --- |
| `file` | none | A single local config file |
| `files` | none | Multiple local config files, comma-separated, merged in declaration order |
| `directory` | none | A local config directory; regular files loaded in lexicographic order of relative path |
| `path` | none | Auto-detects file vs. directory |
| `recursive` | `false` | Whether directory loading recurses into subdirectories; accepts `true` or `1` |
| `extensions` | none | Allowed extensions for directory loading, comma-separated; all regular files when unset |
| `failIfMissing` | `false` | Whether to throw `ConfigOperationException` when the file, directory or path does not exist |
| `watchMode` | `manual` | Local change trigger mode: `manual` or `signal` |
| `signalFile` / `reloadSignalFile` / `triggerFile` | none | The commit-complete marker file watched when `watchMode=signal`; a full reload happens only after this file changes |
| `watchDebounceMillis` | `50` | Debounce delay before triggering the reload in `signal` mode |

At least one of `file/files/directory/path` must be configured; for same-name keys, later loads override earlier ones.
The local provider does not watch config files by default — it suits setups where an external release system finishes a full-package sync, then the business receives a signal and calls `GameConfigManager#reloadNow()`.
For file-system-triggered reloads, prefer `watchMode=signal` and have the release system update a marker file only after all files are synced:

```java
local.setProperty("directory", "config/conf.d");
local.setProperty("watchMode", "signal");
local.setProperty("signalFile", "config/conf.d/.ready");
```

Per-file watchers are not supported: no individual config file change triggers a reload directly, which avoids reading a half-synced snapshot mid-release. Enabling `GameConfigOptions#hotReloadEnabled` also reads local files periodically; turn hot reload off if loading should happen only on the external signal.

### Nacos

```java
RemoteConfigProvider provider = RemoteConfigProviderFactory.create("nacos");

Properties remote = new Properties();
remote.setProperty("serverAddr", "127.0.0.1:8848");
remote.setProperty("dataIds", "DEFAULT_GROUP:game-common,game-room");

options.addSource(new RemoteConfigSource(provider, remote));
```

| Parameter | Default | Description |
| --- | --- | --- |
| `serverAddr` | none | Nacos server address, passed to the Nacos client |
| `dataIds` | none | Multi-config declaration in the form `GROUP:dataId,GROUP:dataId,dataId` |
| `defaultGroup` | `DEFAULT_GROUP` | Used when the group is omitted in `dataIds` |
| `dataId` | none | Single-config compatibility form |
| `group` | `DEFAULT_GROUP` | Group for the single-config compatibility form |
| `timeoutMs` | `3000` | Fetch timeout |

Nacos supports push mode; `GameConfigManager.start()` subscribes to changes automatically.
When both `dataIds` and `dataId` are empty or blank, a `ConfigOperationException` is thrown.

### Etcd

| Parameter | Default | Description |
| --- | --- | --- |
| `endpoints` | `http://localhost:2379` | etcd endpoints, comma-separated |
| `dataIds` | none | Multi-key declaration, comma-separated |
| `dataId` | none | Single-key compatibility form |
| `timeoutMs` | `3000` | Fetch timeout |

Etcd supports watch/push mode; values are parsed as properties text.
When both `dataIds` and `dataId` are empty or blank, a `ConfigOperationException` is thrown.

### Apollo

| Parameter | Default | Description |
| --- | --- | --- |
| `configServiceUrl` / `serverAddr` | `http://localhost:8080` | Apollo Config Service address |
| `appId` | none | Apollo application ID |
| `cluster` / `clusterName` | `default` | Apollo cluster |
| `namespaces` / `namespace` / `namespaceName` / `dataIds` / `dataId` | `application` | Namespaces, comma-separated |
| `ip` | none | Client IP passed through to the Apollo config API |
| `label` | none | Label passed through to the Apollo config API |
| `accessKeySecret` / `apolloAccessKeySecret` / `secret` | none | Apollo access key secret; when set, `Authorization` and `Timestamp` request headers are generated automatically |
| `accessKeySecretEnv` / `secretEnv` | none | Read the Apollo access key secret from an environment variable |
| `timeoutMs` | `3000` | Config fetch timeout |
| `connectTimeoutMs` | `3000` | HTTP connect timeout |
| `longPollTimeoutMs` | `60000` | Notification long-poll request timeout |
| `retryDelayMs` | `1000` | Retry delay after a long-poll error |

The Apollo provider's type is `apollo`. Config fetches use `/configs/{appId}/{cluster}/{namespace}`, preferring the `configurations` field of the response; non-JSON responses are parsed as properties text. Subscription uses `/notifications/v2` long polling with a reused HTTP client; after a namespace change only the changed namespaces are refetched, then the full snapshot is merged in declaration order. When Apollo returns empty `configurations`, the keys of that namespace are removed from the final snapshot; on fetch errors, `GameConfigManager`'s last-good mechanism still provides the fallback.

```java
Properties apollo = new Properties();
apollo.setProperty("configServiceUrl", "http://apollo-config:8080");
apollo.setProperty("appId", "game-room");
apollo.setProperty("cluster", "default");
apollo.setProperty("namespaces", "application,game-room");
apollo.setProperty("accessKeySecretEnv", "APOLLO_ACCESS_KEY_SECRET");

GameConfigManager manager = GameConfigStarter.builder()
        .remote("apollo", apollo)
        .hotReload(true)
        .start();
```

### Consul

| Parameter | Default | Description |
| --- | --- | --- |
| `endpoint` / `address` | `http://127.0.0.1:8500` | Consul HTTP API address |
| `key` / `keys` / `dataId` / `dataIds` | none | KV keys, comma-separated |
| `prefix` | none | KV prefix; when set, the whole prefix is loaded with a single `recurse` request |
| `token` / `aclToken` | none | Consul ACL token |
| `dc` / `datacenter` | none | Consul datacenter |
| `timeoutMs` | `3000` | Regular request timeout |
| `connectTimeoutMs` | `3000` | HTTP connect timeout |
| `waitSeconds` | `55` | The `wait` parameter of blocking queries |
| `retryDelayMs` | `1000` | Retry delay after a blocking-query error |
| `watchThreads` | `16` | Upper bound of background threads for multi-key blocking queries; exceeding it fails startup |

The Consul provider's type is `consul`. Single-key mode reads `/v1/kv/{key}?raw`; prefix mode reads `/v1/kv/{prefix}?recurse` and decodes the Base64 values Consul returns. Subscription uses `X-Consul-Index` blocking queries — no fixed-interval polling; values are parsed as properties text. For many keys, put them under one prefix and use `prefix` mode to compress the watch into a single blocking query. When Consul returns 404 or the prefix has no values, that source's snapshot becomes empty and those keys are removed from the final config; network/parse errors do not drop the last-good snapshot.

```java
Properties consul = new Properties();
consul.setProperty("endpoint", "http://consul-agent:8500");
consul.setProperty("prefix", "game/config/room");
consul.setProperty("token", System.getenv("CONSUL_HTTP_TOKEN"));
consul.setProperty("waitSeconds", "55");

GameConfigManager manager = GameConfigStarter.builder()
        .remote("consul", consul)
        .hotReload(true)
        .start();
```

## Extending Local Loaders

To add a local format, implement `LocalConfigLoader`:

```java
public class YamlLocalConfigLoader implements LocalConfigLoader {
    @Override
    public boolean supports(String extension) {
        return "yaml".equals(extension) || "yml".equals(extension);
    }

    @Override
    public void init(String filePath) {
        // save the path
    }

    @Override
    public Map<String, String> load() {
        // return flattened key-values
    }
}
```

And register it in the resource file:

```text
META-INF/services/cn.managame.config.loader.LocalConfigLoader
```

## Extending Remote Providers

To add a remote config center, implement `RemoteConfigProvider`:

```java
public class MyRemoteConfigProvider implements RemoteConfigProvider {
    @Override
    public String type() {
        return "my-config";
    }

    @Override
    public Map<String, String> load(Properties remoteProperties) {
        return Map.of();
    }
}
```

And register it in the resource file:

```text
META-INF/services/cn.managame.config.spi.RemoteConfigProvider
```

For active push, override `supportsPush()` and `subscribe(...)`.

## Production Notes

- Be explicit about source priority; don't put environment variables so high that they override local or remote config unintentionally.
- Add a `ConfigValidator` for critical settings so hot reload can't apply an invalid snapshot to a running service.
- Configure an `errorHandler` for remote sources to hook into alerting or degradation logs.
- With `hotReloadEnabled=true`, push sources still periodically refetch as a liveness probe to detect broken connections. Pushes take effect immediately, independent of polling; to keep push and polling from compounding into high-frequency duplicate fetches, `GameConfigStarter` defaults the push source's liveness refetch interval (`pushLivenessRefreshMillis`) to 60s, so disconnect detection may lag by at most one interval. When assembling sources manually, control it with `new RemoteConfigSource(provider, props, pushLivenessRefreshMillis)`; `0` restores the old refetch-on-every-reload behavior.
- `failFast=true` suits services that must obtain config at startup; `failFast=false` suits services that can fall back to defaults.
- The dynamic override layer exists only within the current process and is not restored after a restart.
- Call `manager.close()` when done or at shutdown to release remote subscriptions, watchers and internal threads.
- Some Java comments in the current sources have Chinese encoding display issues; this does not affect API behavior and can be cleaned up separately.

## Testing

Run in the `game-config` directory:

```bash
mvn "-Dmaven.repo.local=..\\.m2" test
```

Current tests cover core source priority, classpath/local/remote loading, failure degradation, fail-fast, JSON parse failure, polling hot reload, plus the starter's built-in provider SPI discovery.

Note: the `game-config-starter` tests can discover the built-in providers because the starter module itself declares those optional dependencies; business projects depending on the starter still need to add the provider modules they use explicitly.

CI compiles and runs the `game-config` tests with Java 25, matching `maven.compiler.release=25` in the root `pom.xml`.

The optional integration tests are skipped by default; they need local services and explicit switches:

| Switch | Target | Common parameters |
| --- | --- | --- |
| `GAME_CONFIG_INTEGRATION_APOLLO=true` or `-Dgame.config.integration.apollo=true` | Apollo Config Service | `GAME_CONFIG_APOLLO_ENDPOINT`, `GAME_CONFIG_APOLLO_APP_ID`, `GAME_CONFIG_APOLLO_NAMESPACE`, `GAME_CONFIG_APOLLO_ACCESS_KEY_SECRET`, `GAME_CONFIG_APOLLO_EXPECTED_KEY` |
| `GAME_CONFIG_INTEGRATION_APOLLO_PUSH=true` or `-Dgame.config.integration.apollo.push=true` | Apollo Config Service + Portal OpenAPI push acceptance | `GAME_CONFIG_APOLLO_PORTAL_URL`, `GAME_CONFIG_APOLLO_OPENAPI_TOKEN`, `GAME_CONFIG_APOLLO_ENV`, `GAME_CONFIG_APOLLO_OPERATOR`, `GAME_CONFIG_APOLLO_PUSH_KEY` |
| `GAME_CONFIG_INTEGRATION_CONSUL=true` or `-Dgame.config.integration.consul=true` | Consul Agent | `GAME_CONFIG_CONSUL_ENDPOINT`, `GAME_CONFIG_CONSUL_TOKEN`, `GAME_CONFIG_CONSUL_KEY` |
| `GAME_CONFIG_INTEGRATION_NACOS=true` or `-Dgame.config.integration.nacos=true` | Nacos Config Service | `GAME_CONFIG_NACOS_SERVER_ADDR`, `GAME_CONFIG_NACOS_GROUP`, `GAME_CONFIG_NACOS_DATA_ID`, `GAME_CONFIG_NACOS_NAMESPACE`, `GAME_CONFIG_NACOS_USERNAME`, `GAME_CONFIG_NACOS_PASSWORD` |
| `GAME_CONFIG_INTEGRATION_ETCD=true` or `-Dgame.config.integration.etcd=true` | Etcd | `GAME_CONFIG_ETCD_ENDPOINTS`, `GAME_CONFIG_ETCD_DATA_ID` |
