English | [中文](README.zh-CN.md)

# managame-example-ai

Standalone external AI service built purely on **Spring Boot 4** and **Spring AI 2** (`spring-ai-starter-model-openai` +
`spring-ai-starter-mcp-client`) with **zero compile dependency on managame**, paired with
[managame-example](../managame-example/README.md) (`GameServerApp`) over HTTP/SSE and standard **MCP Streamable HTTP**.

## Architecture

```
managame-example (GameServerApp)                managame-example-ai (Spring Boot + Spring AI)
  GameRuntime (PLAYER routes)                     GameAiController (/api/ai)
   ├── NpcAiService ──AiClient (HTTP / SSE)────▶   ├── POST /npc/chat   (ChatClient + ChatMemory + npcTools)
   │     (Continuation resumes player route)       ├── POST /npc/stream (ChatClient.stream -> SseEmitter)
   │                                               ├── POST /npc/decide (ChatClient.entity Structured Output)
   │                                               ├── POST /gm/ask     (ChatClient + gmTools)
   │                                               └── DELETE /sessions/{id} (chatMemory.clear)
   └── /mcp (BearerTokenAuthHandler + McpHttpHandler)
         ▲  MCP Streamable HTTP (JSON-RPC 2.0)       │
         └── Authorization + X-Caller + X-Role-Id ◀──┴── McpSyncHttpClientRequestCustomizer
```

## Scenarios Demonstrated

1. **Bearer token protection on `/mcp`**: unauthenticated requests are rejected with HTTP `401` by
   `BearerTokenAuthHandler` before reaching `McpHttpHandler`.
2. **NPC multi-turn chat + per-player / per-NPC isolation + MCP callback**:
   - `sessionId = "npc:" + roleId + ":" + npcId + ":" + epoch` isolates `ChatMemory` across players and NPCs.
   - Spring AI automatically discovers and invokes the game server's `@McpTool("query_bag")` on the target player's
     `GameRuntime` route, carrying `X-Role-Id` via `McpSyncHttpClientRequestCustomizer`.
   - Prompt-injection attempts to query another player's bag are rejected by the game server's `McpToolInterceptor`
     (`isError: true`).
   - `resetDialogue` bumps the game-side epoch and calls `DELETE /api/ai/sessions/{id}`.
3. **SSE streaming + cancellation**: Spring AI streams tokens through `SseEmitter`; calling `AiCall.cancel()` on the
   game server stops generation immediately.
4. **Structured option decision + fallback**: Spring AI uses `.call().entity(NpcDecideRes.class)`; the game server
   validates the chosen enum back on the player's route and falls back to rule-based logic on timeout or disallowed
   choices.
5. **GM natural-language ops**: `/gm/ask` uses `X-Caller: gm` to invoke `online_count` and `grant_item` (which reuses
   an `@HttpMethod` returning `HttpResult`, mapped via `resultAdapter` to MCP `isError: true` on rule violations).

## Running Against a Real LLM

1. Install the framework and `managame-example`, start `WorldServerApp` and `GameServerApp` (with `-Dai.mcp.port=8082`).
2. Start `GameAiServiceApplication` with any OpenAI-compatible provider (DeepSeek, Qwen, Ollama, OpenAI):

```bash
export OPENAI_BASE_URL=https://api.deepseek.com
export OPENAI_API_KEY=your-key
export OPENAI_MODEL=deepseek-chat
mvn -f managame-example-ai/pom.xml compile exec:java -Dexec.mainClass=cn.managame.example.aiservice.GameAiServiceApplication
```

## Running the Automated End-to-End Test (No API Key Required)

Install `managame-example` first, then run:

```bash
mvn -o -f managame-example-ai/pom.xml test "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"
```

`GameServerWithSpringAiIntegrationTest` boots the real `WorldServerApp`, `GameServerApp` and `GameAiServiceApplication`
against an in-process OpenAI-compatible stub (`FakeOpenAiServer`) and verifies all five scenarios over real loopback
connections.
