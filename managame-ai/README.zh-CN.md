[English](README.md) | 中文

# managame-ai

游戏服务器侧的 AI 边界组件（`cn.managame:managame-ai`），按三个正交平面解耦设计；记忆、Prompt、推理编排、模型路由、第三方 AI SDK、MCP 服务端以及离线/实时聚合分析均保留在外部独立服务进程中：

1. **出站事件流（`AiEventPublisher` + `AiEventSink`）**：为游戏事实事件附带 `eventId`、`partitionKey`、`occurredAt` 与 `Metadata`，按批大小/时间窗攒批投递给 MQ 或分析/记忆服务。
2. **出站 AI 服务调用（`AiClient` + `AiTransport`）**：按命名隔离通道（Lane）调用外部 AI 服务进程，支持带 `responseSchema` 的类型化请求（同步 `call` / 非阻塞 `callAsync`）以及带空闲超时与总超时双控的流式调用（`stream`）。
3. **入站工具目录（`AiTools` + `@AiTool` + `@AiParam`）**：按受众（`audience`）隔离工具目录，生成严格的入参/出参 JSON Schema，显式分离可信调用者身份与 AI 不可信参数，并强制高危操作走审批入口（`requireApproval -> entry.propose`）。

完整装配示例见 [managame-example](../managame-example/README.zh-CN.md)，演示了在游戏服中将 AI 三平面与 `GameRuntime`、`RpcNode`、`GameData` 协同使用。

## 1. 出站事件流（`AiEventPublisher`）

```java
record Purchase(String orderId, long roleId, String item) {}

try (AiEventPublisher events = AiEventPublisher.builder(batch -> kafka.send(batch))
        .events(List.of(new AiEventPublisher.EventType("purchase.completed", Purchase.class)))
        .limits(1, 4096, 64, Duration.ofMillis(50))
        .build()) {
    events.publish(Long.toString(roleId), metadata, new Purchase("P7", roleId, "boots"));
}
```

- 每个事件类型绑定稳定的外部名称，提交的对象和集合应为不可变快照。
- 发布瞬间即打上 `eventId`（可通过 `idGenerator(...)` 接入雪花 ID）、`occurredAt`（可通过 `clock(...)` 接入 `GameTime`）、`partitionKey`（供 Kafka/RocketMQ 按角色/公会分区保序）与 `Metadata`。
- 在按需创建的虚拟线程（不池化）上按 `batchSize`（默认 `64`）或 `linger` 时间窗（默认 `50ms`）双触发攒批刷出；`close()` 会立即唤醒等待中的攒批线程并在 `shutdownTimeout`（默认 `10s`）内落完队列。
- 指标：`managame.ai.events.queued`、`managame.ai.events.sent`、`managame.ai.events.lost`、`managame.ai.events.batches`。

## 2. 出站 AI 服务调用（`AiClient`）

```java
record Choice(@AiParam("选中的动作") String selected) {}
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

- 每个命名通道（`lane`）拥有独立的并发上限、有界队列与指标，防止长耗时推理/流式对话占满槽位拖死低延迟决策；简单场景可直接使用内置 `"default"` 通道。
- `AiRequest` 携带 `lane`、`operation`、`metadata`、`payload` 及缓存好的 `responseSchema`，方便外部 AI 服务直接把结构化输出约束透传给决策或推理大模型。
- 非阻塞传输（如 RPC 或异步 HTTP）可覆写 `AiTransport.callAsync` 返回 `CompletionStage<String>` 避免占用阻塞工作线程。
- `stream(...)` 同时校验 `idleTimeout`（首字/中途卡顿超时）与 `totalTimeout`（总时长上限），调用 `future.cancel(true)` 可立即打断在途流。
- 失败或拒绝统一完成为携带 `FrameworkErrorCodes`（`AI_CLOSED`、`AI_OVERLOADED`、`AI_TIMEOUT`、`AI_FAILED`、`AI_PROTOCOL_ERROR`）的 `AiException`。
- 停服顺序：先停止业务提交，关闭 `AiEventPublisher` 和 `AiClient`（受 `shutdownTimeout` 保护，默认 `10s`），再关闭 `GameRuntime`。
- 指标（带 `lane` 标签）：`managame.ai.client.queued`、`managame.ai.client.succeeded`、`managame.ai.client.refused`、`managame.ai.client.failed`、`managame.ai.client.latency`（`LatencyBuckets`）。

在游戏任务里先捕获 `runtime.continuation()` 并透传 `Context.current().metadata()`，返回后回原路由校验状态版本与合法候选集；高频流式文本可直接推送连接，仅在流结束时回路由。见 [GameplayAi](../managame-example/src/main/java/cn/managame/example/ai/GameplayAi.java)。

## 3. 入站工具目录（`AiTools`）

```java
record GetEquipment(@AiParam("目标角色 id") long roleId) {}
record EquipmentView(List<String> items) {}

@AiTool(name = "getEquipment", description = "读取当前角色可穿戴装备", audience = {"ops", "player"},
        output = EquipmentView.class, readOnly = true)
public HandleResult equipment(GetEquipment request) { return service.equipment(request); }

AiTools opsTools = AiTools.builder().audience("ops").register(handlers).build();
// 或在独立 MCP/Agent 网关里直接从共享契约接口构建目录，再经 RPC 转发给目标游戏服：
AiTools gatewayTools = AiTools.builder().audience("ops").registerTypes(List.of(OpsContract.class)).build();
```

- 启动时从处理器实例（`register`）或纯契约接口/类（`registerTypes`）扫描公开的非静态 `@AiTool` 方法，不持有也不直接调用处理器实例。
- `audience(name)` 按受众过滤目录，确保运维/GM 工具不会泄露到玩家侧目录。
- `catalog.call(name, jsonOrMap, caller, entry)` 解码 AI 参数后将已鉴权的可信 `caller` 单独传给 `Entry<C>.dispatch`（`requireApproval = true` 时只进 `Entry<C>.propose`）；返回结果使用 `catalog.encodeResult(value)` 保持与入参编解码器一致。

## 编解码与 Schema（`AiJson`）

- `AiJson.jackson()`（默认）：严格反序列化（禁用标量强转、浮点转整型、原始类型 null、尾随 token），内置 ISO-8601 `java.time` 支持（`Instant`、`LocalDate`、`LocalDateTime`、`Duration`）；record 组件默认标记为 `required`，除非标注 `@AiParam(required = false)`。
- `AiJson.jacksonStringLongs()`：将 64 位 `long`/`Long` 序列化为 JSON 字符串（`format: int64`），避免雪花 ID 在 JS/LLM 端精度丢失，同时兼容字符串和精确整数解码。
- `AiJson.jackson(objectMapper)`：复用应用自定义 `ObjectMapper`。
- `ProtobufJson`：按需添加 `com.google.protobuf:protobuf-java-util:3.25.5`，向 builder 传入 `new ProtobufJson(List.of(MyRequest.getDefaultInstance()), fallback)`。
