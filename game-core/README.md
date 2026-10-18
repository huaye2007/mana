# game-core

**[English](README.md)** | [简体中文](README.zh-CN.md)

Java 25 implementation of shared Metadata and framework error codes. Maven coordinates: cn.managame:game-core. It has no third-party dependencies ([Core Java specification](../docs/ogbs/OGBS-Core-Java-25-Specification-1.0.md#1-模块与职责)).

<a id="规范文档"></a>

## Specifications

| Specification (language-independent) | Java Development Specification |
| --- | --- |
| [OGBS Core Specification](../docs/ogbs/OGBS-Core-1.0.md) | [OGBS Core Java Development Specification](../docs/ogbs/OGBS-Core-Java-25-Specification-1.0.md) |

The standard defines shared encoding, ownership, and error-code meanings. The Java specification defines public APIs, builders, codecs, exceptions, and implementation boundaries. Do not maintain duplicate shared error-code allocations in consuming components.

<a id="构建与验证"></a>

## Observability

Package `cn.managame.core.metrics` is the one contract between the framework and whatever collects readings: `MetricSink` (what a collector implements), `MetricsSource` (what a component publishes) and `MetricsRegistry` (where they meet). Collection is pull, so nothing happens until something collects and no data path pays for it; Core defines gauges, counters and the dotted naming rule and no exposition format, which is why it still has no third-party dependency. Which readings exist belongs to each component; adapting them to a collector belongs to [game-boot](../game-boot/README.md). See [Core §5](../docs/ogbs/OGBS-Core-1.0.md#5-observability) and [Java §5](../docs/ogbs/OGBS-Core-Java-25-Specification-1.0.md#5-observability-binding).

## Build and validation

Run mvn -pl game-core -am test from the repository root. For cross-component or dependency changes, run mvn clean verify.

Test entry point: [MetadataTest](src/test/java/cn/managame/core/MetadataTest.java). Sources are in cn.managame.core, depend on no other framework components, and own no network threads or database resources.
