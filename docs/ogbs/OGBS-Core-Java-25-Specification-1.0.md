# OGBS Core Java 25 Development Specification 1.0

**[English](OGBS-Core-Java-25-Specification-1.0.md)** | [简体中文](OGBS-Core-Java-25-Specification-1.0.zh-CN.md)

Document type: **Java Development Specification**. Standard: [OGBS Core Specification](OGBS-Core-1.0.md).

Status: current game-core Java 25 implementation contract. This document and Core's standard jointly constrain Java implementations. The standard remains the single normative source for Metadata format and shared error codes.

<a id="1-模块与职责"></a>

## 1. Module and responsibilities

Maven coordinates: cn.managame:game-core:1.0.0-SNAPSHOT. Requires JDK 25 without preview features. Public types live in cn.managame.core. There are no dependencies on Network, Runtime, Data, or RPC. game-core has no third-party dependencies ([Core POM](../../game-core/pom.xml)); components declare the libraries they use themselves (Runtime declares Caffeine for its idle mailbox cache). Core Metadata/error-code APIs create no caches, threads, or external resources.

Public types are Metadata (with its nested Builder), MetadataKey, MetadataCodec and FrameworkErrorCodes. Metadata and MetadataKey are final classes: every Metadata instance has passed structure validation, so consumers such as the RPC encoder need not re-check it.

<a id="2-metadata-api-与行为"></a>

## 2. Metadata API and behavior

```java
public final class Metadata {
    public static Metadata empty();
    public static Metadata wrap(byte[] encoded);
    public static Builder builder();
    public <T> T get(MetadataKey<T> key);
    public <T> T get(MetadataKey<T> key, T defaultValue);
    public boolean contains(MetadataKey<?> key);
    public boolean isEmpty();
    public byte[] bytes();

    public static final class Builder {
        public <T> Builder put(MetadataKey<T> key, T value);
        public Metadata build();
    }
}

public final class MetadataKey<T> {
    public static <T> MetadataKey<T> of(int id, MetadataCodec<T> codec);
    public static MetadataKey<Boolean> booleanKey(int id);
    public static MetadataKey<Long> longKey(int id);
    public static MetadataKey<Integer> intKey(int id);
    public static MetadataKey<String> stringKey(int id);
    public static MetadataKey<byte[]> bytesKey(int id);
    public int id();
}

public interface MetadataCodec<T> {
    byte[] encode(T value);
    T decode(byte[] bytes, int offset, int length);
}
```

Built-in keys and construction:

| Method | Behavior |
| --- | --- |
| MetadataKey.booleanKey(id) | 1 byte, false=0/true=1; other values or lengths throw IllegalArgumentException |
| MetadataKey.longKey(id) | 8-byte big-endian; decoding fails unless length is 8 |
| MetadataKey.intKey(id) | 4-byte big-endian; decoding fails unless length is 4 |
| MetadataKey.stringKey(id) | UTF-8 |
| MetadataKey.bytesKey(id) | Raw bytes |
| MetadataKey.of(id, codec) | Custom encoded type |
| Metadata.empty() | Empty Metadata |
| Metadata.wrap(encodedBytes) | Validate structure and wrap the original array without defensive copying |
| Metadata.builder() | Construct new Metadata |

MetadataKey.id() returns the numeric Key. Distinct MetadataKey instances with the same id query the same wire entry. Definers own encoding consistency; there is no global Key registry.

Missing get returns null; the defaultValue overload returns that default. Codec exceptions propagate directly. bytes() returns the shared backing array. bytesKey.get extracts its value into an independent Java byte[]; this does not mean every get copies all Metadata.

Builder forbids null values. Repeated put for an id replaces its previous Builder value, so build emits one entry for that Key. build assembles a new encoded array; subsequent Builder changes do not affect previously built Metadata.

```java
var traceId = MetadataKey.longKey(1024);
var locale = MetadataKey.stringKey(1025);
var debugEnabled = MetadataKey.booleanKey(1026);
var retryCount = MetadataKey.intKey(1027);
Metadata metadata = Metadata.builder()
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

Metadata.Builder is not thread-safe. Built Metadata may be read concurrently while underlying bytes remain unchanged. Custom MetadataCodec concurrency is its provider's responsibility. get calls codecs lazily and propagates exceptions without general retry or caching.

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

FrameworkErrorCodes publishes no Router constants: game-router reports RPC error codes, and the retired 5001..5004 values are not reused. See [Router Java specification](OGBS-Router-Java-25-Specification-1.0.md) for reporting paths.

Java FrameworkErrorCodes exposes public static final int constants for [Core's error allocation](OGBS-Core-1.0.md#4-frameworkerrorcode). Do not maintain a duplicate allocation table here. Defined values cannot be arbitrarily reordered; amend the standard before adding allocations, then update Java constants and consumers. `FrameworkErrorCodes.name(code)` returns the constant name (for example `RPC_TIMEOUT`) and `describe(code)` the name with the number (`RPC_TIMEOUT(2003)`); unallocated and business codes give `UNKNOWN(<code>)`. Both are for logs and diagnostics only; program logic compares the numbers.

The RPC implementation uses Core's 2001..2007 allocation, replacing the unimplemented draft. RpcErrorCodes references FrameworkErrorCodes only. errorCode has no high-bit wrapping: 0 success, 1..9999 framework, 10000..Integer.MAX_VALUE business; negative values are invalid. Old RPC constants have source/allocation changes and must not be mixed with the old draft. Runtime/Data allocations are unchanged.

Network uses WriteStatus, NetworkException, and ConnectionHandler.onException. Core's Network range is only reserved; it does not force Network to depend on game-core or assign a code for every closure.

Changes to Java signatures or exceptions should explain source compatibility. Changes to Metadata bytes or cross-process error meanings must first address protocol compatibility in the standard and Wire Profile.

<a id="5-源码与验证"></a>

## 5. Sources and validation

- [Metadata](../../game-core/src/main/java/cn/managame/core/Metadata.java): structure checks, lookup and Builder.
- [MetadataKey](../../game-core/src/main/java/cn/managame/core/MetadataKey.java): key validation and built-in codecs.
- [FrameworkErrorCodes](../../game-core/src/main/java/cn/managame/core/FrameworkErrorCodes.java): shared allocation binding.
- [MetadataTest](../../game-core/src/test/java/cn/managame/core/MetadataTest.java): shared arrays, lazy decoding, malformed structure, boundaries, Builder snapshots, encoding vectors.

Run mvn -pl game-core -am test. For dependency or cross-component integration changes, run mvn clean verify. Coverage does not establish cross-language interoperability or production performance validation.
