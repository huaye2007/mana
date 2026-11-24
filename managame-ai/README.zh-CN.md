[English](README.md) | 中文

# managame-ai

基于 Java 25 的游戏服务器 AI 薄封装组件，用于对接基于 Spring AI、LangChain 等第三方框架开发的独立外部 AI 服务。

大模型厂商对接、Prompt、会话记忆、RAG 与 Agent 编排都留在外部 AI 服务中；`managame-ai` 只维护游戏服一侧的两个薄边界，零第三方编译依赖（仅依赖 `managame-common` 和 `managame-network`），且与 `managame-runtime`、`managame-rpc`、`managame-data` 相互独立：

1. **游戏服 → AI 服务（`AiClient`）**：非阻塞 HTTP 与 W3C SSE 客户端，内置并发准入（`maxInFlight`）、单次与流式总超时、随时取消、单向事件通知、`Metadata` 请求头透传、可插拔 `AiCodec` 与 `Metrics`。
2. **AI 服务 → 游戏服（`@McpTool` + `McpTools` + `McpHttpHandler`）**：在游戏服现有方法上加注解，直接以标准 **MCP（Model Context Protocol）** 无状态 Streamable HTTP（JSON-RPC 2.0）工具对外开放。启动期一次性编译 `MethodHandle` 与 JSON Schema，支持拦截器按调用方过滤可见工具、鉴权与审计，通过 `McpToolExecutor` 安全调度到游戏执行模型（例如 `GameRuntime`）。

| 包 | 内容 |
| --- | --- |
| `cn.managame.ai` | `AiClient`、`AiRequest`、`AiResult`、`AiCall`、`AiCallback`、`AiStreamEvent`、`AiStreamListener`、`AiCodec` |
| `cn.managame.ai.annotation` | `@McpTool`、`@McpParam` |
| `cn.managame.ai.mcp` | `McpTools`、`McpHttpHandler`、`McpToolDefinition`、`McpToolContext`、`McpToolCall`、`McpToolResult`、`McpToolInterceptor`、`McpToolHandler` |
| `cn.managame.ai.spi` | `McpToolExecutor` |
| `cn.managame.ai.internal` | 零依赖 JSON 编解码、JSON Schema 生成、基础 Codec、SSE 解析器、编译后的 `ToolMethod` 与指标（非 API） |

## 调用外部 AI 服务（`AiClient`）

```java
AiClient ai = AiClient.builder("http://127.0.0.1:8081/api/ai")
        .codec(jsonCodec)                             // 例如基于 Jackson；默认 AiCodec.basic()
        .timeout(Duration.ofSeconds(30))
        .streamIdleTimeout(Duration.ofSeconds(10))    // 可选：SSE 分片间/首包最大空闲超时
        .maxInFlight(128)
        .header("Authorization", () -> "Bearer " + currentToken())
        .propagate(TRACE_ID, "X-Trace-Id")
        .metrics(metrics, "npc")
        .build();

// 非阻塞单次调用（默认 POST，也支持 AiRequest.get / delete / withMethod）；回调运行在独立执行器上
AiCall call = ai.call(AiRequest.of("/npc/chat", new NpcTalk(roleId, text), ctx.metadata()),
        NpcReply.class, result -> {
            if (result.isSuccess()) { ... } else { ... }
        });

// 轮询异步长任务或清理远端会话
ai.call(AiRequest.get("/jobs/" + jobId), JobStatus.class, result -> ...);
ai.call(AiRequest.delete("/sessions/" + sessionId), Void.class, result -> ...);

// 增量 W3C SSE 流式接收，随时可取消（例如玩家下线）
AiCall stream = ai.stream("/npc/chat/stream", new NpcTalk(roleId, text),
        event -> { ... },
        result -> { ... });
stream.cancel();

// 单向异步事件通知（独立并发预算，返回 network 的 WriteStatus）
WriteStatus status = ai.notify("/events/boss-killed", new BossKilled(bossId, killerRoleId));

ai.close();
```

- **配合 `managame-runtime`**：在游戏任务发起调用前先取 `Continuation then = runtime.continuation()`，在回调里经 `then.execute(c -> ...)` 回到原路由继续修改状态；在 continuation 里按 id 重新取实体，不要跨调用持有实体。
- **会话隔离与重置**：在请求 DTO 中带一个业务会话键（例如 `"npc:" + roleId + ":" + npcId + ":" + dialogueEpoch`），对应 Spring AI 的 `ChatMemory.CONVERSATION_ID` 或 LangGraph 的 `thread_id`。重置会话只需把游戏数据里的 `dialogueEpoch` 加 1，无需额外协议状态。
- **共享 `HttpClient` 与舱壁隔离**：按业务域建多个 `AiClient`（例如 `npc` 配 30s 超时、`moderation` 配 500ms 超时），通过 `.httpClient(shared)` 共享连接池，避免慢对话占满 `maxInFlight` 饿死实时审核；共享实例不会在 `AiClient.close()` 时被关闭。
- **原始字符串**：请求体为 `String` 时按 UTF-8 原样发送（不经 `AiCodec.encode`）；响应类型为 `String.class` 时原样返回（不经 `AiCodec.decode`）。
- **准入、体积上限与关闭**：超过 `maxInFlight` 的调用以 `AI_OVERLOADED` 失败；单向 `notify` 使用独立的 `maxNotifyInFlight` 预算并返回 `WriteStatus`（`ACCEPTED`、`INACTIVE`、`NOT_WRITABLE`）；单次响应超过 `maxResponseBytes`（默认 1 MiB）或单条 SSE 事件超过 `maxStreamEventChars`（默认 256 KiB）以 `AI_PROTOCOL_ERROR` 中止；回调统一投递到 `.executor(...)`（默认虚拟线程），绝不在调用线程或 `HttpClient` I/O 线程上运行；`close()` 后在途调用与新调用均以 `AI_CLOSED` 失败。

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

    @McpTool(name = "online_count", description = "查询当前在线人数", group = "gm", readOnly = true)
    public int onlineCount() { return sessions.size(); }

    @McpTool(name = "query_rank", description = "跨服查询玩家排名",
            group = "npc", readOnly = true, routeKey = "roleId")
    public void queryRank(McpToolContext ctx, @McpParam("玩家角色ID") long roleId) {
        worldRpc.queryRank(roleId, rank -> ctx.reply(rank));
    }
}

McpTools tools = McpTools.builder()
        .codec(jsonCodec)
        .executor(gameRuntimeExecutor)                // 默认使用虚拟线程
        .intercept(List.of(authInterceptor))
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

- **支持的方法签名**：无参、单个 DTO（record 或 POJO，其成员自动展开为 `inputSchema` 顶层属性）、或多个显式参数（编译开 `-parameters` 或用 `@McpParam(name = ...)` 指定参数名），首位均可额外声明 `McpToolContext`。不要把 `@McpTool` 直接标在入参为 Protobuf 的协议处理器上，应标在底层 Service 方法上。
- **返回值语义与保护**：带 `McpToolContext` 的 `void` 方法异步通过 `context.reply` / `context.fail` 应答；不带 `McpToolContext` 的 `void` 方法同步返回 `"ok"`；业务失败返回 `McpToolResult.fail(...)`（MCP `isError: true`）；非 `void` 方法返回 `null` 视为 bug（`AI_TOOL_EXECUTION_ERROR`）。直接复用返回 `HttpResult` / `HandleResult` 的已有方法时，可通过 `.resultAdapter(...)` 把非 0 错误码映射为 `McpToolResult.fail`；通过 `.maxInFlight(...)`（默认 256）防止失控 Agent 打满游戏服路由；配了 `.timeout(...)` 时在停服时调用 `tools.close()`，排队期间已超时的调用会被跳过，已开始执行的非幂等写工具若超时会明确提示模型结果未知、先核对状态再决定是否重试。
- **结果上限与幂等提示**：`McpHttpHandler.builder(tools).maxResultChars(...)`（默认 256K 字符）会在返回体过大时转为 `isError: true` 提示模型缩小查询范围；支持安全重试的写工具可标 `@McpTool(idempotent = true)`（输出 `idempotentHint`），并在业务侧按 `opId` 做幂等去重。
- **拦截器（`McpToolInterceptor`）**：`allowList(headers, tool)` 按调用方过滤 `tools/list` 可见工具；`before(call)` 负责鉴权、分组/只读校验与开关（返回 `false` 拒绝并返回 `AI_TOOL_REJECTED`）；`after(call, result)` 在调用完成后逆序执行，用于审计日志。
- **对接 `managame-runtime`**：在 `@McpTool(routeKey = "roleId")` 上声明路由键成员（方法参数或单 DTO 的方法/getter/字段，启动期编译为 `MethodHandle`），提供一个 `McpToolExecutor` 读取 `call.method()`（例如 `@Domain`）和 `call.routeKey()` 投递 `GameTask` 到 `runtime.execute`，并在捕获拒绝异常和 `GameTask.refused` 中调用 `call.context().fail(...)`。完整拼装样例见 `managame-example` 中的 `AiConfig` 与 `AiRuntimeIntegrationExampleTest`。
- **MCP 协议兼容**：`McpHttpHandler` 实现无状态 Streamable HTTP（JSON-RPC 2.0 `initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call`；与已有 HTTP 端口共港时可配 `.path("/mcp")`，并校验 `MCP-Protocol-Version` 请求头），兼容 Spring AI、LangChain4j、LangChain 及官方 MCP SDK（`2024-11-05` 至 `2025-11-25`）。未知工具返回 JSON-RPC `-32602`；参数校验失败、拦截拒绝与业务失败返回 `isError: true`，便于大模型自我纠正。

## 错误码、日志与指标

- 错误码位于 `FrameworkErrorCodes`（`5001–5999`）：`AI_CLOSED`、`AI_UNAVAILABLE`、`AI_TIMEOUT`、`AI_OVERLOADED`、`AI_CANCELLED`、`AI_PROTOCOL_ERROR`、`AI_TOOL_NOT_FOUND`、`AI_TOOL_INVALID_ARGS`、`AI_TOOL_EXECUTION_ERROR`、`AI_TOOL_REJECTED`。
- Logger（`System.Logger`）：`cn.managame.ai.client`、`cn.managame.ai.mcp`。
- 客户端指标（`AiClient.builder().metrics(...)`，可选 `client` 标签）：`managame.ai.client.calls`（`kind`）、`managame.ai.client.failures`（`code`）、`managame.ai.client.in_flight`、`managame.ai.client.notify_in_flight`、`managame.ai.client.stream_events`、`managame.ai.client.latency`（`_bucket`、`_nanos`）。
- MCP 工具指标（`McpTools.builder().metrics(...)`，可选 `server` 标签）：`managame.ai.mcp.calls`（`tool`）、`managame.ai.mcp.failures`（`tool`）、`managame.ai.mcp.rejected`（`tool`）、`managame.ai.mcp.latency`（`tool`、`_bucket`、`_nanos`）、`managame.ai.mcp.not_found`、`managame.ai.mcp.invalid_args`、`managame.ai.mcp.overloaded`、`managame.ai.mcp.closed`、`managame.ai.mcp.in_flight`。`Metrics.noop()` 下不读时钟。

## 构建与测试

```bash
mvn -o clean install "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

测试全部使用真实回环连接，覆盖 `AiClient`（单次、增量 SSE、取消、超时、过载、关闭）、`McpTools`、`McpHttpHandler`，以及使用官方 MCP Java SDK 客户端的双向端到端联调测试。
