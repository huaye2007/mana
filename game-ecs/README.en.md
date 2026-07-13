[中文](README.md) | English

# game-ecs

`game-ecs` is a lightweight Entity Component System for game scenes, rooms, and combat simulation. The core is independent of Spring, networking, RPC, and persistence implementations. Its optional runtime gives each world a dedicated platform thread that continuously drives its own game loop.

## Structure

`game-ecs` is published as one JAR and uses Java packages to separate responsibilities:

| Package | Responsibility |
| --- | --- |
| `cn.managame.ecs` | Entity lifecycle, concrete-type component storage, intersection queries, system pipelines, command buffers, and lifecycle listeners |
| `cn.managame.ecs.runtime` | Dedicated world threads, fixed-cadence update loops, cross-thread input queues, and complete start/stop lifecycle |

The component does not depend on a concrete network, RPC, or task-scheduling implementation. A host may drive the core types with its own loop or use `EcsWorldRunner` to manage a world thread.

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-ecs</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

Components contain data only. Entity IDs are unique within a world, monotonically increasing, and never reused:

```java
record Position(float x, float y) implements Component {}
record Velocity(float x, float y) implements Component {}

World world = new World();
Entity player = world.createEntity();
world.add(player, new Position(0, 0));
world.add(player, new Velocity(3, 1));
```

Systems run in `INPUT → SIMULATION → POST_SIMULATION` phase order and then by ascending `order()` within each phase. Queries return immutable snapshots. Queue entity or component structural changes in the supplied `CommandBuffer`; they are applied after the current system and before the next one:

```java
EcsSystem movement = (currentWorld, deltaNanos, commands) -> {
    float seconds = deltaNanos / 1_000_000_000f;
    for (Entity entity : currentWorld.query(Position.class, Velocity.class)) {
        Position p = currentWorld.get(entity, Position.class).orElseThrow();
        Velocity v = currentWorld.get(entity, Velocity.class).orElseThrow();
        currentWorld.add(entity, new Position(
                p.x() + v.x() * seconds,
                p.y() + v.y() * seconds));
    }
};

SystemPipeline pipeline = new SystemPipeline().add(movement);
pipeline.update(world, 50_000_000L); // manually advance 50 ms
```

Systems that need structural changes can use their command buffer:

```java
commands.spawn(new Position(10, 20));
commands.remove(player, Velocity.class);
commands.delete(player);
```

## World Update Loop

Each runner owns one platform thread. `start()` starts the loop; `close()` interrupts its wait and blocks until the thread exits:

```java
EcsWorldRunner runner = new EcsWorldRunner(
        world, pipeline, "ecs-scene-" + sceneId, 50); // 20 Hz
runner.start();

// Network/RPC threads must not modify World directly; enqueue input on the world thread.
runner.execute(() -> world.add(player, new Velocity(5, 2)));

// Shutdown: stop and wait for the world thread to exit.
runner.close();
```

The loop uses `System.nanoTime()` to calculate the actual `deltaNanos`. If input processing and system updates overrun a frame period, the runner skips missed deadlines and waits for the next full period. It never updates one world concurrently or runs back-to-back catch-up ticks. The input queue is bounded at 16,384 entries by default and can be changed with the five-argument constructor; `execute` throws `RejectedExecutionException` when the queue is full or the runner has stopped.

## Combining with Other Components

- Network and RPC handlers translate input into a `Runnable` and call `execute` on the corresponding runner. The world thread processes that input serially before the next update.
- A `WorldListener` can map entity/component lifecycle changes to runtime events, dirty-data markers, or JPA persistence commands without binding the core to a specific approach.
- Configuration-center updates should also use the corresponding runner's `execute` method before changing world or system state.

## Contracts

- `World` is intentionally not thread-safe. After a runner starts, all access to its world must enter through `execute` and run on the loop thread.
- `tickOnce` is only for deterministic tests or host-owned loops before the runner has ever started; manual ticks are rejected once its loop starts.
- Components are indexed by their concrete runtime class. Querying a base class or interface does not match implementations.
- Register systems during startup. A system exception propagates immediately and discards that system's unapplied command buffer.
- `WorldListener` callbacks are synchronous and must remain lightweight and non-blocking.

## Build

```powershell
mvn "-Dmaven.repo.local=.m2" -pl game-ecs test
```
