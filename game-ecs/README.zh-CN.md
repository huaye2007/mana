[English](README.md) | 中文

# game-ecs

`game-ecs` 是一个零运行时依赖、面向服务端确定性游戏模拟的实体组件系统核心。
它提供稳定实体标识、按精确类型存储的组件、带索引的多组件查询、有序系统、
生命周期回调和延迟结构变更命令缓冲区。

本模块刻意不依赖 `game-runtime`、网络和持久化模块。宿主可以将一个 `EcsWorld`
绑定到一个 runtime routerKey，使该世界的所有模拟操作始终在同一逻辑线程执行。

## 功能

- 稳定、单调递增且永不复用的 `EntityId`
- 支持添加、替换、查找、强制获取、移除和销毁的流式实体句柄
- 按实体 ID 确定性排序、采用快照迭代的可复用 AND 查询
- 先按优先级、再按注册顺序执行的系统
- `onAdded` / `onRemoved` 生命周期回调
- 每次世界更新后自动刷新的 FIFO 命令缓冲区
- 可选且支持合并的实体/组件/字段变更跟踪，用于客户端同步
- 无运行时依赖

## 快速开始

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

    world.update(0.05); // 一次 20 Hz 模拟 tick
}
```

## 延迟结构变更

查询采用快照迭代，因此可以在系统更新时安全替换组件值。如果实体或组件集合应在
所有系统都看到当前 tick 后才发生变化，请使用命令缓冲区：

```java
world.commands().destroyEntity(entity.id());
world.commands().createEntity(created -> created.add(new Position(0, 0)));
```

在 `update` 中提交的命令会在所有系统执行完毕后刷新。在 tick 外提交命令时，
请调用 `world.flushCommands()`。

## 客户端同步

`game-ecs` 只产出与传输协议无关的 `WorldChangeBatch`。宿主负责将它映射为稳定的
协议组件 ID，使用 JSON、Protobuf 或 Fory 序列化，并路由到关注这些实体的客户端
会话；ECS 模块本身不依赖具体网络实现。

请在创建客户端可见实体前注册 sink：

```java
world.addChangeSink(batch -> {
    // 在这里把组件 Class 键和值转换为稳定的协议 DTO。
    ChangePacket packet = changePacketMapper.toPacket(batch);
    outboundQueue.offer(packet); // 非阻塞地交给网络发送层
});
```

实体创建/销毁以及组件新增/替换/删除会自动记录。可变业务字段应通过类型安全的
字段更新 API 修改：

```java
player.updateField(
        RoleStats.class,
        "hp",
        RoleStats::hp,
        RoleStats::setHp,
        newHp);
```

getter 会在 setter 前后各执行一次，因此 `FieldChange` 中记录的是实际生效的旧值
和新值，也能反映组件内部的数值裁剪。同一个发送窗口内对同一字段的多次修改会
合并为“第一次旧值 → 最后一次新值”；如果最终回到原值，则自动取消该变化。

对于集合的原地修改，应保存不可变快照并显式标记字段：

```java
List<Item> before = List.copyOf(inventory.items());
inventory.items().add(item);
player.markChanged(
        Inventory.class, "items", before, List.copyOf(inventory.items()));
```

注册 sink 后，每个 tick 的系统和延迟命令全部完成时会自动发布非空批次。
采用主动拉取的宿主可以这样处理：

```java
world.enableChangeTracking();
world.drainChanges().ifPresent(clientSync::enqueue);
```

变更跟踪只包含启用之后发生的修改。客户端中途进入已有世界时，应先发送一份
关注范围内的完整快照，再消费后续增量批次。sink 回调运行在世界线程中，应在
回调内立即序列化或复制组件载荷，不能执行阻塞网络 I/O。

## 线程模型

`EcsWorld` 按设计为单线程模型。同一世界的所有操作应路由到一个所有者线程，或
一个串行的 `game-runtime` routerKey。不同世界可以并行更新。

## 构建与测试

```powershell
mvn "-Dmaven.repo.local=.m2" -f game-ecs\pom.xml test
```

## 许可证

本项目基于 [Apache License 2.0](../LICENSE) 开源。
