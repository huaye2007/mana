English | [中文](README.zh-CN.md)

# managame-ai

Game-server-side AI boundary components (`cn.managame:managame-ai`), split into three orthogonal planes while keeping memory, prompts, reasoning orchestration, model routing, third-party AI SDKs, MCP servers and analytics aggregation in standalone external service processes:

1. **Outbound event stream (`AiEventPublisher` + `AiEventSink`)**: publish structured game facts with `eventId`, `partitionKey`, `occurredAt` and `Metadata` in size/linger batches to MQ or analytics/memory pipelines.
2. **Outbound AI service calls (`AiClient` + `AiTransport`)**: call external AI service processes over isolated named bulkhead lanes (`call` with cached `responseSchema`, non-blocking `callAsync`, and `stream` with independent idle and total timeouts).
3. **Inbound tool contracts (`AiTools` + `@AiTool` + `@AiParam`)**: expose audience-filtered tool catalogs with strict input/output JSON Schemas, separate authenticated caller context, and mandatory approval routing (`requireApproval -> entry.propose`).

See [managame-example](../managame-example/README.md) for a Spring-assembled game server wiring all three planes alongside `GameRuntime`, `RpcNode` and `GameData`.

## 1. Outbound event stream (`AiEventPublisher`)

```java
record Purchase(String orderId, long roleId, String item) {}

try (AiEventPublisher events = AiEventPublisher.builder(batch -> kafka.send(batch))
        .events(List.of(new AiEventPublisher.EventType("purchase.completed", Purchase.class)))
        .limits(1, 4096, 64, Duration.ofMillis(50))
        .build()) {
    events.publish(Long.toString(roleId), metadata, new Purchase("P7", roleId, "boots"));
}
```

- Register a stable external name per event class and pass immutable payloads.
- Each published event is stamped immediately with `eventId` (customizable via `idGenerator(...)`), `occurredAt` (customizable via `clock(...)`), `partitionKey` (for per-entity ordering in Kafka/RocketMQ) and `Metadata`.
- Batches flush on either `batchSize` (default `64`) or `linger` (default `50ms`) using on-demand virtual threads (never pooled). `close()` wakes lingering workers immediately and drains up to `shutdownTimeout` (default `10s`).
- Metrics: `managame.ai.events.queued`, `managame.ai.events.sent`, `managame.ai.events.lost`, `managame.ai.events.batches`.

## 2. Outbound AI service invocation (`AiClient`)

```java
record Choice(@AiParam("Selected action") String selected) {}
record ChatChunk(String text) {}

try (AiClient ai = AiClient.builder(transport)
        .lane("decision", 32, 128)
        .lane("chat", 256, 512)
        .build()) {
    var move = ai.call("decision", "chooseMove", metadata, request, Choice.class, Duration.ofMillis(250));
    var chat = ai.stream("chat", "npcChat", metadata, chatReq, ChatChunk.class,
            Duration.ofSeconds(5), Duration.ofSeconds(30), chunk -> push(chunk.text()));
}
```

- Named lanes isolate concurrency limits, bounded queues and metrics so long streaming or reasoning calls never starve low-latency decision calls. Simple applications may use the built-in `"default"` lane.
- Every `AiRequest` carries `lane`, `operation`, `metadata`, `payload` and a cached `responseSchema` (derived from the response/chunk class) so external AI services can enforce structured outputs directly.
- Non-blocking transports (such as RPC or async HTTP) may override `AiTransport.callAsync` to return a `CompletionStage<String>` without blocking a worker thread.
- `stream(...)` enforces both `idleTimeout` (stalled first token or mid-stream stall) and `totalTimeout`, and `future.cancel(true)` interrupts an in-flight stream immediately.
- Failures complete with `AiException` carrying `FrameworkErrorCodes` (`AI_CLOSED`, `AI_OVERLOADED`, `AI_TIMEOUT`, `AI_FAILED`, `AI_PROTOCOL_ERROR`).
- On shutdown stop producers, close `AiEventPublisher` and `AiClient` (bounded by `shutdownTimeout`, default `10s`), then close `GameRuntime`.
- Metrics (tagged by `lane`): `managame.ai.client.queued`, `managame.ai.client.succeeded`, `managame.ai.client.refused`, `managame.ai.client.failed`, `managame.ai.client.latency` (`LatencyBuckets`).

In a game task capture `runtime.continuation()` before calling `AiClient`, pass `Context.current().metadata()`, and validate candidate/state revisions on the original route. For high-frequency text streams push chunks directly and return to the route on completion. See [GameplayAi](../managame-example/src/main/java/cn/managame/example/ai/GameplayAi.java).

## 3. Inbound tool catalog (`AiTools`)

```java
record GetEquipment(@AiParam("Target player role id") long roleId) {}
record EquipmentView(List<String> items) {}

@AiTool(name = "getEquipment", description = "Read wearable equipment", audience = {"ops", "player"},
        output = EquipmentView.class, readOnly = true)
public HandleResult equipment(GetEquipment request) { return service.equipment(request); }

AiTools opsTools = AiTools.builder().audience("ops").register(handlers).build();
// Or from contract interfaces in a standalone MCP/Agent gateway:
AiTools gatewayTools = AiTools.builder().audience("ops").registerTypes(List.of(OpsContract.class)).build();
```

- Registration scans public non-static `@AiTool` methods from handler instances (`register`) or contract interfaces/classes (`registerTypes`) without invoking or retaining any handler instance.
- `audience(name)` restricts a catalog to tools declaring that audience so GM/ops tools never leak to player-side catalogs.
- `catalog.call(name, jsonOrMap, caller, entry)` decodes untrusted arguments and passes the authenticated `caller` separately to `Entry<C>.dispatch` (or strictly `Entry<C>.propose` when `requireApproval = true`). Use `catalog.encodeResult(value)` to encode responses with the same `AiJson` codec.

## Codecs and Schemas (`AiJson`)

- `AiJson.jackson()` (default): strict decoding (no scalar coercion, no float-to-int, no null for primitives, no trailing tokens) with ISO-8601 `java.time` support (`Instant`, `LocalDate`, `LocalDateTime`, `Duration`). Record components are `required` unless marked `@AiParam(required = false)`.
- `AiJson.jacksonStringLongs()`: serializes `long`/`Long` as JSON strings (`format: int64`) to protect 64-bit Snowflake IDs in JS/LLM clients while decoding from decimal strings or exact integers.
- `AiJson.jackson(objectMapper)`: wraps a custom `ObjectMapper`.
- `ProtobufJson`: add optional `com.google.protobuf:protobuf-java-util:3.25.5` and pass `new ProtobufJson(List.of(MyRequest.getDefaultInstance()), fallback)` to builders.
