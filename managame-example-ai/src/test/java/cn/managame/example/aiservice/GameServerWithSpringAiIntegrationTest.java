package cn.managame.example.aiservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.FrameworkErrorCodes;
import cn.managame.ai.AiCall;
import cn.managame.ai.AiResult;
import cn.managame.example.GameServerApp;
import cn.managame.example.NodeConstant;
import cn.managame.example.WorldServerApp;
import cn.managame.example.ai.AiConfig.McpEndpoint;
import cn.managame.example.ai.AiDtos.GmAskRes;
import cn.managame.example.ai.AiDtos.NpcAction;
import cn.managame.example.ai.AiDtos.NpcChatRes;
import cn.managame.example.ai.NpcAiService;
import cn.managame.example.ai.PlayerBagService;
import cn.managame.rpc.RpcNode;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * 端到端跨项目联调测试：同时启动真实的 {@code managame-example} 游戏服（{@link GameServerApp}）与真实的
 * {@code managame-example-ai} Spring AI 服务（{@link GameAiServiceApplication}），配合本地 OpenAI 兼容桩服务，
 * 验证以下 5 个真实游戏 AI 场景：
 * <ol>
 *   <li>MCP 端点 Bearer Token 鉴权（未授权 401）</li>
 *   <li>NPC 多轮对话 + 不同玩家/不同 NPC 会话隔离 + Spring AI 通过 MCP 回查游戏服玩家路由背包 + 提示词注入跨玩家拦截 + 会话重置</li>
 *   <li>NPC SSE 流式对话与中途 cancel 取消</li>
 *   <li>NPC 结构化选项决策（Spring AI Structured Output）+ 非法选项与超时规则降级</li>
 *   <li>GM 自然语言指令调用游戏服受控 MCP 写工具（{@code grant_item} / {@code online_count}，含 {@code HttpResult} 失败映射）</li>
 * </ol>
 */
class GameServerWithSpringAiIntegrationTest {

    @Test void fullGameServerAndSpringAiEndToEnd() throws Exception {
        int aiServicePort = pickFreePort();
        String mcpToken = "secret-mcp-token-123";

        try (FakeOpenAiServer openAiStub = new FakeOpenAiServer();
             var worldCtx = startWorld();
             var gameCtx = startGame(worldCtx.getBean(RpcNode.class).port(), aiServicePort, mcpToken)) {

            int mcpPort = gameCtx.getBean(McpEndpoint.class).port();

            try (ConfigurableApplicationContext springAiCtx = startSpringAiService(
                    aiServicePort, openAiStub.port(), mcpPort, mcpToken)) {

                NpcAiService npcAi = gameCtx.getBean(NpcAiService.class);
                PlayerBagService bags = gameCtx.getBean(PlayerBagService.class);

                // 1. Bearer Token 鉴权：未携带或错误 Token 访问游戏服 /mcp 返回 401
                HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
                HttpResponse<String> unauth = http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mcpPort + "/mcp"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(401, unauth.statusCode());

                // 2a. NPC 对话 + Spring AI 自动经 MCP 回调游戏服 query_bag（运行在 PLAYER 路由 1001 上）
                NpcChatRes bagReply = npcAi.talk(1001L, 7L, "铁匠老王", "check bag").get(10, TimeUnit.SECONDS);
                assertTrue(bagReply.reply().contains("500") && bagReply.reply().contains("gold"),
                        "Expected bag gold from GameServer MCP: " + bagReply);
                assertTrue(bagReply.reply().contains("routeKey") && bagReply.reply().contains("1001"),
                        "Expected execution on player route 1001: " + bagReply);

                // 2b. 不同玩家、不同 NPC 会话隔离（Spring AI ChatMemory 按 sessionId 隔离）
                NpcChatRes p1Npc7Turn2 = npcAi.talk(1001L, 7L, "铁匠老王", "remember my sword").get(10, TimeUnit.SECONDS);
                assertTrue(p1Npc7Turn2.reply().contains("turns=2"), "Player 1001 + NPC 7 should have 2 turns: " + p1Npc7Turn2);
                assertTrue(p1Npc7Turn2.reply().contains("check bag"), "History should contain turn 1: " + p1Npc7Turn2);

                NpcChatRes p2Npc7Turn1 = npcAi.talk(1002L, 7L, "铁匠老王", "hello blacksmith").get(10, TimeUnit.SECONDS);
                assertTrue(p2Npc7Turn1.reply().contains("turns=1"), "Player 1002 must have isolated memory: " + p2Npc7Turn1);

                NpcChatRes p1Npc8Turn1 = npcAi.talk(1001L, 8L, "药剂师", "hello alchemist").get(10, TimeUnit.SECONDS);
                assertTrue(p1Npc8Turn1.reply().contains("turns=1"), "NPC 8 must have isolated memory from NPC 7: " + p1Npc8Turn1);

                // 2c. 提示词注入跨玩家访问：玩家 1001 会话中大模型企图查 1002 背包 -> 游戏服 McpToolInterceptor 拒绝
                NpcChatRes stolen = npcAi.talk(1001L, 7L, "铁匠老王", "steal bag 1002").get(10, TimeUnit.SECONDS);
                assertTrue(stolen.reply().contains("Cross-player access denied"),
                        "Cross-player prompt injection must be rejected: " + stolen);

                // 2d. 重置对话：游戏服递增 epoch 并 DELETE 清理 Spring AI ChatMemory
                npcAi.resetDialogue(1001L, 7L).get(10, TimeUnit.SECONDS);
                NpcChatRes afterReset = npcAi.talk(1001L, 7L, "铁匠老王", "fresh start").get(10, TimeUnit.SECONDS);
                assertTrue(afterReset.reply().contains("turns=1"), "Session after reset must start fresh: " + afterReset);

                // 3a. NPC SSE 流式对话（Spring AI ChatClient.stream -> SseEmitter -> AiClient.stream）
                List<String> tokens = Collections.synchronizedList(new ArrayList<>());
                CompletableFuture<AiResult<Void>> streamDone = new CompletableFuture<>();
                npcAi.streamTalk(1001L, 7L, "铁匠老王", "welcome me", tokens::add, streamDone::complete);
                assertEquals(FrameworkErrorCodes.SUCCESS, streamDone.get(10, TimeUnit.SECONDS).code());
                assertEquals("欢迎来到铁匠铺", String.join("", tokens));

                // 3b. SSE 流式对话中途取消（模拟玩家下线/切走）
                CountDownLatch firstToken = new CountDownLatch(1);
                CompletableFuture<AiResult<Void>> cancelDone = new CompletableFuture<>();
                AiCall slowStream = npcAi.streamTalk(1001L, 7L, "铁匠老王", "slow stream",
                        t -> firstToken.countDown(), cancelDone::complete);
                assertTrue(firstToken.await(10, TimeUnit.SECONDS));
                assertTrue(slowStream.cancel());
                assertEquals(FrameworkErrorCodes.AI_CANCELLED, cancelDone.get(10, TimeUnit.SECONDS).code());

                // 4. NPC 结构化选项决策（Spring AI Structured Output）+ 非法选项与超时降级
                NpcAction validChoice = npcAi.decide(1001L, 7L, "merchant-offer",
                        List.of(NpcAction.TRADE, NpcAction.FLEE), NpcAction.FLEE, Duration.ofSeconds(5))
                        .get(10, TimeUnit.SECONDS);
                assertEquals(NpcAction.TRADE, validChoice);

                NpcAction disallowedFallback = npcAi.decide(1001L, 7L, "bad-choice",
                        List.of(NpcAction.TRADE, NpcAction.FLEE), NpcAction.FLEE, Duration.ofSeconds(5))
                        .get(10, TimeUnit.SECONDS);
                assertEquals(NpcAction.FLEE, disallowedFallback);

                NpcAction timeoutFallback = npcAi.decide(1001L, 7L, "slow-decide",
                        List.of(NpcAction.TRADE, NpcAction.FLEE), NpcAction.FLEE, Duration.ofMillis(150))
                        .get(10, TimeUnit.SECONDS);
                assertEquals(NpcAction.FLEE, timeoutFallback);

                // 5. GM 自然语言助手（X-Caller=gm，可调用 online_count 与 grant_item，含 HttpResult 失败映射）
                GmAskRes online = npcAi.askGm("online count").get(10, TimeUnit.SECONDS);
                assertTrue(online.reply().contains("2"), "Expected online count 2: " + online);

                GmAskRes granted = npcAi.askGm("grant 50 gold").get(10, TimeUnit.SECONDS);
                assertTrue(granted.reply().contains("550"), "Expected updated gold 550: " + granted);
                assertEquals(550, bags.goldOf(1001L));

                GmAskRes limitErr = npcAi.askGm("grant 999 gold").get(10, TimeUnit.SECONDS);
                assertTrue(limitErr.reply().contains("between 1 and 100"),
                        "Expected HttpResult.fail message propagated via MCP isError: " + limitErr);
                assertEquals(550, bags.goldOf(1001L));
            }
        }
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

    private static AnnotationConfigApplicationContext startGame(int worldRpcPort, int aiServicePort, String mcpToken) throws Exception {
        String jdbcUrl = "jdbc:h2:mem:ai_example_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (var conn = java.sql.DriverManager.getConnection(jdbcUrl, "sa", "test");
             var stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE `user` (`user_id` BIGINT NOT NULL, `role_id_map` MEDIUMTEXT NOT NULL, PRIMARY KEY (`user_id`))");
        }
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("game-test", Map.of(
                "rpc.node", 2,
                "rpc.peers", NodeConstant.WORLD + "=127.0.0.1:" + worldRpcPort,
                "game.port", 0,
                "ai.mcp.port", 0,
                "ai.mcp.token", mcpToken,
                "ai.service.url", "http://127.0.0.1:" + aiServicePort + "/api/ai",
                "mysql.url", jdbcUrl,
                "mysql.user", "sa",
                "mysql.password", "test")));
        ctx.register(GameServerApp.class);
        ctx.refresh();
        return ctx;
    }

    private static ConfigurableApplicationContext startSpringAiService(
            int aiServicePort, int openAiStubPort, int mcpPort, String mcpToken) {
        return SpringApplication.run(GameAiServiceApplication.class,
                "--server.port=" + aiServicePort,
                "--spring.ai.openai.base-url=http://127.0.0.1:" + openAiStubPort,
                "--spring.ai.openai.api-key=test-key",
                "--spring.ai.openai.chat.options.model=demo-model",
                "--spring.ai.mcp.client.streamable-http.connections.game.url=http://127.0.0.1:" + mcpPort,
                "--spring.ai.mcp.client.streamable-http.connections.game.endpoint=/mcp",
                "--game.mcp.token=" + mcpToken);
    }
}
