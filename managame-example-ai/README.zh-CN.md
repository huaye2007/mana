[English](README.md) | 中文

# managame-example-ai

基于 **Spring Boot 4 + Spring AI 2**（`spring-ai-starter-model-openai` + `spring-ai-starter-mcp-client`）开发的独立外部 AI 服务示例，**编译期零依赖 managame 框架**，通过标准 HTTP/SSE 与 **MCP Streamable HTTP** 与 [managame-example](../managame-example/README.zh-CN.md) 中的真实游戏服（`GameServerApp`）双向交互。

## 交互架构

```
managame-example（游戏服 GameServerApp）           managame-example-ai（Spring Boot + Spring AI）
  GameRuntime（PLAYER 路由）                        GameAiController（/api/ai）
   ├── NpcAiService ──AiClient (HTTP / SSE)────▶   ├── POST /npc/chat   （ChatClient + ChatMemory + npcTools）
   │     （Continuation 切回玩家路由）              ├── POST /npc/stream （ChatClient.stream -> SseEmitter）
   │                                               ├── POST /npc/decide （ChatClient.entity 结构化选项决策）
   │                                               ├── POST /gm/ask     （ChatClient + gmTools）
   │                                               └── DELETE /sessions/{id}（chatMemory.clear）
   └── /mcp（BearerTokenAuthHandler + McpHttpHandler）
         ▲  MCP Streamable HTTP (JSON-RPC 2.0)       │
         └── Authorization + X-Caller + X-Role-Id ◀──┴── McpSyncHttpClientRequestCustomizer
```

## 演示的 5 个真实业务场景

1. **`/mcp` 端点 Bearer Token 鉴权**：通过 Netty `BearerTokenAuthHandler` 拦截未授权请求并返回 `401`。
2. **NPC 多轮对话 + 不同玩家/不同 NPC 会话隔离 + MCP 回查背包**：
   - 以 `sessionId = "npc:" + roleId + ":" + npcId + ":" + epoch` 绑定 Spring AI `ChatMemory.CONVERSATION_ID`，实现玩家与 NPC 维度的完全记忆隔离。
   - Spring AI 自动发现并调用游戏服 `@McpTool("query_bag")`，通过 `McpSyncHttpClientRequestCustomizer` 透传 `X-Caller` 与 `X-Role-Id`，任务串行落在对应玩家的 `GameRuntime` 路由上执行。
   - 提示词注入试图跨玩家读取他人背包时，被游戏服 `McpToolInterceptor` 强校验拒绝并返回 MCP `isError: true`。
   - 重置对话时游戏服递增 `epoch` 并调用 `DELETE /api/ai/sessions/{id}` 清理记忆。
3. **SSE 流式对话与中途取消**：Spring AI 经 `SseEmitter` 逐片推送，游戏服调用 `AiCall.cancel()` 即可立即中止生成。
4. **结构化选项决策与降级**：Spring AI 用 `.call().entity(NpcDecideRes.class)` 输出枚举；游戏服切回玩家路由校验合法性，超时或非法选项自动降级到规则引擎。
5. **GM 自然语言运营助手**：`/gm/ask` 以 `X-Caller: gm` 身份调用 `online_count` 与 `grant_item`（复用返回 `HttpResult` 的 `@HttpMethod`，通过 `resultAdapter` 将业务失败映射为 MCP `isError: true`）。

## 对接真实大模型运行

1. 先安装框架与 `managame-example`，启动 `WorldServerApp` 与 `GameServerApp`（如 `-Dai.mcp.port=8082`）。
2. 配置任意 OpenAI 兼容服务（DeepSeek、通义千问、Ollama、OpenAI）环境变量后启动 `GameAiServiceApplication`：

```bash
export OPENAI_BASE_URL=https://api.deepseek.com
export OPENAI_API_KEY=your-key
export OPENAI_MODEL=deepseek-chat
mvn -f managame-example-ai/pom.xml compile exec:java -Dexec.mainClass=cn.managame.example.aiservice.GameAiServiceApplication
```

## 一键运行端到端联调测试（无需真实 API Key）

先在 `managame-example` 目录执行 `mvn -o install`，再运行：

```bash
mvn -o -f managame-example-ai/pom.xml test "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

`GameServerWithSpringAiIntegrationTest` 会在本机回环网络上同时启动真实的 `WorldServerApp`、`GameServerApp` 与 `GameAiServiceApplication`，配合本地 OpenAI 兼容桩服务（`FakeOpenAiServer`）自动验证全部 5 个场景。
