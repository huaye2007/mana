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

## Listener contract

`ServiceInstanceListener` is invoked **synchronously** by the provider. Which thread it runs on depends on the provider and on where the event came from:

| provider | initial snapshot | incremental events |
|---|---|---|
| memory | the thread that called `watchService` | the `register` / `deregister` caller that triggered the change |
| nacos | the thread that called `watchService` | a Nacos client notifier thread |
| etcd | the thread that called `watchService` | a jetcd gRPC callback thread / an internal recovery thread |

A listener therefore **must return quickly**:

- do not block on IO, wait on locks, or run long computations inside the callback — hand that work to your own thread;
- do not call back into the same registry from the callback; it may reenter or deadlock;
- exceptions thrown by a listener are logged and dropped by the provider. They are not retried and never propagate to the caller.

Events carry no cross-provider guarantees either: **whether a redundant re-registration produces an event, and the order in which `getInstances` returns instances, are both provider-specific** — do not depend on them. Watch reconnection also happens silently in the background and the API has no error channel, so "no events" may mean "no instances" or "the underlying watch is reconnecting". If you need to tell those apart, use the logs or your own health check.

## Service instances

A `ServiceInstance` is identified by `getKey()`: the `id` when one is set, otherwise `address:port`.

**Always set a stable `id` explicitly.** Without one, changing the address changes the identity: the new address registers as a new instance and the record for the old address survives until the provider expires it (for etcd, when the lease runs out).

## Provider properties

Keys in `RegistryConfig.properties(...)` that a provider **does not recognise are silently ignored** — misspelling `leaseTtlSeconds` as `leaseTtlSecond` just falls back to the default — so check spelling against the tables below.

For Nacos, use `.type("nacos")` and the Nacos server address as `endpoints`. This module consumes only `group` (the service group, default `DEFAULT_GROUP`); **every other property is passed straight through to the Nacos client**, so consult the Nacos documentation for valid keys. Note that `getInstances` is a one-shot query and does not create a subscription; only `watchService` subscribes.

For Etcd, use `.type("etcd")`; `endpoints` accepts a comma-separated list. The properties this module consumes are:

| property | default | meaning |
|---|---|---|
| `prefix` | `/mana/services` | key prefix |
| `leaseTtlSeconds` | `10` | lease TTL, and the overall budget for lease recovery |
| `operationTimeoutMillis` | `5000` | timeout for a single etcd operation |
| `username` / `password` | none | credentials |

Registered instances are attached to the client lease, which is revoked when the registry closes. A keepalive failure re-grants a lease and restores locally owned registrations. A failed watch reloads a snapshot, resumes from a new revision, and emits the instance differences missed during the outage. Entries under the prefix that this module cannot decode — written by hand, or by a writer using a newer format version — are skipped with a warning and do not affect queries or watches for the other instances sharing that prefix.
