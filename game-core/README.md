# game-core

共享 Metadata 和框架错误码的 Java 25 实现，Maven 坐标为 cn.managame:game-core。

## 规范文档

| 标准规范（语言无关） | Java 开发规范 |
| --- | --- |
| [OGBS Core Specification](../docs/ogbs/OGBS-Core-1.0.md) | [OGBS Core Java Development Specification](../docs/ogbs/OGBS-Core-Java-25-Specification-1.0.md) |

标准定义共享编码、所有权和错误码含义；Java 开发规范定义公开 API、Builder、codec、异常和实现边界。不要在消费组件中复制维护共享编号。

## 构建与验证

在仓库根目录运行 mvn -pl game-core -am test；跨组件或依赖调整运行 mvn clean verify。

测试入口：[MetadataTest](src/test/java/cn/managame/core/MetadataTest.java)。源码位于 cn.managame.core，不依赖其他框架组件，不持有网络线程或数据库资源。