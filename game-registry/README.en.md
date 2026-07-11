[中文](README.md) | English

# game-registry

`game-registry` provides a small, unified service registration and discovery API. It currently supports memory and Nacos.

## Modules

- `game-registry-core`: the `ServiceRegistry` API, immutable service instance model, and SPI factory.
- `game-registry-memory`: a same-JVM registry shared and isolated by `endpoints`, intended for development and tests.
- `game-registry-nacos`: ephemeral instance registration, lookup, and subscriptions backed by Nacos Naming Service.

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
