# game-spring

**[English](README.md)** | [简体中文](README.zh-CN.md)

Runtime 与 MySQL Data 的可选普通 Spring 集成，Java 25、Spring Context 7.0.9，不使用 Spring Boot，核心模块保持不依赖 Spring。

使用 `@EnableGameRuntime(basePackages="my.game")` 发现 Handler/EventHandler/HttpHandler 和 Cron 对象，提供 ProtocolProvider 与 GameRuntimeConfigurer Bean 配置协议、Domain、执行器及业务身份策略。`@EnableGameData(basePackages="my.game")` 配合应用持有的 DataSource 初始化 `@Repository` Bean，可选 GameDataConfigurer 配置 codec/回写。Repository 保留字段/setter 注入，仍需满足 Data 的无参构造及直接泛型基类约束。

Context 关闭时先拒绝新 Runtime 工作，等待已登记工作/回调，再关闭 Runtime 和下游资源。必须在管理上下文同步关闭，未完成回调可能使关闭继续等待。见 [完整 demo 配置](../game-demo/src/main/java/cn/managame/demo/common/runtime/GameRuntimeConfig.java)。

契约：[容器集成规范](../docs/ogbs/OGBS-Spring-1.0.zh-CN.md)、[Java 开发规范](../docs/ogbs/OGBS-Spring-Java-25-Specification-1.0.zh-CN.md)、[OGBS 索引](../docs/ogbs/README.zh-CN.md)。公开包为 `cn.managame.spring.runtime` 和 `.data`，发现/配置/生命周期辅助类型保持包内封装。

构建使用 `mvn -pl game-spring -am clean verify`，测试覆盖 Cron 发现和平滑生命周期，Repository 集成测试位于 demo。真实数据库及任意自定义 Spring 代理/广播配置仍未验证。
