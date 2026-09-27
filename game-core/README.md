# game-core

**[English](README.md)** | [简体中文](README.zh-CN.md)

Java 25 implementation of shared Metadata and framework error codes. Maven coordinates: cn.managame:game-core.

<a id="规范文档"></a>

## Specifications

| Specification (language-independent) | Java Development Specification |
| --- | --- |
| [OGBS Core Specification](../docs/ogbs/OGBS-Core-1.0.md) | [OGBS Core Java Development Specification](../docs/ogbs/OGBS-Core-Java-25-Specification-1.0.md) |

The standard defines shared encoding, ownership, and error-code meanings. The Java specification defines public APIs, builders, codecs, exceptions, and implementation boundaries. Do not maintain duplicate shared error-code allocations in consuming components.

<a id="构建与验证"></a>

## Build and validation

Run mvn -pl game-core -am test from the repository root. For cross-component or dependency changes, run mvn clean verify.

Test entry point: [MetadataTest](src/test/java/cn/managame/core/MetadataTest.java). Sources are in cn.managame.core, depend on no other framework components, and own no network threads or database resources.
