[中文](README.md) | English

# game-serialization

`game-serialization` provides a small, high-throughput serialization facade for game services.
It supports JSON, Protobuf, and Fury-compatible binary serialization.

## Formats

- JSON: Jackson `JsonMapper` with cached `ObjectReader`/`ObjectWriter`, Java time support, and Blackbird bytecode optimization.
- Protobuf: Google Protobuf `MessageLite` payloads with generated parser caching.
- Fury: implemented with the current Apache Fory Java SDK, using Java-native serialization and a reused `ThreadSafeFory` runtime.

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

### Fury

A standalone Fury serializer is built through its builder. Class registration is **on by default**
(secure), so every type you serialize/deserialize must be registered first:

```java
FurySerializer fury = FurySerializer.builder()
    .register(PlayerState.class, 100)   // stable id → smaller payload
    .build();
byte[] furyBytes = fury.serialize(playerState);
PlayerState fromFury = fury.deserialize(furyBytes, PlayerState.class);
```

The default Fury obtained from `SerializerManager.getInstance()` is also registration-required.
Register your message types at startup before serving traffic:

```java
if (serializers.getISerializer(SerializationType.FURY.typeId()) instanceof FurySerializer fury) {
    fury.register(LoginReq.class);
    fury.register(LoginRes.class);
}
```

## Performance Notes

- Reuse serializer instances. Creating Jackson mappers or Fory runtimes per request will dominate latency.
- Prefer Protobuf for schema-first cross-service contracts and compact stable wire payloads.
- Prefer Fury for JVM-to-JVM game state snapshots, cache values, and internal messages where Java-native object graphs matter.
- Register frequently used Fury classes with stable IDs for smaller payloads and faster dispatch.

## Defaults & Security

- **Class registration is enabled by default** (`requireClassRegistration(true)`). Untrusted
  boundaries — e.g. the external game protocol whose body is Fury-encoded — can only deserialize
  registered types, which closes off the arbitrary-class deserialization attack surface. Register
  all message types at startup; unregistered types fail fast. Disable it only for fully trusted,
  internal-only traffic via `Builder.requireClassRegistration(false)`.
- **Reference tracking is enabled by default** (`refTracking(true)`) so shared and cyclic object
  graphs round-trip correctly. Turn it off only when payloads are known to be tree-shaped and you
  want the extra throughput.
