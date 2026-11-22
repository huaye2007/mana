English | [中文](README.zh-CN.md)

# managame-ai

Game-server-side AI boundary components (`cn.managame:managame-ai`), split into three orthogonal planes while keeping memory, prompts, reasoning orchestration, model routing, third-party AI SDKs, MCP servers and analytics aggregation in standalone external service processes:

1. **Outbound event stream (`AiEventPublisher` + `AiEventSink`)**: publish structured game facts with `eventId`, `partitionKey`, `occurredAt` and `Metadata` in size/byte/linger batches with bounded retries and fallback error handling.
2. **Outbound AI service calls (`AiClient` + `AiTransport`)**: call external AI service processes over isolated named bulkhead lanes (`call` / `callAsync`, `stream` / `streamAsync`), with per-`routingKey` concurrency/session control, lane circuit breaking, cached `responseSchema`, idle+total stream timeouts, and AI-scaled latency/TTFT buckets (`50ms`–`30s`).
3. **Inbound tool contracts (`AiTools` + `@AiTool` + `@AiParam`)**: expose audience-filtered tool catalogs with strict input/output JSON Schemas, wire-serializable `Descriptor`s, `AiToolResult`, separate authenticated caller context, and mandatory approval routing (`requireApproval -> entry.propose`).

See [managame-example](../managame-example/README.md) for a Spring-assembled game server wiring all three planes alongside `GameRuntime`, `RpcNode` and `GameData`.

## 1. Outbound event stream (`AiEventPublisher`)

```java
record Purchase(String orderId, long roleId, String item) {}

try (AiEventPublisher events = AiEventPublisher.builder(batch -> kafka.send(batch))
        .events(List.of(new AiEventPublisher.EventType("purchase.completed", Purchase.class)))
        .limits(1, 4096, 64, Duration.ofMillis(50))
        .maxBatchBytes(512 * 1024)
        .retry(2, Duration.ofMillis(100))
        .errorHandler(failure -> spool.write(failure))
        .build()) {
    events.publish(Long.toString(roleId), metadata, new Purchase("P7", roleId, "boots"));
}
```

- Register a stable external name per event class and pass immutable payloads.
- Each published event is stamped immediately with `eventId` (customizable via `idGenerator(...)`), `occurredAt` (customizable via `clock(...)`), `partitionKey` (for per-entity ordering in Kafka/RocketMQ) and `Metadata`.
- Batches flush on `batchSize` (default `64`), `maxBatchBytes` (default `1 MiB`), or `linger` (default `50ms`) using on-demand virtual threads (never pooled). Transient sink failures retry up to `maxRetries` (default `2`, configurable with a `retryable` predicate); any dropped or exhausted event is handed to `errorHandler` (`AiEventFailure`).
- `close()` wakes lingering workers immediately and drains up to `shutdownTimeout` (default `10s`).
- Metrics: `managame.ai.events.queued`, `managame.ai.events.sent`, `managame.ai.events.lost`, `managame.ai.events.batches`, `managame.ai.events.retried`.

## 2. Outbound AI service invocation (`AiClient`)

```java
record Choice(@AiParam("Selected action") String selected) {}
record ChatChunk(String text) {}

try (AiClient ai = AiClient.builder(transport)
        .lane("decision", 32, 128, 2, AiClient.KeyConflict.REJECT)
        .circuitBreaker("decision", 5, Duration.ofSeconds(10))
        .lane("chat", 256, 512, 1, AiClient.KeyConflict.CANCEL_PREVIOUS)
        .circuitBreaker("chat", 5, Duration.ofSeconds(15))
        .build()) {
    var move = ai.call(AiClient.CallOptions.of("decision").routingKey(Long.toString(roleId)).metadata(metadata),
            "chooseMove", request, Choice.class, Duration.ofMillis(250));
    var chat = ai.stream(AiClient.CallOptions.of("chat").routingKey(roleId + ":" + npcId).metadata(metadata),
            "npcChat", chatReq, ChatChunk.class, Duration.ofSeconds(5), Duration.ofSeconds(30),
            chunk -> push(chunk.text()));
}
```

- **Named lanes & per-key control**: each lane owns independent concurrency limits, bounded queue and metrics. Optional per-`routingKey` limits support `KeyConflict.CANCEL_PREVIOUS` (for stateful per-session streams like `(roleId, npcId)` NPC chat so a new message cleanly aborts the previous turn) and `KeyConflict.REJECT` (per-player fairness/anti-spam).
- **Lane circuit breaker**: `circuitBreaker(lane, failureThreshold, openDuration)` trips after consecutive transport/timeout failures, fast-fails with `AI_CIRCUIT_OPEN`, and recovers via a single half-open probe.
- **`AiRequest` contract**: carries `requestId` (auto-generated or pre-allocated via `client.nextRequestId()`), `lane`, `operation`, optional `routingKey` (for sticky routing to stateful external AI nodes), `metadata`, `payload`, and cached `responseSchema`.
- **Non-blocking & streaming transports**: override `AiTransport.callAsync` for async calls or `AiTransport.streamAsync` (returning an `AutoCloseable` cancel handle invoked immediately on cancel, timeout, replacement or close so upstream token generation stops promptly).
- `stream(...)` enforces both `idleTimeout` (first-token or mid-stream stall) and `totalTimeout`.
- Failures complete with `AiException` carrying `FrameworkErrorCodes` (`AI_CLOSED`, `AI_OVERLOADED`, `AI_TIMEOUT`, `AI_FAILED`, `AI_PROTOCOL_ERROR`, `AI_CIRCUIT_OPEN`).
- Metrics (tagged by `lane`, using `50ms`–`30s` buckets): `managame.ai.client.queued`, `managame.ai.client.succeeded`, `managame.ai.client.refused`, `managame.ai.client.failed`, `managame.ai.client.circuit_open`, `managame.ai.client.latency`, `managame.ai.client.ttft`.

In a game task capture `runtime.continuation()` before calling `AiClient`, pass `Context.current().metadata()`, and validate candidate/state revisions on the original route. For interrupted streams that already pushed partial text to the client, publish a truncation event carrying `requestId` through `AiEventPublisher` so external memory stays aligned. See [GameplayAi](../managame-example/src/main/java/cn/managame/example/ai/GameplayAi.java).

## 3. Inbound tool catalog (`AiTools`)

```java
record GetEquipment(@AiParam("Target player role id") long roleId) {}
record EquipmentView(List<String> items) {}

@AiTool(name = "getEquipment", description = "Read wearable equipment", audience = {"ops", "player"},
        output = EquipmentView.class, readOnly = true)
public HandleResult equipment(GetEquipment request) { return service.equipment(request); }

AiTools opsTools = AiTools.builder().audience("ops").register(handlers).build();
String wireCatalog = opsTools.encodeResult(opsTools.descriptors()); // export to remote AI/MCP gateway
```

- Registration scans public non-static `@AiTool` methods from handler instances (`register`) or contract interfaces/classes (`registerTypes`) without invoking or retaining any handler instance.
- `audience(name)` isolates catalogs so GM/ops tools never leak to player-side catalogs.
- `catalog.descriptors()` exports pure wire-serializable `Descriptor`s (no `Class<?>` fields) for multi-server registration with standalone AI/MCP gateways.
- `catalog.call(name, jsonOrMap, caller, entry)` decodes untrusted arguments and passes the authenticated `caller` separately to `Entry<C>.dispatch` (or strictly `Entry<C>.propose` when `requireApproval = true`). Use `AiToolResult` (`ok` / `reject` / `fail`) to distinguish business rejections from retryable system errors.

## Codecs and Schemas (`AiJson`)

- `AiJson.jackson()` (default): strict decoding with ISO-8601 `java.time` support (`Instant`, `LocalDate`, `LocalDateTime`, `Duration`). Record components are `required` unless marked `@AiParam(required = false)`.
- `AiJson.jacksonStringLongs()`: serializes `long`/`Long` as JSON strings (`format: int64`) to protect 64-bit Snowflake IDs in JS/LLM clients while decoding from decimal strings or exact integers.
- `AiJson.jackson(objectMapper)`: wraps a custom `ObjectMapper`.
- `ProtobufJson`: add optional `com.google.protobuf:protobuf-java-util:3.25.5` and pass `new ProtobufJson(List.of(MyRequest.getDefaultInstance()), fallback)` to builders.
