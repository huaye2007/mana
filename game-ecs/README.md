[中文](README.zh-CN.md) | English

# game-ecs

`game-ecs` is a dependency-free entity-component-system core for deterministic
server-side game simulation. It provides stable entity identifiers, exact-type
component storage, indexed multi-component queries, ordered systems, lifecycle
callbacks, and a deferred structural-change command buffer.

The module is intentionally independent from `game-runtime`, networking, and
persistence. A host can place one `EcsWorld` behind a runtime router key so all
simulation access stays on the same logical thread.

## Features

- Stable, monotonically increasing `EntityId` values that are never reused
- Fluent entity handles with add, replace, find, require, remove, and destroy operations
- Reusable AND queries with deterministic entity-id ordering and snapshot iteration
- Systems ordered by priority and then registration order
- `onAdded` / `onRemoved` lifecycle callbacks
- FIFO command buffer, automatically flushed after each world update
- Optional, coalesced entity/component/field change tracking for client synchronization
- No runtime dependencies

## Quick Start

```java
import cn.managame.ecs.Component;
import cn.managame.ecs.EcsWorld;
import cn.managame.ecs.Entity;
import cn.managame.ecs.EntitySystem;

record Position(double x, double y) implements Component {}
record Velocity(double x, double y) implements Component {}

final class MovementSystem implements EntitySystem {
    @Override
    public void update(EcsWorld world, double deltaSeconds) {
        for (Entity entity : world.query(Position.class, Velocity.class)) {
            Position position = entity.require(Position.class);
            Velocity velocity = entity.require(Velocity.class);
            entity.add(new Position(
                    position.x() + velocity.x() * deltaSeconds,
                    position.y() + velocity.y() * deltaSeconds));
        }
    }
}

try (EcsWorld world = new EcsWorld()) {
    world.addSystem(new MovementSystem());
    world.createEntity()
            .add(new Position(0, 0))
            .add(new Velocity(3, 1));

    world.update(0.05); // one 20 Hz simulation tick
}
```

## Deferred Structural Changes

Queries iterate over snapshots, so component value replacement is safe during a
system update. When entities or component sets should change only after all
systems have seen the current tick, use the command buffer:

```java
world.commands().destroyEntity(entity.id());
world.commands().createEntity(created -> created.add(new Position(0, 0)));
```

Commands issued during `update` are flushed after every system completes. If
commands are issued outside a tick, call `world.flushCommands()`.

## Client Synchronization

`game-ecs` produces transport-neutral `WorldChangeBatch` values. The host maps
them to stable protocol component ids, serializes them with JSON, Protobuf, or
Fory, and routes them to the interested client sessions. The ECS module does not
depend on a network transport.

Register a sink before creating client-visible entities:

```java
world.addChangeSink(batch -> {
    // Convert component Class keys and values to your stable protocol DTO now.
    ChangePacket packet = changePacketMapper.toPacket(batch);
    outboundQueue.offer(packet); // non-blocking handoff to the network layer
});
```

Entity creation/destruction and component add/replace/remove operations are
tracked automatically. Mutable business fields should be updated through the
typed field API:

```java
player.updateField(
        RoleStats.class,
        "hp",
        RoleStats::hp,
        RoleStats::setHp,
        newHp);
```

The getter runs before and after the setter. The emitted `FieldChange` therefore
contains the effective old and new values, including any clamping performed by
the component. Repeated updates to the same field in one delivery window are
coalesced from the first old value to the last new value. Changes that return to
the original value cancel out.

For in-place collection mutations, capture immutable values and mark the field
explicitly:

```java
List<Item> before = List.copyOf(inventory.items());
inventory.items().add(item);
player.markChanged(
        Inventory.class, "items", before, List.copyOf(inventory.items()));
```

With registered sinks, a non-empty batch is published automatically after the
tick's systems and deferred commands finish. Pull-based hosts can use:

```java
world.enableChangeTracking();
world.drainChanges().ifPresent(clientSync::enqueue);
```

Tracking only includes mutations that happen after it is enabled. A client that
joins an existing world should first receive a full interest-area snapshot and
then consume incremental batches. Sink callbacks execute on the world thread;
serialize or copy component payloads inside the callback and never perform
blocking network I/O there.

## Threading Model

`EcsWorld` is single-threaded by design. Route every operation for the same
world through one owner thread or one serial `game-runtime` router key. Separate
worlds can be updated in parallel.

## Build and Test

```powershell
mvn "-Dmaven.repo.local=.m2" -f game-ecs\pom.xml test
```

## License

This project is open source under the [Apache License 2.0](../LICENSE).
