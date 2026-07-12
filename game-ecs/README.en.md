[中文](README.md) | English

# game-ecs

`game-ecs` is a lightweight Entity Component System for game scenes, rooms, and combat simulation. The core is independent of Spring, networking, RPC, and persistence implementations. Its optional runtime bridge submits world updates to `game-runtime`, serializing them with player or scene tasks that use the same router key.

## Modules

| Module | Responsibility |
| --- | --- |
| `game-ecs-core` | Entity lifecycle, concrete-type component storage, intersection queries, system pipelines, command buffers, and lifecycle listeners |
| `game-ecs-runtime` | Triggers updates with `TimingWheel` and dispatches world ticks through `ExecutorGroupRegistry` by `(group, routerKey)` |

The core has no third-party runtime dependencies. Depend only on `game-ecs-core` for a manual game loop, or use `game-ecs-runtime` to combine it with mana scheduling.

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-ecs-runtime</artifactId>
    <version>1.0.0-SNAPSHOT</version>
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

## Combining with game-runtime

Register a scene executor as required by `game-runtime`, then start a world runner. Give each world a stable, non-zero router key, normally its sceneId or roomId:

```java
ExecutorGroupRegistry.getInstance().register(
        DefaultExecutorGroup.platformThreads(ExecutorGroups.SCENE, "scene", 8, 10_000));

EcsWorldRunner runner = new EcsWorldRunner(
        world, pipeline, ExecutorGroups.SCENE, sceneId, 50); // 20 Hz
runner.start();

// Shutdown: stop producing ECS ticks, then follow the runtime shutdown order.
runner.close();
TimingWheel.getInstance().shutdown();
ExecutorGroupRegistry.getInstance().shutdownAll(5_000);
```

The runner keeps at most one queued or running tick. If a frame exceeds the configured period, later timer firings coalesce instead of growing the executor queue without bounds. The next frame receives the actual monotonic-clock interval in `deltaNanos`.

## Combining with Other Components

- Network and RPC handlers translate input into runtime tasks. With the same scene router key, those tasks may safely read or modify the corresponding `World`.
- A `WorldListener` can map entity/component lifecycle changes to runtime events, dirty-data markers, or JPA persistence commands without binding the core to a specific approach.
- Convert configuration-center updates into runtime tasks for the appropriate scene before changing system parameters; do not mutate a world directly from a configuration callback thread.

## Contracts

- `World` is intentionally not thread-safe. Confine all access to one thread, preferably by routing it to the same `(group, routerKey)`.
- Components are indexed by their concrete runtime class. Querying a base class or interface does not match implementations.
- Register systems during startup. A system exception propagates immediately and discards that system's unapplied command buffer.
- `WorldListener` callbacks are synchronous and must remain lightweight and non-blocking.

## Build

```powershell
mvn "-Dmaven.repo.local=.m2" -pl game-ecs/game-ecs-runtime -am test
```
