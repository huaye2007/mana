package cn.managame.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.ai.annotation.AiTool;
import cn.managame.ai.http.AiToolHttpHandler;
import cn.managame.ai.spi.AiToolExecutor;
import cn.managame.network.http.HttpInitializer;
import cn.managame.network.netty.NetworkServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * End-to-end usage example: a game server calls an external AI service (such as a Spring AI service that owns LLM
 * inference, prompts, memory and MCP/tool orchestration) asynchronously via {@link AiClient}, while exposing
 * route-safe game server methods back to that AI service via {@link AiToolHttpHandler}.
 *
 * <p>Flow tested over real loopback sockets:
 * <ol>
 *   <li>Player 1001 talks to an NPC -> game server calls the external AI service via {@link AiClient#stream} without
 *       blocking Player 1001's serial route executor.</li>
 *   <li>Mid-inference, the external AI service calls the game server's {@code POST /ai/tools/query_bag} and
 *       {@code POST /ai/tools/grant_quest} endpoints with its service token.</li>
 *   <li>The game server runs both tools serially on Player 1001's own single-threaded route executor (zero deadlock)
 *       and updates game state.</li>
 *   <li>The external AI service streams the NPC's reply back via SSE to the game server.</li>
 * </ol>
 */
class GameServerAiExampleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record NpcTalkReq(long roleId, String npcId, String playerMessage) {}
    record QueryBag(long roleId, int itemId) {}
    record BagCount(long roleId, int itemId, int count) {}
    record GrantQuest(long roleId, int questId) {}
    record QuestGranted(long roleId, int questId, boolean accepted) {}

    static final class PlayerState {
        final Map<Integer, Integer> items = new ConcurrentHashMap<>();
        final List<Integer> quests = new CopyOnWriteArrayList<>();
    }

    static final class GameNpcTools {
        private final Map<Long, PlayerState> players;

        GameNpcTools(Map<Long, PlayerState> players) { this.players = players; }

        @AiTool(name = "query_bag", description = "Query how many of an item a player has", group = "npc", readOnly = true)
        BagCount queryBag(QueryBag req) {
            int count = players.computeIfAbsent(req.roleId(), _ -> new PlayerState()).items.getOrDefault(req.itemId(), 0);
            return new BagCount(req.roleId(), req.itemId(), count);
        }

        @AiTool(name = "grant_quest", description = "Grant a quest to a player", group = "npc")
        QuestGranted grantQuest(GrantQuest req) {
            PlayerState state = players.computeIfAbsent(req.roleId(), _ -> new PlayerState());
            state.quests.add(req.questId());
            return new QuestGranted(req.roleId(), req.questId(), true);
        }
    }

    @Test void gameServerAndExternalAiServiceCallEachOtherBidirectionally() throws Exception {
        Map<Long, PlayerState> players = new ConcurrentHashMap<>();
        players.computeIfAbsent(1001L, _ -> new PlayerState()).items.put(2001, 2);

        // Simulated per-player serial route (analogous to managame-runtime's (PLAYER, roleId) route)
        ExecutorService player1001Route = Executors.newSingleThreadExecutor();
        AiToolExecutor routeExecutor = (call, invocation) -> player1001Route.execute(invocation);

        AiToolInterceptor tokenAuth = new AiToolInterceptor() {
            @Override public boolean before(AiToolContext context, AiToolDefinition tool, Object argument) {
                return "Bearer ai-service-secret".equals(context.header("Authorization"))
                        && "npc".equals(tool.group());
            }
        };

        AiTools tools = AiTools.builder()
                .codec(new JacksonAiCodec())
                .executor(routeExecutor)
                .intercept(List.of(tokenAuth))
                .register(List.of(new GameNpcTools(players)))
                .build();

        HttpServer fakeSpringAiService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeSpringAiService.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        try (NetworkServer gameToolServer = NetworkServer.start(0,
                HttpInitializer.http().contextPath("/ai").handler(new AiToolHttpHandler(tools)).build());
             HttpClient aiSideHttp = HttpClient.newHttpClient();
             AiClient aiClient = AiClient.builder()
                     .baseUrl("http://127.0.0.1:" + fakeSpringAiService.getAddress().getPort())
                     .codec(new JacksonAiCodec())
                     .build()) {

            String gameToolsBase = "http://127.0.0.1:" + gameToolServer.port() + "/ai/tools/";

            // External Spring AI service: calls Game Server tools over plain HTTP, then streams SSE back
            fakeSpringAiService.createContext("/npc/talk/stream", exchange -> {
                String reqJson = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                long roleId = MAPPER.readTree(reqJson).get("roleId").asLong();

                try {
                    JsonNode bagResp = callGameTool(aiSideHttp, gameToolsBase + "query_bag",
                            new QueryBag(roleId, 2001));
                    int oreCount = bagResp.get("data").get("count").asInt();

                    if (oreCount < 5) {
                        callGameTool(aiSideHttp, gameToolsBase + "grant_quest", new GrantQuest(roleId, 101));
                    }

                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(("data: You only have " + oreCount + " iron ore.\n\n").getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        os.write("data: I have granted you Quest #101!\n\n".getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                } catch (Exception e) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                }
            });
            fakeSpringAiService.start();

            List<String> pushedToPlayer = new CopyOnWriteArrayList<>();
            CompletableFuture<Void> dialogueDone = new CompletableFuture<>();

            player1001Route.execute(() -> aiClient.stream(
                    "/npc/talk/stream",
                    new NpcTalkReq(1001L, "blacksmith", "Do I have enough iron ore?"),
                    new AiStreamListener() {
                        @Override public void onChunk(AiChunk chunk) {
                            player1001Route.execute(() -> pushedToPlayer.add(chunk.data()));
                        }

                        @Override public void onComplete() {
                            player1001Route.execute(() -> dialogueDone.complete(null));
                        }

                        @Override public void onError(int errorCode, String message) {
                            dialogueDone.completeExceptionally(new AiException(errorCode, message));
                        }
                    }));

            dialogueDone.get(5, TimeUnit.SECONDS);

            assertEquals(List.of(
                    "You only have 2 iron ore.",
                    "I have granted you Quest #101!"), pushedToPlayer);
            assertTrue(players.get(1001L).quests.contains(101));
        } finally {
            fakeSpringAiService.stop(0);
            player1001Route.shutdownNow();
        }
    }

    private static JsonNode callGameTool(HttpClient http, String url, Object payload) throws Exception {
        String body = MAPPER.writeValueAsString(payload);
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer ai-service-secret")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        return MAPPER.readTree(resp.body());
    }
}
