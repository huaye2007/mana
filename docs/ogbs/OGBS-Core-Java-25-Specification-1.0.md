# OGBS Core Java 25 Development Specification 1.0

**[English](OGBS-Core-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Core-Java-25-Specification-1.0.zh-CN.md)

Document type: **Java Development Specification**. Standard: [OGBS Core Specification](OGBS-Core-1.0.md).

Status: current game-core Java 25 implementation contract. This document and Core's standard jointly constrain Java implementations. The standard remains the single normative source for Metadata format and shared error codes.

<a id="1-模块与职责"></a>

## 1. Module and responsibilities

Maven coordinates: cn.managame:game-core:1.0.0-SNAPSHOT. Requires JDK 25 without preview features. Public types live in cn.managame.core. There are no dependencies on Network, Runtime, Data, or RPC. game-core publishes the normal compile dependency `com.github.ben-manes.caffeine:caffeine:3.2.3` for shared use by dependent modules; Runtime and Data receive it transitively without separately declaring its version. See [Core POM](../../game-core/pom.xml). Core Metadata/error-code APIs create no caches, threads, or external resources. Cache users configure and own their component-specific lifecycle; the Java dependency does not add a new Core cache facade or change shared Metadata/error semantics.

Public types are Metadata, MetadataKey, MetadataCodec, MetadataBuilder, MetadataKeys, Metadatas, and FrameworkErrorCodes. Metadatas' encoded container and Builder implementation remain internal; language-layer separation does not create another Maven artifact.

<a id="2-metadata-api-与行为"></a>

## 2. Metadata API and behavior

```java
public interface Metadata {
    <T> T get(MetadataKey<T> key);
    default <T> T get(MetadataKey<T> key, T defaultValue);
    boolean contains(MetadataKey<?> key);
    boolean isEmpty();
    byte[] bytes();
}

public interface MetadataCodec<T> {
    byte[] encode(T value);
    T decode(byte[] bytes, int offset, int length);
}

public interface MetadataBuilder {
    <T> MetadataBuilder put(MetadataKey<T> key, T value);
    Metadata build();
}
```

This is a signature summary; default method bodies are omitted. Construction utilities:

| Method | Behavior |
| --- | --- |
| MetadataKeys.booleanKey(id) | 1 byte, false=0/true=1; other values or lengths throw IllegalArgumentException |
| MetadataKeys.longKey(id) | 8-byte big-endian; decoding fails unless length is 8 |
| MetadataKeys.intKey(id) | 4-byte big-endian; decoding fails unless length is 4 |
| MetadataKeys.stringKey(id) | UTF-8 |
| MetadataKeys.bytesKey(id) | Raw bytes |
| MetadataKeys.of(id, codec) | Custom encoded type |
| Metadatas.empty() | Empty Metadata |
| Metadatas.wrap(encodedBytes) | Validate structure and wrap the original array without defensive copying |
| Metadatas.builder() | Construct new Metadata |

MetadataKey.id() returns the numeric Key. Distinct MetadataKey instances with the same id query the same wire entry. Definers own encoding consistency; there is no global Key registry.

Missing get returns null; the defaultValue overload returns that default. Codec exceptions propagate directly. bytes() returns the shared backing array. bytesKey.get extracts its value into an independent Java byte[]; this does not mean every get copies all Metadata.

Builder forbids null values. Repeated put for an id replaces its previous Builder value, so build emits one entry for that Key. build assembles a new encoded array; subsequent Builder changes do not affect previously built Metadata.

```java
var traceId = MetadataKeys.longKey(1024);
var locale = MetadataKeys.stringKey(1025);
var debugEnabled = MetadataKeys.booleanKey(1026);
var retryCount = MetadataKeys.intKey(1027);
Metadata metadata = Metadatas.builder()
    .put(traceId, 123456L)
    .put(locale, "zh-CN")
    .put(debugEnabled, true)
    .put(retryCount, 3)
    .build();
long trace = metadata.get(traceId, 0L);
boolean debug = metadata.get(debugEnabled, false);
int retries = metadata.get(retryCount, 0);
```

These IDs are application choices, not predefined framework fields. The default implementation scans linearly on every get and decodes on demand, with no decoded-value cache or zero-allocation guarantee.

<a id="3-所有权并发与失败"></a>

## 3. Ownership, concurrency, and failure

Java byte[] is not immutable at the language level. wrap and bytes() share arrays, so callers must follow the standard's read-only convention. This implementation neither freezes arrays nor isolates them by copying. Builder stores codec-returned arrays at put and assembles a new array at build; callers/codecs must not concurrently modify encoded values before build.

MetadataBuilder is not thread-safe. Built Metadata may be read concurrently while underlying bytes remain unchanged. Custom MetadataCodec concurrency is its provider's responsibility. get calls codecs lazily and propagates exceptions without general retry or caching.

| Condition | Java behavior |
| --- | --- |
| MetadataKey ID outside 1..65535 | IllegalArgumentException |
| null codec, encoded array, key, or put value | NullPointerException |
| Malformed wrap structure, duplicate/zero ID, invalid length | IllegalArgumentException |
| Builder value/container too long | IllegalArgumentException |
| Invalid built-in boolean/int32/int64 format or length on read | IllegalArgumentException |
| Missing Key | get returns null; default overload returns its default |
| Custom codec failure | Original exception propagates |

stringKey uses JDK StandardCharsets.UTF_8 for String encoding/decoding; bytesKey returns an independent byte[] for the entry. Linear scanning promises neither zero allocation nor decoded-value caching. Optimizations must preserve numeric Key identity, lazy decoding, and shared-array contracts.

<a id="4-共享错误码绑定与兼容性"></a>

## 4. Shared error binding and compatibility

Java FrameworkErrorCodes exposes public static final int constants for [Core's error allocation](OGBS-Core-1.0.md#4-frameworkerrorcode). Do not maintain a duplicate allocation table here. Defined values cannot be arbitrarily reordered; amend the standard before adding allocations, then update Java constants and consumers.

The RPC implementation uses Core's 2001..2007 allocation, replacing the unimplemented draft. RpcErrorCodes references FrameworkErrorCodes only. errorCode has no high-bit wrapping: 0 success, 1..9999 framework, 10000..Integer.MAX_VALUE business; negative values are invalid. Old RPC constants have source/allocation changes and must not be mixed with the old draft. Runtime/Data allocations are unchanged.

Network uses WriteStatus, NetworkException, and ConnectionHandler.onException. Core's Network range is only reserved; it does not force Network to depend on game-core or assign a code for every closure.

Changes to Java signatures or exceptions should explain source compatibility. Changes to Metadata bytes or cross-process error meanings must first address protocol compatibility in the standard and Wire Profile.

<a id="5-源码与验证"></a>

## 5. Sources and validation

- [Metadata](../../game-core/src/main/java/cn/managame/core/Metadata.java), [MetadataKey](../../game-core/src/main/java/cn/managame/core/MetadataKey.java): public access interfaces.
- [Metadatas](../../game-core/src/main/java/cn/managame/core/Metadatas.java), [MetadataKeys](../../game-core/src/main/java/cn/managame/core/MetadataKeys.java): structure checks, Builder, built-in codecs.
- [FrameworkErrorCodes](../../game-core/src/main/java/cn/managame/core/FrameworkErrorCodes.java): shared allocation binding.
- [MetadataTest](../../game-core/src/test/java/cn/managame/core/MetadataTest.java): shared arrays, lazy decoding, malformed structure, boundaries, Builder snapshots, encoding vectors.

Run mvn -pl game-core -am test. For dependency or cross-component integration changes, run mvn clean verify. Coverage does not establish cross-language interoperability or production performance validation.
