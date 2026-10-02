# game-demo

[English](README.md) | **[简体中文](README.zh-CN.md)**

根 Maven 构建中的普通 Spring 应用，使用 JDK 25 和仓库父 POM，应用源码位于 `cn.managame.demo`。`spring-context:7.0.9` 提供依赖注入；应用层 HikariCP 与 MySQL Driver 提供连接池和 JDBC 实现。

[GameDemo](src/main/java/cn/managame/demo/GameDemo.java) 启动 Spring Context 并注册关闭钩子。[MysqlConfig](src/main/java/cn/managame/demo/common/mysql/MysqlConfig.java) 加载 `application.properties` 并创建应用持有的连接池。业务 Repository 类使用 Spring 注解：

```java
@Repository
public class UserRepository extends SingleRepository<Long, User> {
}
```

在 `src/main/resources/application.properties` 配置 `game.db.url`、`game.db.username`、`game.db.password` 后，使用 JDK 25 在 IDE 启动 `cn.managame.demo.GameDemo`。数据库需预先存在，Data 会初始化缺失的状态表/列/索引。JDBC URL 缺失或为空时，在创建连接池前报错并明确提示配置 `game.db.url`。也可通过 JVM 系统属性和环境变量向 Spring Environment 提供这些配置键。

`@PropertySource` 加载文件，Spring 默认的内嵌值解析器负责注入 `@Value`，无需显式声明 `PropertySourcesPlaceholderConfigurer` Bean。初始化成功后，入口输出 `game-demo started. Press Ctrl+C to stop.`，并返回 main 执行用户/角色写入示例。当前入口没有阻塞等待及显式 Context 关闭；JVM 退出时，Spring 关闭钩子按依赖顺序先关闭 Data，再关闭应用持有的连接池。不新增保活线程；目前尚未创建 HTTP 监听器。

[GameDataConfig](src/main/java/cn/managame/demo/common/data/GameDataConfig.java) 收集扫描到的、继承 SingleRepository、GroupRepository 或 LogRepository 的 `@Repository` Bean，不提前实例化。它将类型注册到 GameDataBuilder，并把 Bean 的实例供应器改为 `data.repository(type)`，保留 Bean 名称、限定符及 Spring 字段/setter 注入。Data 初始化实例后才进入 Spring 注入回调或供业务使用；在扫描范围内新增 Repository 无需逐个注册类型或声明 `@Bean` 方法。Repository 必须为单例作用域，其他作用域在启动时拒绝；仍遵守 Data 直接继承具体参数化基类及无参构造的要求。实例由 GameData 构造，因此不支持构造器依赖注入。此适配位于 demo 应用层，game-data 不依赖 Spring。

User 的 JSON Map 初始化为 ConcurrentHashMap<Integer,Long>，默认加载保留具体实现及泛型类型。空 Map 默认值由 Java 初始化和全字段 INSERT 提供；`defaultValue="{}"` 不是 SQL DDL 表达式，已省略。详见 [默认 JSON 类型绑定与边界](../docs/ogbs/OGBS-Data-Java-25-Specification-1.0.zh-CN.md#default-json-field-binding)。自定义行为通过 `jsonCodec(...)` 配置。

在仓库根目录构建：

```shell
mvn -pl game-demo -am clean verify
```

[DataSpringWiringTest](src/test/java/cn/managame/demo/DataSpringWiringTest.java) 使用内存 JDBC 桩验证无需显式占位符配置器的属性注入、组件扫描、已初始化 Data Repository 的注入及 Data 关闭。同时验证 URL 缺失时拒绝启动，以及入口持续运行直至 Context 关闭或主线程中断。[DataRepositoryRegistrationTest](src/test/java/cn/managame/demo/DataRepositoryRegistrationTest.java) 覆盖三种 Repository、只构造一次、注入回调中使用已初始化 Repository，以及拒绝 prototype 作用域。当前 demo 验证为四项通过、一项生命周期测试失败：测试要求主线程等待及处理中断，而入口在初始化后返回。生命周期测试的同步调整尚未完成。桩测试不验证原生 MySQL；此前使用阻塞入口验证过配置的本地 MySQL 启动和关闭；本次验证未对当前非阻塞的用户/角色写入示例执行真实 MySQL 操作。这些检查不代表所有数据库操作和故障场景均已验证。根 `clean verify` 仍被引用 `maxPendingCalls` 等已移除 API 的既有 RPC 测试挡住；demo 生产源码可编译，但当前 `clean verify` 因生命周期测试而失败。模块使用既有 [OGBS 规范](../docs/ogbs/README.zh-CN.md)，不新增框架契约；组件示例仍位于 [game-example](../game-example/README.zh-CN.md)。
