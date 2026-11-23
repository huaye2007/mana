[English](README.md) | 中文

# managame-ai

游戏服务器侧的 AI 边界组件（`cn.managame:managame-ai`），按三个正交平面解耦设计；记忆、Prompt、推理编排、模型路由、第三方 AI SDK、MCP 服务端以及离线/实时聚合分析均保留在外部独立服务进程中：

1. **出站事件流（`AiEventPublisher` + `AiEventSink`）**：为游戏事实事件附带 `eventId`、`partitionKey`、`occurredAt` 与 `Metadata`，按条数/字节上限/时间窗三触发攒批投递给 MQ 或分析/记忆服务，并支持有限次重试与失败回调。
2. **出站 AI 服务调用（`AiClient` + `AiTransport`）**：按目标 AI 服务隔离调用外部 AI 进程，支持 `call` / `callAsync`、同步/非阻塞流式 `stream` / `streamAsync`、按 `routingKey` 限流或会话互斥顶替、服务级熔断保护、响应 Schema 透传、空闲+总时长双超时以及适配 AI 耗时的延迟/TTFT 分桶（`50ms`–`30s`）。
3. **入站工具目录（`AiTools` + `@AiTool` + `@AiParam`）**：按受众（`audience`）隔离工具目录，生成严格的入参/出参 JSON Schema、可跨进程导出的纯值 `Descriptor` 与结构化 `AiToolResult`，显式分离可信调用者身份与 AI 不可信参数，并强制高危操作走审批入口（`requireApproval -> entry.propose`）。

完整装配示例见 [managame-example](../managame-example/README.zh-CN.md)，演示了在游戏服中将 AI 三平面与 `GameRuntime`、`RpcNode`、`GameData` 协同使用。

## 1. 出站事件流（`AiEventPublisher`）

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

- 每个事件类型绑定稳定的外部名称，提交的对象和集合应为不可变快照。
- 发布瞬间即打上 `eventId`（可接 `SnowflakeIdGenerator`）、`occurredAt`（可接 `GameTime`）、`partitionKey`（供 Kafka/RocketMQ 按角色/会话分区保序）与 `Metadata`。
- 按 `batchSize`（默认 `64`）、`maxBatchBytes`（默认 `1 MiB`）与 `linger` 时间窗（默认 `50ms`）触发刷出，运行在按需创建的虚拟线程（不池化）上。瞬时发送失败默认重试 2 次（可通过 `retryable` 谓词分类）；队列满、关闭或重试耗尽的事件会交给 `errorHandler`（`AiEventFailure`）。
- `close()` 会立即唤醒等待中的攒批线程并在 `shutdownTimeout`（默认 `10s`）内排空队列。
- 指标：`managame.ai.events.queued`、`managame.ai.events.sent`、`managame.ai.events.lost`、`managame.ai.events.batches`、`managame.ai.events.retried`。

## 2. 出站 AI 服务调用（`AiClient`）

```java
record Choice(@AiParam("选中的动作") String selected) {}
record ChatChunk(String text) {}

try (AiClient ai = AiClient.builder(transport)
        .service("decision", s -> s
                .limits(32, 128)
                .perKey(2, AiClient.KeyConflict.REJECT)
                .circuitBreaker(5, Duration.ofSeconds(10)))
        .service("chat", s -> s
                .limits(256, 512)
                .perKey(1, AiClient.KeyConflict.CANCEL_PREVIOUS)
                .circuitBreaker(5, Duration.ofSeconds(15)))
        .build()) {
    var move = ai.call(AiClient.CallOptions.service("decision").routingKey(Long.toString(roleId)).metadata(metadata),
            "chooseMove", request, Choice.class, Duration.ofMillis(250));
    var chat = ai.stream(AiClient.CallOptions.service("chat").routingKey(roleId + ":" + npcId).metadata(metadata),
            "npcChat", chatReq, ChatChunk.class, Duration.ofSeconds(5), Duration.ofSeconds(30),
            chunk -> push(chunk.text()));
}
```

- **按服务隔离与单键控制**：每个已配置的外部 AI 服务拥有独立的并发上限、有界队列与指标。可选的 `perKey(maxPerKey, conflict)` 支持 `KeyConflict.CANCEL_PREVIOUS`（适合 `roleId:npcId` 这类带外部记忆的 NPC 聊天会话，新消息自动取消旧流防记忆并发写乱）或 `KeyConflict.REJECT`（单玩家并发上限防刷）。
- **服务级熔断器**：`circuitBreaker(failureThreshold, openDuration)` 在连续传输/超时失败达阈值后打开，快速以 `AI_CIRCUIT_OPEN` 失败，等待窗口后放行单个探针恢复。
- **`AiRequest` 契约**：携带唯一 `requestId`（自动生成或通过 `client.nextRequestId()` 预分配）、`service`、`operation`、可选 `routingKey`（供外部有状态 AI 集群做黏性路由）、`metadata`、`payload` 及缓存的 `responseSchema`。
- **非阻塞与流式传输**：可覆写 `AiTransport.callAsync` 或 `AiTransport.streamAsync`（返回 `AutoCloseable` 取消句柄；在主动取消、超时、新流顶替或关服时立即关闭上游流止损 Token）。
- `stream(...)` 同时校验 `idleTimeout`（首字/中途卡顿）与 `totalTimeout`（总时长上限）。
- 失败或拒绝统一以携带 `FrameworkErrorCodes`（`AI_CLOSED`、`AI_OVERLOADED`、`AI_TIMEOUT`、`AI_FAILED`、`AI_PROTOCOL_ERROR`、`AI_CIRCUIT_OPEN`）的 `AiException` 结束。
- 指标（带 `service` 标签，使用 `50ms`–`30s` 分桶）：`managame.ai.client.queued`、`managame.ai.client.succeeded`、`managame.ai.client.refused`、`managame.ai.client.failed`、`managame.ai.client.circuit_open`、`managame.ai.client.latency`、`managame.ai.client.ttft`。

在游戏任务里先捕获 `runtime.continuation()` 并透传 `Context.current().metadata()`；若流式聊天已向玩家展示部分文本后被打断，可用相同的 `requestId` 经 `AiEventPublisher` 上报截断事件供外部记忆服务对齐。见 [GameplayAi](../managame-example/src/main/java/cn/managame/example/ai/GameplayAi.java)。

## 3. 入站工具目录（`AiTools`）

```java
record GetEquipment(@AiParam("目标角色 id") long roleId) {}
record EquipmentView(List<String> items) {}

@AiTool(name = "getEquipment", description = "读取当前角色可穿戴装备", audience = {"ops", "player"},
        output = EquipmentView.class, readOnly = true)
public HandleResult equipment(GetEquipment request) { return service.equipment(request); }

AiTools opsTools = AiTools.builder().audience("ops").register(handlers).build();
String wireCatalog = opsTools.encodeResult(opsTools.descriptors()); // 直接序列化上报给外部 AI/MCP 网关
```

- 启动时从处理器实例（`register`）或纯契约接口/类（`registerTypes`）扫描公开的非静态 `@AiTool` 方法，不持有也不直接调用处理器实例。
- `audience(name)` 按受众隔离目录；`catalog.descriptors()` 导出不含 `Class<?>` 的纯值描述列表，便于多游戏服动态向独立 AI/MCP 网关注册。
- `catalog.call(name, jsonOrMap, caller, entry)` 解码 AI 参数后将已鉴权的可信 `caller` 单独传给 `Entry<C>.dispatch`（`requireApproval = true` 时只进 `Entry<C>.propose`）；可使用 `AiToolResult`（`ok` / `reject` / `fail`）清晰区分业务条件拒绝与可重试系统故障。

## 编解码与 Schema（`AiJson`）

- `AiJson.jackson()`（默认）：严格反序列化，内置 ISO-8601 `java.time` 支持（`Instant`、`LocalDate`、`LocalDateTime`、`Duration`）；record 组件默认标记为 `required`，除非标注 `@AiParam(required = false)`。
- `AiJson.jacksonStringLongs()`：将 64 位 `long`/`Long` 序列化为 JSON 字符串（`format: int64`），避免雪花 ID 在 JS/LLM 端精度丢失，同时兼容字符串和精确整数解码。
- `AiJson.jackson(objectMapper)`：复用应用自定义 `ObjectMapper`。
- `ProtobufJson`：按需添加 `com.google.protobuf:protobuf-java-util:3.25.5`，向 builder 传入 `new ProtobufJson(List.of(MyRequest.getDefaultInstance()), fallback)`。
