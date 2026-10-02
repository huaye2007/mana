# game-demo

[English](README.md) | **[简体中文](README.zh-CN.md)**

根 Maven 构建中的普通 Spring 应用，使用 JDK 25 和仓库父 POM，应用源码位于 `cn.managame.demo`。`spring-context:7.0.9` 提供依赖注入；应用层 HikariCP 与 MySQL Driver 提供连接池和 JDBC 实现。

[GameDemo](src/main/java/cn/managame/demo/GameDemo.java) 启动 Spring Context 并注册关闭钩子。[MysqlConfig](src/main/java/cn/managame/demo/common/mysql/MysqlConfig.java) 加载 `application.properties`、创建应用持有的连接池，并构建 Data：

```java
GameData data = GameDataBuilder.builder()
        .mysql(dataSource)
        .repositories(UserRepository.class)
        .build();
UserRepository repository = data.repository(UserRepository.class);
```

在 IDE 启动 GameDemo 前配置 `game.db.url`、`game.db.username`、`game.db.password`。当前尚未创建 HTTP 监听器；入口初始化 Bean，不包含阻塞服务循环。Spring 关闭钩子按依赖顺序先关闭 Data，再关闭连接池。启动会让 Data 初始化缺失的状态表/列/索引，应使用计划初始化的数据库。

Repository Bean 来自 GameData，避免组件扫描另建一个未初始化实例。User 的 JSON Map 初始化为 ConcurrentHashMap<Integer,Long>，默认加载保留具体实现及泛型类型。空 Map 默认值由 Java 初始化和全字段 INSERT 提供；`defaultValue="{}"` 不是 SQL DDL 表达式，已省略。详见 [默认 JSON 类型绑定与边界](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md#default-json-field-binding)。自定义行为通过 `jsonCodec(...)` 配置。

在仓库根目录构建：

```shell
mvn -pl game-demo -am clean verify
```

[DataSpringWiringTest](src/test/java/cn/managame/demo/DataSpringWiringTest.java) 使用内存 JDBC 桩验证 Spring 注入已初始化 Data Repository 并关闭 Data，不连接已配置数据库，也不验证原生 MySQL。本任务尚未验证真实数据库启动。模块使用既有 [OGBS 规范](../docs/ogbs/README.zh-CN.md)，不新增框架契约；组件示例仍位于 [game-example](../game-example/README.zh-CN.md)。
