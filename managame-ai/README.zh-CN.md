[English](README.md) | 中文

# managame-ai

基于 Java 25 的轻量、协议无关的游戏服务器 AI 接入薄封装。

大模型厂商协议、MCP、A2A、JSON Schema、Prompt、记忆、RAG 与 Agent 编排都归外部独立 AI 服务（例如基于 Spring AI 或 Python 搭建的服务）；`managame-ai` 只维护游戏服务器这一侧长期稳定的双向边界，避免 AI 行业标准协议演进导致游戏服框架跟着变更：

1. **游戏服 → AI 服务（`AiClient`）**：无状态、非阻塞异步调用（单次请求、支持随时取消的 W3C SSE 流式接收、单向游戏事件通知），内置超时、并发上限保护、拦截器、指标与可替换的 `AiTransport`。
2. **AI 服务 → 游戏服（`AiTools` + `AiToolHttpHandler`）**：用注解声明允许 AI 调用的游戏服方法（`@AiTool`），启动期编译 `MethodHandle`，支持分组/只读元信息、拦截器（鉴权、可见性过滤、开关与审计）、同步或异步（`AiToolContext`）应答、可插拔 `AiToolExecutor`（安全调度到游戏路由），以及极薄的普通 HTTP/JSON 入口（`GET /tools`、`POST /tools/{name}`）。

仅依赖 `managame-common` 和 `managame-network`，无任何第三方编译依赖，且独立于 `managame-runtime`、`managame-rpc` 与 `managame-data`。

| 包 | 内容 |
| --- | --- |
| `cn.managame.ai` | `AiClient`、`AiRequest`、`AiResult`、`AiCall`、`AiCallback`、`AiStreamListener`、`AiChunk`、`AiClientInterceptor`、`AiTools`、`AiToolDefinition`、`AiToolContext`、`AiToolResult`、`AiToolCall`、`AiToolInterceptor`、`AiJsonCodec`、`AiException` |
| `cn.managame.ai.annotation` | `@AiTool` |
| `cn.managame.ai.http` | `AiToolHttpHandler`（简洁 HTTP/JSON 入口：`GET /tools`、`POST /tools/{name}`） |
| `cn.managame.ai.spi` | `AiTransport`（出站传输 SPI）、`AiToolExecutor`（入站工具调度器） |
| `cn.managame.ai.internal` | 内部 SSE 解析、`MethodHandle` 调用、基础标量编解码与 JDK `HttpClient` 传输（非 API） |

## 调用外部 AI 服务（`AiClient`）

```java
AiClient ai = AiClient.builder()
        .baseUrl("http://127.0.0.1:8081/api/ai")
        .codec(jsonCodec)                          // 例如基于 Jackson；默认 AiJsonCodec.basic()
        .timeout(Duration.ofSeconds(30))
        .maxInFlight(256)
        .header("Authorization", "Bearer " + token)
        .intercept(List.of(traceInterceptor))
        .metrics(metrics, "npc")
        .build();

// 异步单次调用（绝不阻塞调用线程，回调不在调用线程运行）
AiCall call = ai.call("/npc/chat", new NpcChatReq(npcId, text), NpcReply.class, result -> {
    if (result.isSuccess()) { ... } else { ... }
});

// 异步流式调用（SSE，支持随时取消，例如玩家下线时中止生成）
AiCall stream = ai.stream("/npc/chat/stream", new NpcChatReq(npcId, text), new AiStreamListener() {
    @Override public void onChunk(AiChunk chunk) { ... }
    @Override public void onComplete() { ... }
    @Override public void onError(int errorCode, String message) { ... }
});
stream.cancel();

// 异步单向通知（把游戏事件推送给 AI 服务更新记忆或触发决策）
ai.notify("/events/boss-killed", new BossKilledEvent(bossId, killerRoleId));

ai.close();
```

- **单次请求覆盖**：通过 `AiRequest.of(path, body).withHeader(...).withTimeout(...)` 为单次调用追加 Header 或设置独立超时。
- **共享 `HttpClient`**：多个 `AiClient` 可通过 `.httpClient(shared)` 共用同一个连接池；共享实例不会在 `AiClient.close()` 时被关闭。
- **过载与关闭**：超过 `maxInFlight` 的调用以 `AI_OVERLOADED` 失败；`close()` 后新调用返回 `AI_CLOSED`，在途调用与流式请求会被取消并收到 `AI_CANCELLED`。
- **自定义传输**：通过 `.transport(...)` 提供 `AiTransport` 实现，可改走 `managame-rpc`、其他内部协议或内存 Fake 测试。
- **配合 `managame-runtime`**：在游戏任务里发起调用前先取 `Continuation back = runtime.continuation()`，在回调或流式分片里经 `back.execute(...)` 回到发起任务的路由上执行——玩家路由永不阻塞，即使 AI 服务在推理中途反过来调用该玩家的工具也不会死锁。

## 暴露游戏服方法给 AI 调用（`AiTools`）

```java
record QueryBag(long roleId, Integer category) {}
record SendMail(long roleId, String title, String content) {}

class PlayerAiTools {
    @AiTool(name = "query_player_bag", description = "查询玩家背包里的物品列表", group = "npc", readOnly = true)
    public BagView queryBag(QueryBag req) {
        return bags.snapshot(req.roleId(), req.category());
    }

    @AiTool(name = "online_count", description = "查询当前在线玩家人数", group = "gm", readOnly = true)
    public int onlineCount() { return sessions.size(); }

    @AiTool(name = "send_mail", description = "给玩家发送一封系统邮件", group = "gm")
    public void sendMail(AiToolContext context, SendMail req) {
        mails.send(req, result -> context.reply(result));
    }
}

AiTools tools = AiTools.builder()
        .codec(jsonCodec)
        .executor(toolExecutor)                    // 默认使用虚拟线程；配合 GameRuntime 时可路由到对应 (domain, routeKey)
        .intercept(List.of(authInterceptor))
        .register(List.of(new PlayerAiTools()))
        .metrics(metrics)
        .build();

NetworkServer server = NetworkServer.start(8082,
        HttpInitializer.http().contextPath("/ai").handler(new AiToolHttpHandler(tools)).build());
```

- **方法签名**：支持 `()`、`(arg)`、`(AiToolContext)` 或 `(AiToolContext, arg)`。同步返回任意 DTO 或 `AiToolResult`（业务失败用 `AiToolResult.fail(code, message)`），或声明为 `void` 并带 `AiToolContext` 参数稍后在任意线程通过 `context.reply` / `context.fail` 应答。非 `void` 方法返回 `null` 视为 bug（`AI_TOOL_EXECUTION_ERROR`）。
- **编程式注册**：`Builder.register(name, description, group, readOnly, handler)` 支持按配置动态注册工具。
- **拦截器（`AiToolInterceptor`）**：`allowList(headers, tool)` 按调用方过滤可见工具列表；`before` / `after` 负责鉴权、分组权限校验、只读限制、功能开关与审计日志。
- **对接 `managame-runtime`**：提供一个 `AiToolExecutor`，从 `call.method()` 和 `call.argument()` 读取路由信息并提交给 `runtime.execute(gameContext, _ -> invocation.run())`，runtime 拒绝时调用 `call.context().fail(...)`。
- **接入方式灵活**：可直接挂载 `AiToolHttpHandler` 提供内部 HTTP/JSON 接口（`GET /tools`、`POST /tools/{name}`），也可以由应用自己的 `RpcHandler` 或网关直接调 `tools.list(...)` / `tools.invoke(...)`。外部 Spring AI 服务按普通内部服务调用这些接口，再由 Spring AI 自己负责对外对接 MCP、大模型 `@Tool` 等易变的 AI 标准协议。

## 错误码

位于 `FrameworkErrorCodes`（`5001–5999`）：`AI_CLOSED`、`AI_UNAVAILABLE`、`AI_TIMEOUT`、`AI_OVERLOADED`、`AI_CANCELLED`、`AI_PROTOCOL_ERROR`、`AI_TOOL_NOT_FOUND`、`AI_TOOL_INVALID_ARGS`、`AI_TOOL_EXECUTION_ERROR`、`AI_TOOL_REJECTED`。

## 日志与指标

- Logger（`System.Logger`）：`cn.managame.ai.client`、`cn.managame.ai.tool`、`cn.managame.ai.http`。
- 客户端指标（`AiClient.builder().metrics(...)`，可选 `client` 标签）：`managame.ai.client.calls`（`kind`）、`managame.ai.client.in_flight`、`managame.ai.client.failures`（`code`）、`managame.ai.client.stream_chunks`、`managame.ai.client.latency`（`_bucket`、`_nanos`）。
- 工具指标（`AiTools.builder().metrics(...)`）：`managame.ai.tool.calls`（`tool`）、`managame.ai.tool.failures`（`tool`）、`managame.ai.tool.rejected`（`tool`）、`managame.ai.tool.not_found`、`managame.ai.tool.invalid_args`、`managame.ai.tool.latency`（`_bucket`、`_nanos`）。`Metrics.noop()` 下不读时钟。

## 构建与测试

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

测试使用真实回环连接，覆盖单次调用、增量 SSE 流式推送、取消、超时、过载、拦截器、`AiToolHttpHandler`，以及 `GameServerAiExampleTest` 中的游戏服与外部 AI 服务双向互调完整示例。
