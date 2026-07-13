[English](README.en.md) | 中文

# game-ecs

`game-ecs` 是面向游戏场景、房间和战斗模拟的轻量 ECS（Entity Component System）框架。核心层不依赖 Spring、网络、RPC 或持久化实现；可选 runtime 为每个世界提供独立的平台线程，由线程自身持续执行游戏循环。

## 结构

`game-ecs` 以单个 JAR 发布，内部通过 Java 包区分职责：

| 包 | 职责 |
| --- | --- |
| `cn.managame.ecs` | 实体生命周期、按具体类型存储的组件、组件交集查询、系统流水线、命令缓冲和生命周期监听 |
| `cn.managame.ecs.runtime` | 为世界提供独立线程、固定节拍更新循环、跨线程输入队列和完整启停生命周期 |

该组件不依赖具体网络、RPC 或任务调度实现。宿主可以直接使用核心类型驱动自有循环，也可以使用 `EcsWorldRunner` 管理世界线程。

```xml
<dependency>
    <groupId>cn.managame</groupId>
    <artifactId>game-ecs</artifactId>
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

## 世界运行循环

每个 runner 拥有一个专属平台线程。`start()` 启动循环，`close()` 中断等待并阻塞到线程退出：

```java
EcsWorldRunner runner = new EcsWorldRunner(
        world, pipeline, "ecs-scene-" + sceneId, 50); // 20 Hz
runner.start();

// 网络/RPC 线程不得直接修改 World，输入投递到世界线程
runner.execute(() -> world.add(player, new Velocity(5, 2)));

// 停服：停止并等待世界线程退出
runner.close();
```

循环使用 `System.nanoTime()` 计算实际 `deltaNanos`。若输入处理与系统更新超过一帧周期，runner 会跳过已经错过的截止点，再等待下一个完整周期，不会并发更新同一世界，也不会连续补帧。输入队列默认上限为 16,384，可通过五参数构造器调整；队列已满或 runner 已停止时，`execute` 抛出 `RejectedExecutionException`。

## 与其他组件组合

- 网络/RPC handler 只负责把输入转换为 `Runnable` 并调用对应 runner 的 `execute`；输入会在下一帧更新前由世界线程串行处理。
- `WorldListener` 可把实体/组件生命周期映射为 runtime 事件、脏数据标记或 JPA 持久化命令；核心层不绑定具体方案。
- 配置中心更新也应通过对应 runner 的 `execute` 修改世界或系统参数，避免跨线程写入。

## 使用约束

- `World` 有意设计为非线程安全；runner 启动后，一个世界的所有访问必须经 `execute` 进入其循环线程。
- `tickOnce` 仅供从未启动 runner 的确定性测试或宿主自有循环使用；runner 一旦启动便禁止手动 tick。
- 组件按运行时具体类索引；查询基类或接口不会匹配实现类。
- 系统注册应在启动期完成。系统异常会立即向上传播，该系统尚未应用的命令缓冲会被丢弃。
- `WorldListener` 回调同步执行，必须轻量、非阻塞。

## 构建

```powershell
mvn "-Dmaven.repo.local=.m2" -pl game-ecs test
```
