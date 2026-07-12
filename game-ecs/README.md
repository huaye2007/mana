[English](README.en.md) | 中文

# game-ecs

`game-ecs` 是面向游戏场景、房间和战斗模拟的轻量 ECS（Entity Component System）框架。核心层不依赖 Spring、网络、RPC 或持久化实现；可选 runtime 桥接把世界更新提交到 `game-runtime`，与相同 `routerKey` 的玩家/场景任务串行执行。

## 模块

| 模块 | 职责 |
| --- | --- |
| `game-ecs-core` | 实体生命周期、按具体类型存储的组件、组件交集查询、系统流水线、命令缓冲和生命周期监听 |
| `game-ecs-runtime` | 使用 `TimingWheel` 触发更新，并通过 `ExecutorGroupRegistry` 按 `(group, routerKey)` 投递世界 tick |

核心层无第三方运行时依赖。若只需要手动游戏循环，仅依赖 `game-ecs-core`；需要与 mana 任务调度组合时依赖 `game-ecs-runtime`。

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-ecs-runtime</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## 快速开始

组件只承载数据。实体 ID 在单个世界内唯一、单调递增且不复用：

```java
record Position(float x, float y) implements Component {}
record Velocity(float x, float y) implements Component {}

World world = new World();
Entity player = world.createEntity();
world.add(player, new Position(0, 0));
world.add(player, new Velocity(3, 1));
```

系统按 `INPUT → SIMULATION → POST_SIMULATION` 执行，同一阶段再按 `order()` 升序执行。查询返回不可变快照；新增/删除实体或组件时建议写入 `CommandBuffer`，当前系统结束后、下一系统开始前统一应用：

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
pipeline.update(world, 50_000_000L); // 手动推进 50 ms
```

需要结构变更的系统可以使用传入的命令缓冲：

```java
commands.spawn(new Position(10, 20));
commands.remove(player, Velocity.class);
commands.delete(player);
```

## 与 game-runtime 组合

先按 `game-runtime` 约定注册场景执行器，再启动世界 runner。每个世界使用稳定且非 0 的 `routerKey`（通常是 sceneId / roomId）：

```java
ExecutorGroupRegistry.getInstance().register(
        DefaultExecutorGroup.platformThreads(ExecutorGroups.SCENE, "scene", 8, 10_000));

EcsWorldRunner runner = new EcsWorldRunner(
        world, pipeline, ExecutorGroups.SCENE, sceneId, 50); // 20 Hz
runner.start();

// 停服：先停止生产 ECS tick，再按 runtime 的顺序关闭时间轮和执行器组
runner.close();
TimingWheel.getInstance().shutdown();
ExecutorGroupRegistry.getInstance().shutdownAll(5_000);
```

runner 至多保留一个等待/执行中的 tick。若单帧耗时超过周期，后续定时触发会合并，避免执行器队列无限堆积；下一帧收到的 `deltaNanos` 是实际单调时钟间隔。

## 与其他组件组合

- 网络/RPC handler 只负责把输入转换为 runtime 任务；使用同一场景 `routerKey` 后，可安全读取或修改对应 `World`。
- `WorldListener` 可把实体/组件生命周期映射为 runtime 事件、脏数据标记或 JPA 持久化命令；核心层不绑定具体方案。
- 配置中心更新应先转换为对应场景的 runtime 任务，再更新系统参数，避免跨线程直接修改世界。

## 使用约束

- `World` 有意设计为非线程安全；一个世界的所有访问必须线程封闭，推荐统一路由到同一 `(group, routerKey)`。
- 组件按运行时具体类索引；查询基类或接口不会匹配实现类。
- 系统注册应在启动期完成。系统异常会立即向上传播，该系统尚未应用的命令缓冲会被丢弃。
- `WorldListener` 回调同步执行，必须轻量、非阻塞。

## 构建

```powershell
mvn "-Dmaven.repo.local=.m2" -pl game-ecs/game-ecs-runtime -am test
```
