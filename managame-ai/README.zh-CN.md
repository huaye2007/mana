[English](README.md) | 中文

# managame-ai

基于 Java 25 的游戏服务器 AI 薄封装组件，用于对接基于 Spring AI、LangChain 等第三方框架开发的独立外部 AI 服务。

大模型厂商对接、Prompt、会话记忆、RAG 与 Agent 编排都留在外部 AI 服务中；`managame-ai` 只维护游戏服一侧的两个薄边界，零第三方编译依赖（仅依赖 `managame-common` 和 `managame-network`），与 `managame-runtime`、`managame-rpc`、`managame-data` 平等且完全独立——既可与 `GameRuntime` 无缝拼装，也可直接接入任意其他游戏服务器框架（Actor、自定义线程模型等）：

1. **游戏服 → AI 服务（全服单实例 `AiClient`）**：全服所有业务模块共享唯一一个非阻塞 HTTP 与 W3C SSE 客户端、唯一连接池与全进程共享守护时间轮。通过 `AiDispatcher` 在启动期一次性桥接服务器的上下文与路由模型后，业务模块直接调用即可自动校验上下文、透传 `Metadata` 请求头并在原业务路由按序接收回调，内置并发准入（`maxInFlight`）、端到端总超时（含响应头与流/分块响应体）、空闲超时、随时取消、单向事件通知与 `Metrics`。
2. **AI 服务 → 游戏服（`@McpTool` + `McpTools` + `McpHttpHandler`）**：在游戏服现有方法上加注解，直接以标准 **MCP（Model Context Protocol）** 无状态 Streamable HTTP（JSON-RPC 2.0）工具对外开放。启动期一次性编译 `MethodHandle`、路由键提取器与 JSON Schema，回调 `McpToolExecutor.prepare` 完成框架注解预检，通过拦截器按调用方过滤可见工具、鉴权与审计，并由 `McpToolExecutor` 安全调度到游戏执行路由。

| 包 | 内容 |
| --- | --- |
| `cn.managame.ai` | `AiClient`、`AiRequest`、`AiResult`、`AiCall`、`AiCallback`、`AiStreamEvent`、`AiStreamListener`、`AiCodec` |
| `cn.managame.ai.annotation` | `@McpTool`、`@McpParam` |
| `cn.managame.ai.mcp` | `McpTools`、`McpHttpHandler`、`McpToolDefinition`、`McpToolContext`、`McpToolCall`、`McpToolResult`、`McpToolInterceptor`、`McpToolHandler`、`McpToolProxy` |
| `cn.managame.ai.spi` | `AiDispatcher`、`McpToolExecutor` |
| `cn.managame.ai.internal` | 零依赖 JSON 编解码、JSON Schema 生成、基础 Codec、SSE 解析器、共享守护时间轮、编译后的 `ToolMethod` 与指标（非 API） |

## 调用外部 AI 服务（全服单实例 `AiClient`）

整个游戏服进程只需构建**唯一一个 `AiClient`**，并在启动期通过 `AiDispatcher` 一次性桥接服务器框架的上下文与路由（不依赖特定运行时，默认使用虚拟线程）：

```java
// 启动期配置一次（以对接 managame-runtime 为例；其他 Actor/路由框架同理）：
AiDispatcher runtimeDispatcher = new AiDispatcher() {
    @Override public Executor capture(Object explicitCtx) {
        if (explicitCtx != null) {
            Context target = (Context) explicitCtx;
            runtime.checkDomain(target.domain());
            return task -> runtime.handOn(target, _ -> task.run());
        }
        Continuation back = runtime.continuation();   // 强制要求当前在业务路由任务内，并捕获原路由
        return task -> back.execute(_ -> task.run());
    }
    @Override public Metadata metadata(Object explicitCtx) {
        if (explicitCtx instanceof Context target) return target.metadata();
        return Context.hasCurrent() ? Context.current().metadata() : Metadata.empty();
    }
};

AiClient ai = AiClient.builder("http://127.0.0.1:8081/api/ai")
        .codec(jsonCodec)                             // 例如基于 Jackson；默认 AiCodec.basic()
        .dispatcher(runtimeDispatcher)                // 默认 AiDispatcher.virtualThreads()
        .timeout(Duration.ofSeconds(30))
        .streamIdleTimeout(Duration.ofSeconds(10))    // 可选：SSE 分片间/首包最大空闲超时
        .maxInFlight(1024)
        .header("Authorization", () -> "Bearer " + currentToken())
        .propagate(TRACE_ID, "X-Trace-Id")
        .metrics(metrics)
        .build();

// 全服任意业务模块注入同一个 AiClient 直接调用：自动透传 X-Trace-Id，回调自动回到发起调用的玩家路由
AiCall call = ai.call("/npc/chat", new NpcTalk(roleId, text), NpcReply.class, result -> {
    if (result.isSuccess()) { ... } else { ... }
});

// 单次快接口按请求覆盖超时（例如实时内容审核 500ms），无需拆分多个客户端实例
ai.call(AiRequest.of("/moderation/check", text).withTimeout(Duration.ofMillis(500)),
        ModerationReply.class, result -> ...);

// 查询参数与路径段安全编码；非路由入口可通过 .withContext(...) 显式指定目标上下文
ai.call(AiRequest.get("/jobs/status").withQuery("jobId", jobId), JobStatus.class, result -> ...);
ai.call(AiRequest.delete("/sessions/" + AiRequest.encodeSegment(sessionId)), Void.class, result -> ...);

// 增量 W3C SSE 流式接收：分片与完成回调按序回到原业务路由，收到 event: error 自动按失败收敛，随时可取消
AiCall stream = ai.stream("/npc/chat/stream", new NpcTalk(roleId, text),
        event -> { ... },
        result -> { ... });
stream.cancel();

// 单向异步事件通知（独立并发预算，返回 network 的 WriteStatus）
WriteStatus status = ai.notify("/events/boss-killed", new BossKilled(bossId, killerRoleId));

ai.close();
```

- **单客户端服务全服百余模块**：无需按业务模块拆分多个 `AiClient`。单个 `AiClient` 统一管理连接池、并发水位（默认 `maxInFlight = 1024`）与全进程唯一的 10ms 守护时间轮；不同业务接口的快慢差异直接通过 `AiRequest.withTimeout(...)` 按请求覆盖，响应反序列化在进入业务路由前并行完成，绝不占用路由串行时间。在回调里按 id 重新取实体，不要跨异步调用持有实体引用。
- **会话隔离与重置**：在请求 DTO 中带业务会话键（例如 `"npc:" + serverId + ":" + roleId + ":" + npcId + ":" + dialogueEpoch`），对应 Spring AI 的 `ChatMemory.CONVERSATION_ID` 或 LangGraph 的 `thread_id`。重置会话只需递增游戏数据里的 `dialogueEpoch`。
- **原始字符串、`byte[]` 直通与 64 位雪花 ID**：请求体为 `String` 或 `byte[]` 时原样发送，响应类型为 `String.class` 或 `byte[].class` 时原样返回；自定义 `AiCodec` 可覆盖 `encodeBytes` / `decodeBytes` 省去单次调用的中间 `String` 拷贝。内置 `AiCodec.basic()` 支持无损解析引号包裹的 64 位雪花 `long` ID，并原生支持 `UUID`、`URI`、`Duration` 与 `java.time.*` 类型。
- **全阶段超时、准入、体积上限与关闭**：默认随请求发送 `X-Ai-Timeout-Ms` 毫秒预算头（可通过 `.timeoutHeader(...)` 改名或禁用）；守护时间轮同时覆盖响应头与分块响应体读取阶段，防止下游先刷出 `200 OK` 头再挂起导致槽位泄漏；超过 `maxInFlight` 以 `AI_OVERLOADED` 失败；单次响应超过 `maxResponseBytes`（默认 1 MiB）或单条 SSE 事件超过 `maxStreamEventChars`（默认 256 KiB）以 `AI_PROTOCOL_ERROR` 中止；SSE 流中途收到 `event: error` 自动以 `AI_UNAVAILABLE` 失败收敛；`close()` 后在途与后续调用均以 `AI_CLOSED` 失败。

## 通过注解把现有方法暴露为 MCP 工具（`@McpTool`）

直接在现有的业务 Service 或 Handler 方法上添加注解即可成为标准 MCP 工具：

```java
record SendMail(
        @McpParam("玩家角色ID") long roleId,
        @McpParam("邮件标题") String title,
        @McpParam("邮件内容") String content) {}

class PlayerTools {
    @McpTool(name = "query_bag", description = "查询玩家背包里的物品",
            group = "npc", readOnly = true, routeKey = "roleId")
    public BagView queryBag(
            @McpParam("玩家角色ID") long roleId,
            @McpParam(value = "可选的物品分类", required = false) String category) {
        return bags.snapshot(roleId, category);
    }

    @McpTool(name = "send_mail", description = "给玩家发送一封系统邮件",
            group = "gm", idempotent = true, routeKey = "roleId")
    public McpToolResult sendMail(SendMail mail) { ... }

    @McpTool(name = "kick_player", description = "强制踢玩家下线",
            group = "gm", destructive = true, routeKey = "roleId")
    public void kickPlayer(@McpParam("玩家角色ID") long roleId) { ... }

    @McpTool(name = "query_rank", description = "跨服查询玩家排名",
            group = "npc", readOnly = true, routeKey = "roleId")
    public void queryRank(McpToolContext ctx, @McpParam("玩家角色ID") long roleId) {
        worldRpc.queryRank(roleId, rank -> ctx.reply(rank));
    }
}

McpTools tools = McpTools.builder()
        .codec(jsonCodec)
        .executor(gameRuntimeExecutor)                // 默认 McpToolExecutor.virtualThreads()
        .intercept(List.of(authInterceptor, auditInterceptor))
        .timeout(Duration.ofSeconds(10))              // 可选：异步工具超时兜底
        .resultAdapter(res -> ...)                    // 可选：复用返回 HttpResult / HandleResult 的方法
        .register(List.of(new PlayerTools()))
        .metrics(metrics)
        .build();

NetworkServer mcpServer = NetworkServer.start(8082,
        HttpInitializer.http().contextPath("/mcp")
                .handler(McpHttpHandler.builder(tools).serverInfo("my-game", "1.0.0").build())
                .build());
```

- **支持的方法签名**：无参、单个 DTO（record 或 POJO，其成员展开为 `inputSchema` 顶层属性）、或多个显式参数（编译开 `-parameters` 或用 `@McpParam(name = ...)` 指定参数名），首位可可选声明 `McpToolContext`。
- **返回值语义、安全提示与保护**：带 `McpToolContext` 的 `void` 方法异步通过 `context.reply` / `context.fail` 应答；不带 `McpToolContext` 的 `void` 方法同步返回 `"ok"`；业务失败返回 `McpToolResult.fail(...)`（MCP `isError: true`）；非 `void` 方法返回 `null` 视为 bug（`AI_TOOL_EXECUTION_ERROR`）。`@McpTool` 支持声明 `readOnly`、`destructive`（破坏性高危操作提示，便于 Agent 触发人工确认）与 `idempotent`（幂等重试提示），并默认标记 `openWorldHint: false`。`McpHttpHandler` 默认限制结果不超过 `256K` 字符；`tools.close()` 会主动以 `AI_CLOSED` 中止未完成的异步调用，防止连接悬挂。
- **上下文与拦截器（`McpToolContext` / `McpToolInterceptor`）**：`McpToolContext` 内置 `Metadata`（`put` / `metadata()`），便于前置鉴权拦截器（`before`）将解析出的调用方身份传递给工具与后置审计（`after`）；`McpToolCall.rawArgumentsJson()` 提供原始参数 JSON 切片供审计落库或网关零重序列化转发。当异步工具在外部 I/O 线程（如 RPC EventLoop）调用 `ctx.reply` 时，`McpTools` 自动经 `McpToolExecutor.complete` 切离 I/O 线程再执行 `after` 与响应编码。
- **对接 `managame-runtime` 或其他游戏框架**：实现 `McpToolExecutor`，在 `prepare(definition, method)` 中一次性校验框架注解（如 `@Domain`）并预建每工具独立的 `TaskKind`，在 `execute` 与 `complete` 中按编译好的 `call.routeKey()` 投递到对应玩家路由。完整示例见 `managame-example` 中的 `AiConfig`。
- **无状态网关代理（`scanDefinitions` / `registerProxy`）**：无状态边缘网关无需实例化业务 Bean，即可通过 `McpTools.scanDefinitions(types)` 导出工具目录，或用 `McpTools.builder().registerProxy(types, call -> ...)` 在边缘完成参数校验、64 位 `routeKey` 提取与越权拦截，再将 `call.rawArgumentsJson()` 经 RPC 转发给目标游戏服。

## 错误码、日志与指标

- 错误码位于 `FrameworkErrorCodes`（`5001–5999`）：`AI_CLOSED`、`AI_UNAVAILABLE`、`AI_TIMEOUT`、`AI_OVERLOADED`、`AI_CANCELLED`、`AI_PROTOCOL_ERROR`、`AI_TOOL_NOT_FOUND`、`AI_TOOL_INVALID_ARGS`、`AI_TOOL_EXECUTION_ERROR`、`AI_TOOL_REJECTED`。
- Logger（`System.Logger`）：`cn.managame.ai.client`、`cn.managame.ai.mcp`。
- 客户端指标（`AiClient.builder().metrics(...)`，可选 `client` 标签）：`managame.ai.client.calls`（`kind`）、`managame.ai.client.failures`（`code`）、`managame.ai.client.in_flight`、`managame.ai.client.notify_in_flight`、`managame.ai.client.stream_events`、`managame.ai.client.latency`（`_bucket`、`_nanos`）。
- MCP 工具指标（`McpTools.builder().metrics(...)`，可选 `server` 标签）：`managame.ai.mcp.calls`（`tool`）、`managame.ai.mcp.failures`（`tool`，仅统计框架/异常/超时故障）、`managame.ai.mcp.business_failures`（`tool`，业务规则错误码）、`managame.ai.mcp.rejected`（`tool`）、`managame.ai.mcp.latency`（`tool`、`_bucket`、`_nanos`）、`managame.ai.mcp.timeouts`、`managame.ai.mcp.not_found`、`managame.ai.mcp.invalid_args`、`managame.ai.mcp.overloaded`、`managame.ai.mcp.closed`、`managame.ai.mcp.in_flight`。`Metrics.noop()` 下不读时钟。

## 构建与测试

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```
