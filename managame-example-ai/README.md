English | [中文](README.zh-CN.md)

# managame-example-ai

Standalone external AI service built purely on **Spring Boot 4** and **Spring AI 2** (`spring-ai-starter-model-openai`, `spring-ai-starter-mcp-client`, `spring-ai-starter-model-chat-memory-repository-jdbc`) with **zero compile dependency on managame**, paired with [managame-example](../managame-example/README.md) (`GameServerApp`) over HTTP/SSE and standard **MCP Streamable HTTP**.

## Architecture

```
managame-example (GameServerApp #2 / #3 ...)     managame-example-ai (Spring Boot + Spring AI)
  GameRuntime (PLAYER routes) + GameData           GameAiController (/api/ai) + JDBC ChatMemory
   ├── NpcAiService ──AiClient (HTTP / SSE)────▶    ├── POST /npc/chat   (JDBC ChatMemory + server-routed npcTools)
   │     (AiDispatcher + filter + quota + audit)  ├── POST /npc/stream (ChatClient.stream -> SseEmitter)
   │                                                ├── POST /npc/decide (ChatClient.entity Structured Output)
   │                                                ├── POST /gm/ask     (target serverId + gmTools)
   │                                                ├── DELETE /sessions/{id} & /players/{prefix}
   │                                                └── POST /admin/mcp/refresh (invalidate tools/list cache)
   └── /mcp (BearerTokenAuthHandler + McpHttpHandler)
         ▲  MCP Streamable HTTP (JSON-RPC 2.0)        │
         └── NPC/GM Bearer + X-Caller + X-Role-Id ◀───┴── GameServerMcpRegistry (per-server lazy McpSyncClient)
```

## Full Lifecycle Capabilities Demonstrated

1. **Separated NPC/GM tokens & zero-downtime key rotation**: `BearerTokenAuthHandler` blocks unauthenticated requests and prevents an NPC token from forging `X-Caller: gm`, while supporting multi-token rotation windows.
2. **Multi-server MCP routing & hot catalog refresh**: `GameServerMcpRegistry` lazily connects to each game server (`game.mcp.servers.<serverId>`) so the AI service can boot before game servers and route MCP calls (`query_bag`, `grant_item`) to the exact server hosting the player; `POST /api/ai/admin/mcp/refresh` refreshes cached `tools/list` after rolling upgrades.
3. **JDBC `ChatMemory` + persisted epochs + role deletion cleanup**: multi-turn memory is persisted via `JdbcChatMemoryRepository` under `npc:<serverId>:<roleId>:<npcId>:<epoch>`; `PlayerAiState` persists epochs in `GameData`; `onRoleDelete` purges both local state and remote JDBC conversations.
4. **SSE streaming, split-chunk content safety & disconnect cancellation**: `ContentSafetyFilter.StreamFilter` masks sensitive words even when split across SSE chunks without delaying normal tokens; `onPlayerDisconnect` cancels active streams immediately via `GameRuntime.handOn`.
5. **Per-player quota, degrade switch & structured decision fallback**: enforces per-player daily quotas/cooldowns, falls back to scripted NPC lines when `aiEnabled=false`, and falls back to rule-based actions on timeout or disallowed enum outputs.
6. **Idempotent GM write tools (`opId`) & MySQL audit/token accounting**: `grant_item` deduplicates retries by `opId`, blocks oversized grants requiring human approval, and records both `MCP_WRITE` audits and `NPC_CHAT` / `GM_ASK` token usage into `ai_audit_log` via `LogRepository`.

## Running Against a Real LLM

```bash
export OPENAI_BASE_URL=https://api.deepseek.com
export OPENAI_API_KEY=your-key
export OPENAI_MODEL=deepseek-chat
mvn -f managame-example-ai/pom.xml compile exec:java -Dexec.mainClass=cn.managame.example.aiservice.GameAiServiceApplication
```

## Running the Automated End-to-End Test (No API Key Required)

Install the framework and `managame-example` first, then run:

```bash
mvn -o -f managame-example-ai/pom.xml test "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

`GameServerWithSpringAiIntegrationTest` boots `WorldServerApp`, two `GameServerApp` instances (`serverId=2` and `serverId=3`) and `GameAiServiceApplication` against an in-process OpenAI-compatible stub (`FakeOpenAiServer`) and verifies every lifecycle scenario end to end.
