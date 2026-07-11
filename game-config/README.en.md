[中文](README.md) | English

# game-config

`game-config` is a consumer-side configuration client. It merges one or more Java Properties documents in declaration order into an immutable snapshot. Later documents override duplicate keys from earlier ones. A single API provides typed reads, reloads, and change notifications.

The current implementation contains four modules:

- `game-config-core`: `ConfigCenter`, immutable `ConfigSnapshot`, SPI, and factory.
- `game-config-local`: local Properties files with filesystem-event hot reload.
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
        .resource("config/application.properties")
        .build())) {
    int port = config.snapshot().getInt("game.server.port", 8080);
    AutoCloseable listener = config.listen(change ->
            System.out.println(change.changedKeys()));
}
```

Nacos:

```java
ConfigOptions.builder("nacos")
        .endpoint("127.0.0.1:8848")
        .resource("GAME:base.properties")
        .resource("GAME:application.properties")
        .property("timeoutMillis", "3000")
        .build();
```

Nacos client properties such as `namespace`, `username`, and `password` may also be supplied through `properties`; `group` and `timeoutMillis` are interpreted by this library.

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

## Semantics

- `ConfigFactory.open` completes the initial load synchronously and never returns a partially initialized center.
- A push creates a new version and `ConfigChange` only when the merged content actually changes.
- Listener failures are isolated and do not prevent snapshot publication.
- Empty/deleted Nacos content and deleted Etcd keys become empty documents while other resources remain intact.
- `close()` unregisters listeners and closes filesystem watchers or remote clients.
