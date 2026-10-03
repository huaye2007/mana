# OGBS Spring Java 25 开发规范 1.0

**[English](OGBS-Spring-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Spring-Java-25-Specification-1.0.zh-CN.md)

对应 [容器集成语义](OGBS-Spring-1.0.zh-CN.md)。已实现可选 artifact `cn.managame:game-spring:1.0.0-SNAPSHOT`，使用 Java 25，依赖 game-runtime、game-data 及 spring-context 7.0.9，不依赖 Spring Boot，核心组件不反向依赖 Spring。

## 1. Runtime 注册

`cn.managame.spring.runtime.EnableGameRuntime` 导入内部配置，扫描 Handler/EventHandler/HttpHandler 标记及声明/继承 Cron 方法的类。`String[] basePackages() default {}` 对应 ComponentScan.basePackages，空值使用被注解配置类的包，采用标准 Spring profile/名称规则，不需额外 Component。声明类型可识别的既有 Bean 产物也参与；不为推断任务类型初始化未知 FactoryBean 产物。发现结果构建时冻结，按目标对象身份去重；prototype 对象和可识别 Spring AOP 代理以 IllegalArgumentException 拒绝。Runtime 校验注解签名，包括非法 private 方法。Runtime 注解不包含 Spring 注解。

函数式 Bean 扩展 `GameRuntimeConfigurer.configure(GameRuntimeBuilder)` 在收集 Handler/任务及全部 ProtocolProvider Bean 后按 Spring 顺序执行。Builder 集合方法替换列表，显式 configurer 可覆盖扫描结果。应用提供 Domain/RouteExecutor 绑定及接入身份策略。执行器宜为 destroyMethod="close" 的 Bean，确保启动失败后释放调用方资源；成功构建转移生命周期至 Runtime，内置 close 幂等。配置创建名为 gameRuntime 的单例，绑定 Events 供外部静态发布；绑定冲突时关闭新实例。Handler 构造器不能依赖正在构造的 Runtime，延迟 provider 只能启动完成后使用。

## 2. Data 注册

`cn.managame.spring.data.EnableGameData` 在 basePackages 扫描 Repository，采用相同的默认包规则并导入内部配置。应用需提供持有的 DataSource Bean。默认使用 GameDataBuilder.mysql(dataSource)，发现已知单例 Bean 中直接继承 SingleRepository、GroupRepository 或 LogRepository 的具体类型并注册。静态 BeanFactoryPostProcessor 把实例供应器替换成 `gameData.repository(type)` 并添加 gameData 依赖，保留名称/限定符及字段/setter 注入。Data 仅创建一次无参实例，初始化后才进入 Spring 注入回调；不支持构造器注入。未继承这三个基类的其他 Repository 是普通 Spring Bean。prototype Data Repository 在数据库访问前拒绝。

`GameDataConfigurer.configure(GameDataBuilder)` Bean 在数据源/Repository 配置后、build 前按 Spring 顺序执行，可配置 JSON codec、回写/错误策略或显式 mapper 覆盖。configurer 依赖不能要求正在构建的 Data/Repository。gameData 单例采用 destroyMethod="close"，借用 DataSource，不关闭应用连接池。

## 3. 停机

内部 ContextClosedEvent 监听器使用 Ordered.HIGHEST_PRECEDENCE、supportsAsyncExecution=false。在 Spring 普通同步事件广播下只处理自身 Context，执行 Runtime.shutdown，以每次 30 秒间隔重复等待、无总超时，然后 Runtime.close。中断不放弃排空，结束后恢复中断状态。普通关闭监听器（例如 demo TCP/HTTP 关闭）随后执行，再销毁 Bean 和 Data 最终回写。不得安装更早/同优先级的资源关闭监听器、以自定义异步广播改变顺序，或在 Runtime Route 关闭 Context，否则违反 S-CLOSE-01。refresh 失败按 Spring 销毁清理，此时不应已暴露业务入口。本模块不持有传输、数据库池或额外业务线程。

## 4. 使用与验证

配置片段：

```java
@Configuration
@EnableGameRuntime(basePackages = "my.game")
@EnableGameData(basePackages = "my.game")
class GameConfig {
    // 提供 DataSource、ProtocolProvider、RouteExecutor 和 GameRuntimeConfigurer Bean。
}
```

完整接入见 [GameRuntimeConfig](../../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java) 及 [GameDataConfig](../../game-demo/src/main/java/cn/managame/demo/common/data/GameDataConfig.java)。[CronBeansTest](../../game-spring/src/test/java/cn/managame/spring/runtime/CronBeansTest.java) 覆盖仅方法注解、继承/default 接口及工厂发现、去重、启动拒绝和真实 Route 执行。[RuntimeLifecycleTest](../../game-spring/src/test/java/cn/managame/spring/runtime/RuntimeLifecycleTest.java) 证明已接纳工作先于普通关闭监听器和资源销毁完成，新 Context 可重新绑定 Events。demo Repository 测试用 JDBC 桩验证初始化注入及三种基类，真实数据库和自定义广播/代理配置仍未验证。根 `mvn clean verify` 检查模块边界，既有 RPC 测试 API 不一致仍为独立记录的阻碍。
