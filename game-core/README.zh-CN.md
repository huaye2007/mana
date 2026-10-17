# game-core

[English](README.md) | **[简体中文](README.zh-CN.md)**

共享 Metadata 和框架错误码的 Java 25 实现，Maven 坐标为 cn.managame:game-core。没有第三方依赖（见 [Core Java 规范](../docs/ogbs/OGBS-Core-Java-25-Specification-1.0.zh-CN.md#1-模块与职责)）。

## 规范文档

| 标准规范（语言无关） | Java 开发规范 |
| --- | --- |
| [OGBS Core Specification](../docs/ogbs/OGBS-Core-1.0.zh-CN.md) | [OGBS Core Java Development Specification](../docs/ogbs/OGBS-Core-Java-25-Specification-1.0.zh-CN.md) |

标准定义共享编码、所有权和错误码含义；Java 开发规范定义公开 API、Builder、codec、异常和实现边界。不要在消费组件中复制维护共享编号。

## 可观测性

包 `cn.managame.core.metrics` 是框架与"收集方"之间唯一的契约：`MetricSink`（收集方实现的东西）、`MetricsSource`（组件发布的东西）与 `MetricsRegistry`（二者相遇的地方）。收集方式是拉取，因此在有人收集之前什么都不发生，也没有任何数据路径为它付代价；Core 只定义 gauge、counter 与点分命名规则，不定义任何导出格式——这正是它依旧没有第三方依赖的原因。有哪些读数属于各组件；把它们适配到收集方属于 [game-spring](../game-spring/README.zh-CN.md)。见 [Core §5](../docs/ogbs/OGBS-Core-1.0.zh-CN.md#5-可观测性) 与 [Java §5](../docs/ogbs/OGBS-Core-Java-25-Specification-1.0.zh-CN.md#5-可观测性绑定)。

## 构建与验证

在仓库根目录运行 mvn -pl game-core -am test；跨组件或依赖调整运行 mvn clean verify。

测试入口：[MetadataTest](src/test/java/cn/managame/core/MetadataTest.java)。源码位于 cn.managame.core，不依赖其他框架组件，不持有网络线程或数据库资源。
