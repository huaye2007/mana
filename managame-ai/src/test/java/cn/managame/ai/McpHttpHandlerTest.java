package cn.managame.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.ai.annotation.McpParam;
import cn.managame.ai.annotation.McpTool;
import cn.managame.ai.internal.Json;
import cn.managame.ai.mcp.McpHttpHandler;
import cn.managame.ai.mcp.McpToolCall;
import cn.managame.ai.mcp.McpToolContext;
import cn.managame.ai.mcp.McpToolDefinition;
import cn.managame.ai.mcp.McpToolInterceptor;
import cn.managame.ai.mcp.McpToolResult;
import cn.managame.ai.mcp.McpTools;
import cn.managame.network.http.HttpInitializer;
import cn.managame.network.netty.NetworkServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link McpHttpHandler} over real loopback HTTP both at the raw JSON-RPC / Streamable HTTP layer and with
 * the official MCP Java SDK client ({@link McpClient} + {@link HttpClientStreamableHttpTransport}), plus a full
 * bidirectional game-server <-> external AI service flow.
 */
class McpHttpHandlerTest {
    private static final int ITEM_FORBIDDEN = 10_002;
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    record GrantReq(@McpParam("Player role id") long roleId, @McpParam("Item id") int itemId, @McpParam("Count") int count) {}
    record PlayerBag(long roleId, Map<Integer, Integer> items) {}
    record NpcAsk(long roleId, String text) {}
    record NpcAnswer(String reply) {}

    static class GameServerService {
        private final Map<Long, Map<Integer, Integer>> bags = new ConcurrentHashMap<>();

        GameServerService() {
            bags.put(1001L, new ConcurrentHashMap<>(Map.of(100, 3)));
        }

        @McpTool(name = "query_bag", description = "Query a player's bag items", group = "npc", readOnly = true)
        public PlayerBag queryBag(@McpParam("Player role id") long roleId) {
            return new PlayerBag(roleId, Map.copyOf(bags.getOrDefault(roleId, Map.of())));
        }

        @McpTool(name = "grant_item", description = "Grant an item to a player", group = "gm", idempotent = true)
        public McpToolResult grantItem(GrantReq req) {
            if (req.count() > 10) return McpToolResult.fail(ITEM_FORBIDDEN, "Count exceeds limit of 10");
            Map<Integer, Integer> bag = bags.computeIfAbsent(req.roleId(), id -> new ConcurrentHashMap<>());
            bag.merge(req.itemId(), req.count(), Integer::sum);
            return McpToolResult.ok(new PlayerBag(req.roleId(), Map.copyOf(bag)));
        }

        @McpTool(name = "announce", description = "Broadcast a server notice", group = "gm", destructive = true)
        public void announce(@McpParam("Notice text") String text) {
            // sync void action
        }

        @McpTool(name = "async_rank", description = "Query rank asynchronously", group = "npc", readOnly = true)
        public void asyncRank(McpToolContext ctx, @McpParam("Player role id") long roleId) {
            Thread.ofVirtual().start(() -> ctx.reply("Role " + roleId + " is #1"));
        }

        @McpTool(name = "dump_big", description = "Returns a huge payload", group = "gm", readOnly = true)
        public String dumpBig() {
            return "A".repeat(200);
        }
    }

    private NetworkServer gameMcpServer;
    private NetworkServer fakeAiServer;
    private AiClient aiClient;

    @AfterEach void tearDown() {
        if (aiClient != null) aiClient.close();
        if (fakeAiServer != null) fakeAiServer.close();
        if (gameMcpServer != null) gameMcpServer.close();
    }

    @Test void rawStreamableHttpConformance() throws Exception {
        AiClientTest.RecordingMetrics metrics = new AiClientTest.RecordingMetrics();
        McpTools tools = McpTools.builder().metrics(metrics).register(List.of(new GameServerService())).build();
        gameMcpServer = NetworkServer.start(new InetSocketAddress("127.0.0.1", 0),
                HttpInitializer.http().contextPath("/mcp")
                        .handler(McpHttpHandler.builder(tools)
                                .serverInfo("mana-game", "1.0")
                                .instructions("Game tools")
                                .maxResultChars(128)
                                .build())
                        .build());

        HttpResponse<String> getRes = send("GET", "/mcp", null);
        assertEquals(405, getRes.statusCode());
        assertEquals("POST", getRes.headers().firstValue("Allow").orElse(""));
        assertEquals(405, send("DELETE", "/mcp", null).statusCode());

        assertEquals(404, send("POST", "/mcp/extra", "{}").statusCode());

        HttpResponse<String> badJson = send("POST", "/mcp", "{broken");
        assertEquals(400, badJson.statusCode());
        assertTrue(badJson.body().contains("-32700"));

        HttpResponse<String> badRpc = send("POST", "/mcp", "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}");
        assertEquals(400, badRpc.statusCode());
        assertTrue(badRpc.body().contains("-32600"));

        HttpResponse<String> nullIdReq = send("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}");
        assertEquals(400, nullIdReq.statusCode());
        assertTrue(nullIdReq.body().contains("-32600"));

        HttpResponse<String> notif = send("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, notif.statusCode());

        HttpResponse<String> unknownMethod = send("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}");
        assertEquals(200, unknownMethod.statusCode());
        assertTrue(unknownMethod.body().contains("-32601"));

        HttpResponse<String> unknownTool = send("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ghost\",\"arguments\":{}}}");
        assertEquals(200, unknownTool.statusCode());
        assertTrue(unknownTool.body().contains("-32602"));

        HttpResponse<String> badArgs = send("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"query_bag\",\"arguments\":{}}}");
        assertEquals(200, badArgs.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) Json.parseObject(badArgs.body()).get("result");
        assertEquals(Boolean.TRUE, result.get("isError"));

        // initialize negotiates down cleanly even if client sends a future MCP-Protocol-Version header
        HttpRequest futureInit = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gameMcpServer.port() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "2099-01-01")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2099-01-01\"}}"))
                .build();
        HttpResponse<String> futureInitRes = HTTP.send(futureInit, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, futureInitRes.statusCode());
        assertTrue(futureInitRes.body().contains("2025-11-25"));

        HttpRequest supportedVer = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gameMcpServer.port() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}"))
                .build();
        assertEquals(200, HTTP.send(supportedVer, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpRequest unsupportedVer = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gameMcpServer.port() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", "1999-01-01")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"ping\"}"))
                .build();
        assertEquals(400, HTTP.send(unsupportedVer, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpResponse<String> oversizedRes = send("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"dump_big\",\"arguments\":{}}}");
        assertEquals(200, oversizedRes.statusCode());
        assertTrue(oversizedRes.body().contains("exceeded limit"));
        assertEquals(1L, metrics.counterValue("managame.ai.mcp.result_too_large", ""));
        assertEquals(1L, metrics.counterValue("managame.ai.mcp.failures", "tool=dump_big"));
        assertTrue(metrics.counterValue("managame.ai.mcp.protocol_errors", "") >= 4L);
        tools.close();
    }

    @Test void coHostedPathAndZeroReserializationForCustomCodec() throws Exception {
        AtomicReference<String> seenDecodeInput = new AtomicReference<>();
        AiCodec spyCodec = new AiCodec() {
            @Override public String encode(Object value) throws Exception { return AiCodec.basic().encode(value); }
            @Override public Object decode(String json, java.lang.reflect.Type type) throws Exception {
                seenDecodeInput.set(json);
                return AiCodec.basic().decode(json, type);
            }
        };
        try (McpTools tools = McpTools.builder().codec(spyCodec).register(List.of(new GameServerService())).build()) {
            McpHttpHandler mcpHandler = McpHttpHandler.builder(tools).path("/mcp").build();
            gameMcpServer = NetworkServer.start(new InetSocketAddress("127.0.0.1", 0),
                    HttpInitializer.http().handler(exchange -> {
                        if ("/health".equals(exchange.path())) {
                            exchange.respond(HttpResponseStatus.OK);
                        } else {
                            mcpHandler.handle(exchange);
                        }
                    }).build());

            assertEquals(200, send("GET", "/health", null).statusCode());
            String rawArgs = "{ \"roleId\" : 1001 , \"itemId\" : 100 , \"count\" : 1 }";
            HttpResponse<String> callRes = send("POST", "/mcp",
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"grant_item\",\"arguments\":" + rawArgs + "}}");
            assertEquals(200, callRes.statusCode());
            assertEquals(rawArgs, seenDecodeInput.get(), "Custom AiCodec must receive verbatim arguments JSON slice");
        }
    }

    @Test void officialMcpJavaSdkInteropAndBidirectionalFlow() throws Exception {
        McpToolInterceptor npcPolicy = new McpToolInterceptor() {
            @Override public boolean allowList(Map<String, String> headers, McpToolDefinition tool) {
                return !"npc".equals(headers.get("X-Caller")) || tool.readOnly();
            }
            @Override public boolean before(McpToolCall call) {
                return !"npc".equals(call.context().header("X-Caller")) || call.definition().readOnly();
            }
        };

        GameServerService service = new GameServerService();
        try (McpTools tools = McpTools.builder()
                .intercept(List.of(npcPolicy))
                .register(List.of(service))
                .build()) {

            gameMcpServer = NetworkServer.start(new InetSocketAddress("127.0.0.1", 0),
                    HttpInitializer.http().contextPath("/mcp")
                            .handler(McpHttpHandler.builder(tools).serverInfo("mana-game", "1.0.0").maxResultChars(128).build())
                            .build());

            String gameBase = "http://127.0.0.1:" + gameMcpServer.port();

            HttpClientStreamableHttpTransport gmTransport = HttpClientStreamableHttpTransport.builder(gameBase)
                    .endpoint("/mcp")
                    .requestBuilder(HttpRequest.newBuilder().header("X-Caller", "gm"))
                    .build();
            try (McpSyncClient gmClient = McpClient.sync(gmTransport).requestTimeout(Duration.ofSeconds(5)).build()) {
                McpSchema.InitializeResult init = gmClient.initialize();
                assertEquals("mana-game", init.serverInfo().name());
                assertNotNull(init.capabilities().tools());
                gmClient.ping();

                McpSchema.ListToolsResult listed = gmClient.listTools();
                assertEquals(5, listed.tools().size());
                McpSchema.Tool queryBagTool = listed.tools().stream()
                        .filter(t -> t.name().equals("query_bag")).findFirst().orElseThrow();
                assertEquals(Boolean.TRUE, queryBagTool.annotations().readOnlyHint());
                assertEquals(Boolean.FALSE, queryBagTool.annotations().openWorldHint());
                assertEquals(List.of("roleId"), queryBagTool.inputSchema().required());

                McpSchema.Tool grantTool = listed.tools().stream()
                        .filter(t -> t.name().equals("grant_item")).findFirst().orElseThrow();
                assertEquals(Boolean.FALSE, grantTool.annotations().destructiveHint());
                assertEquals(Boolean.TRUE, grantTool.annotations().idempotentHint());

                McpSchema.Tool announceTool = listed.tools().stream()
                        .filter(t -> t.name().equals("announce")).findFirst().orElseThrow();
                assertEquals(Boolean.TRUE, announceTool.annotations().destructiveHint());

                McpSchema.CallToolResult tooBig = gmClient.callTool(new McpSchema.CallToolRequest("dump_big", Map.of()));
                assertTrue(Boolean.TRUE.equals(tooBig.isError()));
                assertTrue(((McpSchema.TextContent) tooBig.content().getFirst()).text().contains("exceeded limit"));

                McpSchema.CallToolResult bagRes = gmClient.callTool(
                        new McpSchema.CallToolRequest("query_bag", Map.of("roleId", 1001)));
                assertFalse(Boolean.TRUE.equals(bagRes.isError()));
                assertTrue(((McpSchema.TextContent) bagRes.content().getFirst()).text().contains("\"100\":3"));

                McpSchema.CallToolResult granted = gmClient.callTool(
                        new McpSchema.CallToolRequest("grant_item", Map.of("roleId", 1001, "itemId", 100, "count", 2)));
                assertFalse(Boolean.TRUE.equals(granted.isError()));
                assertTrue(((McpSchema.TextContent) granted.content().getFirst()).text().contains("\"100\":5"));

                McpSchema.CallToolResult tooMany = gmClient.callTool(
                        new McpSchema.CallToolRequest("grant_item", Map.of("roleId", 1001, "itemId", 100, "count", 99)));
                assertTrue(Boolean.TRUE.equals(tooMany.isError()));
                assertTrue(((McpSchema.TextContent) tooMany.content().getFirst()).text().contains("limit of 10"));

                McpSchema.CallToolResult noticed = gmClient.callTool(
                        new McpSchema.CallToolRequest("announce", Map.of("text", "Boss spawned")));
                assertEquals("ok", ((McpSchema.TextContent) noticed.content().getFirst()).text());

                McpSchema.CallToolResult ranked = gmClient.callTool(
                        new McpSchema.CallToolRequest("async_rank", Map.of("roleId", 1001)));
                assertEquals("Role 1001 is #1", ((McpSchema.TextContent) ranked.content().getFirst()).text());

                assertThrows(McpError.class, () -> gmClient.callTool(new McpSchema.CallToolRequest("missing", Map.of())));
            }

            HttpClientStreamableHttpTransport npcTransport = HttpClientStreamableHttpTransport.builder(gameBase)
                    .endpoint("/mcp")
                    .requestBuilder(HttpRequest.newBuilder().header("X-Caller", "npc"))
                    .build();
            try (McpSyncClient npcClient = McpClient.sync(npcTransport).requestTimeout(Duration.ofSeconds(5)).build()) {
                npcClient.initialize();
                assertEquals(3, npcClient.listTools().tools().size());
                McpSchema.CallToolResult rejected = npcClient.callTool(
                        new McpSchema.CallToolRequest("grant_item", Map.of("roleId", 1001, "itemId", 100, "count", 1)));
                assertTrue(Boolean.TRUE.equals(rejected.isError()));
            }

            fakeAiServer = NetworkServer.start(new InetSocketAddress("127.0.0.1", 0),
                    HttpInitializer.http().handler(exchange -> {
                        String body = exchange.request().content().toString(StandardCharsets.UTF_8);
                        Thread.ofVirtual().start(() -> {
                            try {
                                NpcAsk ask = (NpcAsk) AiCodec.basic().decode(body, NpcAsk.class);
                                HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(gameBase)
                                        .endpoint("/mcp")
                                        .requestBuilder(HttpRequest.newBuilder().header("X-Caller", "npc"))
                                        .build();
                                String bagSummary;
                                try (McpSyncClient mcp = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
                                    mcp.initialize();
                                    McpSchema.CallToolResult toolRes = mcp.callTool(
                                            new McpSchema.CallToolRequest("query_bag", Map.of("roleId", ask.roleId())));
                                    bagSummary = ((McpSchema.TextContent) toolRes.content().getFirst()).text();
                                }
                                String replyJson = AiCodec.basic().encode(new NpcAnswer("You have: " + bagSummary));
                                FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                        Unpooled.copiedBuffer(replyJson, StandardCharsets.UTF_8));
                                res.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
                                exchange.respond(res);
                            } catch (Exception e) {
                                exchange.respond(HttpResponseStatus.INTERNAL_SERVER_ERROR);
                            }
                        });
                    }).build());

            aiClient = AiClient.builder("http://127.0.0.1:" + fakeAiServer.port()).build();
            CompletableFuture<AiResult<NpcAnswer>> answer = new CompletableFuture<>();
            aiClient.call("/npc/talk", new NpcAsk(1001L, "What is in my bag?"), NpcAnswer.class, answer::complete);
            AiResult<NpcAnswer> res = answer.get(5, TimeUnit.SECONDS);
            assertTrue(res.isSuccess());
            assertTrue(res.data().reply().contains("\"100\":5"));
        }
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gameMcpServer.port() + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
