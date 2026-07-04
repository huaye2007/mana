[中文](README.md) | English

# game-serialization

`game-serialization` provides a small, high-throughput serialization facade for game services.
It supports JSON, Protobuf, and Apache Fory binary serialization.

## Formats

- JSON: Jackson `JsonMapper` with cached `ObjectReader`/`ObjectWriter`, Java time support, and Blackbird bytecode optimization.
- Protobuf: Google Protobuf `MessageLite` payloads with generated parser caching.
- Apache Fory: implemented with the Apache Fory (formerly Apache Fury) Java SDK, using Java-native serialization and a reused `ThreadSafeFory` runtime.

## Usage

Resolve the shared, pre-registered serializers from `SerializerManager` (the process-wide
singleton wired into the network/RPC stacks):

```java
SerializerManager serializers = SerializerManager.getInstance();

ISerializer json = serializers.getISerializer(SerializationType.JSON.typeId());
byte[] jsonBytes = json.serialize(playerState);
PlayerState fromJson = json.deserialize(jsonBytes, PlayerState.class);

ISerializer protobuf = serializers.getISerializer(SerializationType.PROTOBUF.typeId());
byte[] protoBytes = protobuf.serialize(loginRequest);          // value must be a protobuf MessageLite
LoginRequest fromProto = protobuf.deserialize(protoBytes, LoginRequest.class);
```

`getISerializer` returns `null` for an unknown `serialType` — callers must null-check.

### Apache Fory

A standalone Fory serializer is built through its builder. Class registration is **on by default**
(secure), so every type you serialize/deserialize must be registered first:

```java
ForySerializer fory = ForySerializer.builder()
    .register(PlayerState.class, 100)   // stable id → smaller payload
    .build();
byte[] foryBytes = fory.serialize(playerState);
PlayerState fromFory = fory.deserialize(foryBytes, PlayerState.class);
```

The default Fory obtained from `SerializerManager.getInstance()` is also registration-required.
Register your message types at startup before serving traffic:

```java
if (serializers.getISerializer(SerializationType.FORY.typeId()) instanceof ForySerializer fory) {
    fory.register(LoginReq.class);
    fory.register(LoginRes.class);
}
```

## Performance Notes

- Reuse serializer instances. Creating Jackson mappers or Fory runtimes per request will dominate latency.
- Prefer Protobuf for schema-first cross-service contracts and compact stable wire payloads.
- Prefer Fory for JVM-to-JVM game state snapshots, cache values, and internal messages where Java-native object graphs matter.
- Register frequently used Fory classes with stable IDs for smaller payloads and faster dispatch.

## Defaults & Security

- **Class registration is enabled by default** (`requireClassRegistration(true)`). Untrusted
  boundaries — e.g. the external game protocol whose body is Fory-encoded — can only deserialize
  registered types, which closes off the arbitrary-class deserialization attack surface. Register
  all message types at startup; unregistered types fail fast. Disable it only for fully trusted,
  internal-only traffic via `Builder.requireClassRegistration(false)`.
- **Reference tracking is enabled by default** (`refTracking(true)`) so shared and cyclic object
  graphs round-trip correctly. Turn it off only when payloads are known to be tree-shaped and you
  want the extra throughput.
