[中文](README.zh-CN.md) | English

# game-registry

`game-registry` provides a small, unified service registration and discovery API. It currently supports memory, Nacos, and Etcd.

## Modules

- `game-registry-core`: the `ServiceRegistry` API, immutable service instance model, and SPI factory.
- `game-registry-memory`: a same-JVM registry shared and isolated by `endpoints`, intended for development and tests.
- `game-registry-nacos`: ephemeral instance registration, lookup, and subscriptions backed by Nacos Naming Service.
- `game-registry-etcd`: an Etcd provider backed by leases and prefix watches.

## Usage

Depend on core and select at least one runtime provider:

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-registry-core</artifactId>
</dependency>
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-registry-memory</artifactId>
    <scope>runtime</scope>
</dependency>
```

```java
ServiceRegistry registry = RegistryFactory.startRegistry(RegistryConfig.builder()
        .type("memory")
        .endpoints("local")
        .build());

registry.register(ServiceInstance.builder()
        .name("game-server")
        .id("game-1")
        .address("127.0.0.1")
        .port(9000)
        .build());

AutoCloseable watch = registry.watchService("game-server", event -> {
    // ADDED / UPDATED / REMOVED
});
```

A watch synchronously emits the current instances as `ADDED` events, followed by incremental events. Closing a registry stops its watches and deregisters instances owned by that client.

For Nacos, use `.type("nacos")` and the Nacos server address as `endpoints`. Nacos client properties can be passed through `RegistryConfig.properties(...)`; `group` selects the service group and defaults to `DEFAULT_GROUP`.

For Etcd, use `.type("etcd")`; `endpoints` accepts a comma-separated list. Optional properties are `prefix` (default `/mana/services`), `leaseTtlSeconds` (default 10), `operationTimeoutMillis` (default 5000), `username`, and `password`. Registered instances are attached to the client lease, which is revoked when the registry closes. A keepalive failure re-grants a lease and restores locally owned registrations. A failed watch reloads a snapshot, resumes from a new revision, and emits the instance differences missed during the outage.
