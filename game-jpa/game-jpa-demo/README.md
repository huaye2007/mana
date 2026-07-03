# game-jpa-demo

`game-jpa-demo` 是一个集成风格的演示模块，用于在不依赖 MySQL 或 MongoDB 的情况下验证 `game-jpa` 的主运行路径。

该演示定义了一个横跨两种存储模型的玩家场景：

- `PlayerAccount`：RDB 实体，包含表映射、索引、JSON 字段、版本字段，以及非 id 分片键。
- `PlayerProfile`：DocDB 文档，包含索引字段、内嵌字段和分片字段。

`game.jpa.demo.executor` 下的内存执行器会按 `ExecutorContext` 隔离数据，因此测试可以验证 Repository 调用是否路由到了预期的数据源、物理表或集合。

运行演示模块以及所有 `game-jpa` 模块测试：

```bash
mvn "-Dmaven.repo.local=..\.m2" -f game-jpa\pom.xml test
```

只运行演示模块及其所需的上游模块：

```bash
mvn "-Dmaven.repo.local=..\.m2" -pl game-jpa/game-jpa-demo -am test
```
