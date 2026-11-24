package cn.managame.example.aiservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.FrameworkErrorCodes;
import cn.managame.Metadata;
import cn.managame.ai.AiResult;
import cn.managame.data.GameData;
import cn.managame.example.DomainConstant;
import cn.managame.example.GameServerApp;
import cn.managame.example.NodeConstant;
import cn.managame.example.WorldServerApp;
import cn.managame.example.ai.AiConfig.McpEndpoint;
import cn.managame.example.ai.AiDtos.GmAskRes;
import cn.managame.example.ai.AiDtos.NpcAction;
import cn.managame.example.ai.AiDtos.NpcChatRes;
import cn.managame.example.ai.NpcAiService;
import cn.managame.example.ai.PlayerAiStateRepository;
import cn.managame.example.ai.PlayerBagService;
import cn.managame.rpc.RpcNode;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * 全生命周期端到端联调测试：同时启动两台真实的 {@code managame-example} 游戏服（区服 2 与区服 3）以及一台真实的
 * {@code managame-example-ai} Spring AI 服务（JDBC {@code ChatMemory} + 多区服 {@code GameServerMcpRegistry}），
 * 全程采用 {@code GameRuntime} + {@code Continuation} 回调（无 {@code CompletableFuture}）验证：
 * <ol>
 *   <li>MCP 独立 NPC/GM Token 鉴权、防低权限伪造 GM 身份与双 Token 零停机热轮换</li>
 *   <li>多区服 MCP 路由（区服 2 与区服 3 各自独立背包状态，NPC 与 GM 精准回调目标区服）与工具目录热刷新</li>
 *   <li>JDBC 持久化会话记忆、游戏服 {@code PlayerAiState} 持久化代号、跨玩家防越权、会话重置与删号双端清理</li>
 *   <li>SSE 流式对话、跨分片敏感词脱敏、玩家断线（{@code onPlayerDisconnect}）自动中断在途流</li>
 *   <li>单玩家每日配额保护、一键功能降级开关（退回脚本台词）与结构化决策超时降级</li>
 *   <li>GM 写工具 {@code opId} 幂等防重发、高额拦截，以及 {@code ai_audit_log} 审计与 Token 成本记账落库</li>
 * </ol>
 */
class GameServerWithSpringAiIntegrationTest {

    @Test void fullGameServerLifecycleAndSpringAiEndToEnd() throws Exception {
        int aiServicePort = pickFreePort();
        String npcToken = "npc-secret-v1";
        String gmToken = "gm-secret-v1";

        try (FakeOpenAiServer openAiStub = new FakeOpenAiServer();
             var worldCtx = startWorld();
             var game2Ctx = startGame(2, worldCtx.getBean(RpcNode.class).port(), aiServicePort, npcToken, gmToken);
             var game3Ctx = startGame(3, worldCtx.getBean(RpcNode.class).port(), aiServicePort, npcToken, gmToken)) {

            int mcpPort2 = game2Ctx.getBean(McpEndpoint.class).port();
            int mcpPort3 = game3Ctx.getBean(McpEndpoint.class).port();

            try (ConfigurableApplicationContext springAiCtx = startSpringAiService(
                    aiServicePort, openAiStub.port(), mcpPort2, mcpPort3, npcToken, gmToken)) {

                GameRuntime runtime2 = game2Ctx.getBean(GameRuntime.class);
                NpcAiService npcAi2 = game2Ctx.getBean(NpcAiService.class);
                PlayerBagService bags2 = game2Ctx.getBean(PlayerBagService.class);
                PlayerAiStateRepository stateRepo2 = game2Ctx.getBean(PlayerAiStateRepository.class);
                McpEndpoint mcpEndpoint2 = game2Ctx.getBean(McpEndpoint.class);

                GameRuntime runtime3 = game3Ctx.getBean(GameRuntime.class);
                NpcAiService npcAi3 = game3Ctx.getBean(NpcAiService.class);
                PlayerBagService bags3 = game3Ctx.getBean(PlayerBagService.class);

                ChatMemoryRepository jdbcMemory = springAiCtx.getBean(ChatMemoryRepository.class);
                HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

                // 1. 鉴权与密钥热轮换：未带 Token 返回 401；拿 NPC Token 伪造 X-Caller: gm 返回 401；双 Token 轮换窗口新旧均可用
                assertEquals(401, postMcpPing(http, mcpPort2, null, "npc"));
                assertEquals(401, postMcpPing(http, mcpPort2, npcToken, "gm"),
                        "NPC token must not be allowed to forge X-Caller: gm");
                assertEquals(200, postMcpPing(http, mcpPort2, gmToken, "gm"));

                mcpEndpoint2.authHandler().rotateTokens("npc-secret-v2," + npcToken, gmToken);
                assertEquals(200, postMcpPing(http, mcpPort2, "npc-secret-v2", "npc"));
                assertEquals(200, postMcpPing(http, mcpPort2, npcToken, "npc"));

                // 2. NPC 对话 + Spring AI 按 X-Game-Server-Id 回调区服 2 的 query_bag + Token 记账
                NpcChatRes bagReply2 = talkOnRoute(runtime2, npcAi2, 1001L, 7L, "铁匠老王", "check bag");
                assertTrue(bagReply2.reply().contains("500") && bagReply2.reply().contains("routeKey"),
                        "Expected bag gold 500 on server 2: " + bagReply2);
                assertTrue(bagReply2.totalTokens() > 0, "Expected token usage reported: " + bagReply2);

                // 3. JDBC ChatMemory 持久化验证 + 多玩家/多 NPC 隔离 + 跨玩家越权拦截
                NpcChatRes p1Npc7Turn2 = talkOnRoute(runtime2, npcAi2, 1001L, 7L, "铁匠老王", "remember my sword");
                assertTrue(p1Npc7Turn2.reply().contains("turns=2") && p1Npc7Turn2.reply().contains("check bag"));
                assertFalse(jdbcMemory.findByConversationId("npc:2:1001:7:1").isEmpty(),
                        "Conversation must be persisted in Spring AI JdbcChatMemoryRepository");

                NpcChatRes p2Npc7Turn1 = talkOnRoute(runtime2, npcAi2, 1002L, 7L, "铁匠老王", "hello blacksmith");
                assertTrue(p2Npc7Turn1.reply().contains("turns=1"));

                NpcChatRes stolen = talkOnRoute(runtime2, npcAi2, 1001L, 7L, "铁匠老王", "steal bag 1002");
                assertTrue(stolen.reply().contains("Cross-player access denied"));

                // 4. 会话重置（持久化 epoch 自增）与删号双端清理（清理本地 PlayerAiState + 远端 JDBC ChatMemory）
                resetOnRoute(runtime2, npcAi2, 1001L, 7L);
                assertEquals(2, stateRepo2.get(1001L).getNpcEpochs().get(7L));
                NpcChatRes afterReset = talkOnRoute(runtime2, npcAi2, 1001L, 7L, "铁匠老王", "fresh start");
                assertTrue(afterReset.reply().contains("turns=1"));

                assertFalse(jdbcMemory.findByConversationId("npc:2:1002:7:1").isEmpty());
                deleteRoleOnRoute(runtime2, npcAi2, 1002L);
                assertNull(stateRepo2.get(1002L), "Local PlayerAiState must be deleted on role deletion");
                assertTrue(jdbcMemory.findByConversationId("npc:2:1002:7:1").isEmpty(),
                        "Remote JDBC ChatMemory must be purged on role deletion");

                // 5. 内容安全过滤（单次敏感词脱敏 + SSE 跨分片敏感词脱敏）与玩家断线自动取消在途 SSE 流
                NpcChatRes dirtyChat = talkOnRoute(runtime2, npcAi2, 1001L, 7L, "铁匠老王", "dirty chat");
                assertEquals("别信**和**广告", dirtyChat.reply());

                assertEquals("欢迎来到铁匠铺", streamOnRoute(runtime2, npcAi2, 1001L, 7L, "welcome me"));
                assertEquals("小心**骗子", streamOnRoute(runtime2, npcAi2, 1001L, 7L, "dirty stream"));

                // 模拟玩家在慢速 SSE 流式生成途中掉线（onPlayerDisconnect） -> 自动取消流并回收在途槽位
                CountDownLatch firstChunk = new CountDownLatch(1);
                CountDownLatch disconnectDone = new CountDownLatch(1);
                AtomicReference<AiResult<Void>> disconnectResult = new AtomicReference<>();
                runtime2.execute(playerContext(1001L), ctx ->
                        npcAi2.streamTalk(7L, "铁匠老王", "slow stream",
                                t -> firstChunk.countDown(), res -> {
                                    disconnectResult.set(res);
                                    disconnectDone.countDown();
                                }));
                assertTrue(firstChunk.await(10, TimeUnit.SECONDS));
                assertEquals(1, npcAi2.activeCallCount(1001L));
                runtime2.handOn(playerContext(1001L), ctx -> npcAi2.onPlayerDisconnect(1001L));
                assertTrue(disconnectDone.await(10, TimeUnit.SECONDS));
                assertEquals(FrameworkErrorCodes.AI_CANCELLED, disconnectResult.get().code());
                assertEquals(0, npcAi2.activeCallCount(1001L));

                // 6. 单玩家每日额度限制 + 一键功能降级开关 + 结构化决策降级
                npcAi2.setDailyQuota(1);
                NpcChatRes q1 = talkOnRoute(runtime2, npcAi2, 1003L, 7L, "铁匠老王", "hello quota");
                assertTrue(q1.reply().contains("turns=1"));
                AiResult<NpcChatRes> q2 = talkRawOnRoute(runtime2, npcAi2, 1003L, 7L, "铁匠老王", "over quota");
                assertEquals(FrameworkErrorCodes.AI_OVERLOADED, q2.code());
                npcAi2.setDailyQuota(200);

                npcAi2.setAiEnabled(false);
                NpcChatRes degradedChat = talkOnRoute(runtime2, npcAi2, 1001L, 7L, "铁匠老王", "anybody home");
                assertTrue(degradedChat.reply().contains("铁匠老王"), "Disabled AI must return scripted fallback");
                assertEquals(NpcAction.FLEE, decideOnRoute(runtime2, npcAi2, 1001L, 7L, "merchant-offer",
                        List.of(NpcAction.TRADE, NpcAction.FLEE), NpcAction.FLEE, Duration.ofSeconds(5)));
                npcAi2.setAiEnabled(true);

                assertEquals(NpcAction.TRADE, decideOnRoute(runtime2, npcAi2, 1001L, 7L, "merchant-offer",
                        List.of(NpcAction.TRADE, NpcAction.FLEE), NpcAction.FLEE, Duration.ofSeconds(5)));
                assertEquals(NpcAction.FLEE, decideOnRoute(runtime2, npcAi2, 1001L, 7L, "bad-choice",
                        List.of(NpcAction.TRADE, NpcAction.FLEE), NpcAction.FLEE, Duration.ofSeconds(5)));
                assertEquals(NpcAction.FLEE, decideOnRoute(runtime2, npcAi2, 1001L, 7L, "slow-decide",
                        List.of(NpcAction.TRADE, NpcAction.FLEE), NpcAction.FLEE, Duration.ofMillis(150)));

                // 7. 多区服 GM 路由 + opId 幂等防重发 + 超额拦截 + 工具目录热刷新
                GmAskRes online2 = askGmSync(npcAi2, "online count", 2);
                assertTrue(online2.reply().contains("2"));

                // 对区服 2 用相同 opId ("gm-op-50") 连续执行两次 -> 幂等去重，金币只加一次（500 -> 550）
                GmAskRes grantedOnce = askGmSync(npcAi2, "grant 50 gold", 2);
                GmAskRes grantedRetry = askGmSync(npcAi2, "grant 50 gold", 2);
                assertTrue(grantedOnce.reply().contains("550"));
                assertTrue(grantedRetry.reply().contains("550"));
                assertEquals(550, bags2.goldOf(1001L), "Idempotent opId must prevent duplicate gold grant on server 2");
                assertEquals(500, bags3.goldOf(1001L), "Server 3 bag must stay untouched when GM targets server 2");

                // 对区服 3 发放 80 金币 -> 区服 3 变为 580，且区服 3 的 NPC 回查背包看到 580（验证多服路由完全隔离）
                GmAskRes grantedServer3 = askGmSync(npcAi3, "grant 80 gold", 3);
                assertTrue(grantedServer3.reply().contains("580"));
                assertEquals(580, bags3.goldOf(1001L));
                NpcChatRes bagReply3 = talkOnRoute(runtime3, npcAi3, 1001L, 7L, "铁匠老王", "check bag");
                assertTrue(bagReply3.reply().contains("580"), "Server 3 NPC must query server 3 MCP: " + bagReply3);

                // 超单次自动上限 100 -> 拦截并提示走人工审批
                GmAskRes limitErr = askGmSync(npcAi2, "grant 999 gold", 2);
                assertTrue(limitErr.reply().contains("manual GM approval"));
                assertEquals(550, bags2.goldOf(1001L));

                // 刷新双服缓存的 MCP 工具目录
                HttpResponse<String> refreshRes = http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + aiServicePort + "/api/ai/admin/mcp/refresh"))
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, refreshRes.statusCode());
                assertTrue(refreshRes.body().contains("2"));

                // 8. 刷盘并校验游戏服 2 的 ai_audit_log 表：同时留存 MCP_WRITE 审计、NPC_CHAT 与 GM_ASK Token 账单
                game2Ctx.getBean(GameData.class).flushLogs();
                Set<String> auditKinds = new HashSet<>();
                int mcpWriteRows = 0;
                try (Connection conn = game2Ctx.getBean(DataSource.class).getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT kind, name, code, tokens FROM ai_audit_log")) {
                    while (rs.next()) {
                        String kind = rs.getString("kind");
                        auditKinds.add(kind);
                        if ("MCP_WRITE".equals(kind) && "grant_item".equals(rs.getString("name"))) {
                            mcpWriteRows++;
                        }
                    }
                }
                assertTrue(auditKinds.containsAll(Set.of("MCP_WRITE", "NPC_CHAT", "GM_ASK")),
                        "Expected all audit kinds in ai_audit_log, got: " + auditKinds);
                assertEquals(3, mcpWriteRows, "Expected 3 audited grant_item calls (2 idempotent + 1 rejected)");
            }
        }
    }

    private static int postMcpPing(HttpClient http, int mcpPort, String bearer, String caller) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mcpPort + "/mcp"))
                .header("Content-Type", "application/json")
                .header("X-Caller", caller)
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));
        if (bearer != null) req.header("Authorization", "Bearer " + bearer);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static GameContext playerContext(long roleId) {
        Metadata md = Metadata.builder().put(NpcAiService.TRACE_ID, "trace-" + UUID.randomUUID()).build();
        return new GameContext(DomainConstant.PLAYER, roleId, (byte) 1, roleId, md);
    }

    private static AiResult<NpcChatRes> talkRawOnRoute(GameRuntime runtime, NpcAiService npcAi,
            long roleId, long npcId, String npcName, String text) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AiResult<NpcChatRes>> result = new AtomicReference<>();
        runtime.execute(playerContext(roleId), ctx -> npcAi.talk(npcId, npcName, text, res -> {
            assertEquals(DomainConstant.PLAYER, Context.current().domain());
            assertEquals(roleId, Context.current().routeKey());
            result.set(res);
            done.countDown();
        }));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        return result.get();
    }

    private static NpcChatRes talkOnRoute(GameRuntime runtime, NpcAiService npcAi,
            long roleId, long npcId, String npcName, String text) throws Exception {
        AiResult<NpcChatRes> res = talkRawOnRoute(runtime, npcAi, roleId, npcId, npcName, text);
        assertTrue(res.isSuccess(), "AI chat failed: " + res);
        return res.data();
    }

    private static String streamOnRoute(GameRuntime runtime, NpcAiService npcAi,
            long roleId, long npcId, String prompt) throws Exception {
        List<String> tokens = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AiResult<Void>> result = new AtomicReference<>();
        runtime.execute(playerContext(roleId), ctx ->
                npcAi.streamTalk(npcId, "铁匠老王", prompt, tokens::add, res -> {
                    assertEquals(DomainConstant.PLAYER, Context.current().domain());
                    assertEquals(roleId, Context.current().routeKey());
                    result.set(res);
                    latch.countDown();
                }));
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(FrameworkErrorCodes.SUCCESS, result.get().code());
        return String.join("", tokens);
    }

    private static void resetOnRoute(GameRuntime runtime, NpcAiService npcAi, long roleId, long npcId) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AiResult<Void>> result = new AtomicReference<>();
        runtime.execute(playerContext(roleId), ctx -> npcAi.resetDialogue(npcId, res -> {
            assertEquals(DomainConstant.PLAYER, Context.current().domain());
            assertEquals(roleId, Context.current().routeKey());
            result.set(res);
            done.countDown();
        }));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(result.get().isSuccess(), "Reset dialogue failed: " + result.get());
    }

    private static void deleteRoleOnRoute(GameRuntime runtime, NpcAiService npcAi, long roleId) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AiResult<Void>> result = new AtomicReference<>();
        runtime.execute(playerContext(roleId), ctx -> npcAi.onRoleDelete(roleId, res -> {
            assertEquals(DomainConstant.PLAYER, Context.current().domain());
            assertEquals(roleId, Context.current().routeKey());
            result.set(res);
            done.countDown();
        }));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(result.get().isSuccess(), "Delete role sessions failed: " + result.get());
    }

    private static NpcAction decideOnRoute(GameRuntime runtime, NpcAiService npcAi, long roleId, long npcId,
            String situation, List<NpcAction> allowed, NpcAction fallback, Duration timeout) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<NpcAction> chosen = new AtomicReference<>();
        runtime.execute(playerContext(roleId), ctx ->
                npcAi.decide(npcId, situation, allowed, fallback, timeout, action -> {
                    assertEquals(DomainConstant.PLAYER, Context.current().domain());
                    assertEquals(roleId, Context.current().routeKey());
                    chosen.set(action);
                    done.countDown();
                }));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        return chosen.get();
    }

    private static GmAskRes askGmSync(NpcAiService npcAi, String instruction, int targetServerId) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AiResult<GmAskRes>> result = new AtomicReference<>();
        npcAi.askGm(instruction, targetServerId, res -> {
            result.set(res);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(result.get().isSuccess(), "GM ask failed: " + result.get());
        return result.get().data();
    }

    private static int pickFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static AnnotationConfigApplicationContext startWorld() {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("world-test", Map.of(
                "rpc.node", NodeConstant.WORLD,
                "rpc.port", 0)));
        ctx.register(WorldServerApp.class);
        ctx.refresh();
        return ctx;
    }

    private static AnnotationConfigApplicationContext startGame(
            int nodeId, int worldRpcPort, int aiServicePort, String npcToken, String gmToken) throws Exception {
        String jdbcUrl = "jdbc:h2:mem:ai_example_" + nodeId + "_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (var conn = java.sql.DriverManager.getConnection(jdbcUrl, "sa", "test");
             var stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE `user` (`user_id` BIGINT NOT NULL, `role_id_map` MEDIUMTEXT NOT NULL, PRIMARY KEY (`user_id`))");
        }
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("game-test-" + nodeId, Map.of(
                "rpc.node", nodeId,
                "rpc.peers", NodeConstant.WORLD + "=127.0.0.1:" + worldRpcPort,
                "game.port", 0,
                "ai.mcp.port", 0,
                "ai.mcp.token", npcToken,
                "ai.mcp.gm-token", gmToken,
                "ai.service.url", "http://127.0.0.1:" + aiServicePort + "/api/ai",
                "mysql.url", jdbcUrl,
                "mysql.user", "sa",
                "mysql.password", "test")));
        ctx.register(GameServerApp.class);
        ctx.refresh();
        return ctx;
    }

    private static ConfigurableApplicationContext startSpringAiService(
            int aiServicePort, int openAiStubPort, int mcpPort2, int mcpPort3, String npcToken, String gmToken) {
        String aiDbUrl = "jdbc:h2:mem:spring_ai_mem_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        return SpringApplication.run(GameAiServiceApplication.class,
                "--server.port=" + aiServicePort,
                "--spring.datasource.url=" + aiDbUrl,
                "--spring.ai.openai.base-url=http://127.0.0.1:" + openAiStubPort,
                "--spring.ai.openai.api-key=test-key",
                "--spring.ai.openai.chat.options.model=demo-model",
                "--game.mcp.default-url=http://127.0.0.1:" + mcpPort2,
                "--game.mcp.servers.2=http://127.0.0.1:" + mcpPort2,
                "--game.mcp.servers.3=http://127.0.0.1:" + mcpPort3,
                "--game.mcp.token=" + npcToken,
                "--game.mcp.gm-token=" + gmToken);
    }
}
