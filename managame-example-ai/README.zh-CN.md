[English](README.md) | 中文

# managame-example-ai

基于 **Spring Boot 4 + Spring AI 2**（`spring-ai-starter-model-openai`、`spring-ai-starter-mcp-client`、`spring-ai-starter-model-chat-memory-repository-jdbc`）开发的独立外部 AI 服务示例，**编译期零依赖 managame 框架**，通过标准 HTTP/SSE 与 **MCP Streamable HTTP** 与 [managame-example](../managame-example/README.zh-CN.md) 中的多台真实游戏服（`GameServerApp`）双向交互。

## 交互架构

```
managame-example（游戏服 2服 / 3服 ...）          managame-example-ai（Spring Boot + Spring AI）
  GameRuntime（PLAYER 路由）+ GameData             GameAiController（/api/ai）+ JDBC ChatMemory
   ├── NpcAiService ──AiClient (HTTP / SSE)────▶    ├── POST /npc/chat   （JDBC 记忆 + 按服路由 npcTools + Token 用量）
   │     （AiDispatcher + 脱敏 + 配额 + 审计）    ├── POST /npc/stream （ChatClient.stream -> SseEmitter）
   │                                                ├── POST /npc/decide （ChatClient.entity 结构化选项决策）
   │                                                ├── POST /gm/ask     （按目标区服路由 gmTools）
   │                                                ├── DELETE /sessions/{id} 与 /players/{prefix}（重置与删号清理）
   │                                                └── POST /admin/mcp/refresh（滚动升级后刷新 tools/list 缓存）
   └── /mcp（BearerTokenAuthHandler + McpHttpHandler）
         ▲  MCP Streamable HTTP (JSON-RPC 2.0)        │
         └── NPC/GM 独立 Token + X-Caller + X-Role-Id ◀┴── GameServerMcpRegistry（按 serverId 懒加载 McpSyncClient）
```

## 演示的全生命周期生产能力

1. **NPC/GM 独立凭证鉴权与双 Token 热轮换**：`BearerTokenAuthHandler` 拦截未授权访问，禁止低权限 NPC Token 伪造 `X-Caller: gm`，并支持逗号分隔多 Token 并存窗口平滑轮换密钥。
2. **多区服 MCP 路由与工具目录热刷新**：`GameServerMcpRegistry` 按 `game.mcp.servers.<serverId>` 懒连接对应区服 `/mcp`（AI 服务可先于游戏服启动），精准回查或修改玩家所在区服状态；游戏服滚动升级新增 `@McpTool` 后调 `POST /api/ai/admin/mcp/refresh` 即可热刷新目录。
3. **JDBC 持久化会话记忆、代号落库与删号双端清理**：通过 `JdbcChatMemoryRepository` 持久化多轮记忆（支持 AI 服务多实例扩容），游戏服通过 `PlayerAiState` 持久化 `epoch`；删号时 `onRoleDelete` 同时清理本地状态与远端该角色全部历史会话。
4. **SSE 流式对话、跨分片敏感词过滤与断线自动取消**：`ContentSafetyFilter.StreamFilter` 采用最长敏感词前缀驻留，正常分片零延迟透出、跨分片拆开的敏感词准确脱敏；玩家断线经 `GameRuntime.handOn` 自动取消在途流。
5. **单玩家配额/冷却、一键降级开关与决策兜底**：按 `GameTime` 自然日控制单玩家每日配额，同一 NPC 限制单在途请求；关闭 `aiEnabled` 时立即退回本地脚本台词；结构化决策超时或越界自动退回规则默认值。
6. **GM 写工具 `opId` 幂等防重发、高额拦截与审计/Token 记账**：`grant_item` 按 `opId` 去重防止超时重试双倍发奖，超单次上限强制要求人工审批，并通过 `LogRepository` 将 `MCP_WRITE` 审计及 `NPC_CHAT` / `GM_ASK` Token 消耗异步批量写入 `ai_audit_log`。

## 对接真实大模型运行

```bash
export OPENAI_BASE_URL=https://api.deepseek.com
export OPENAI_API_KEY=your-key
export OPENAI_MODEL=deepseek-chat
mvn -f managame-example-ai/pom.xml compile exec:java -Dexec.mainClass=cn.managame.example.aiservice.GameAiServiceApplication
```

## 一键运行全生命周期端到端测试（无需真实 API Key）

先安装框架与 `managame-example`，再执行：

```bash
mvn -o -f managame-example-ai/pom.xml test "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

`GameServerWithSpringAiIntegrationTest` 会在本机回环网络上同时启动真实的 `WorldServerApp`、两台 `GameServerApp`（2 服与 3 服）与 `GameAiServiceApplication`，配合本地 OpenAI 兼容桩服务（`FakeOpenAiServer`）端到端验证上述全部场景。
