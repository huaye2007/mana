package cn.managame.ai;

import static cn.managame.FrameworkErrorCodes.AI_TOOL_EXECUTION_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_INVALID_ARGS;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_REJECTED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.managame.ai.annotation.AiTool;
import cn.managame.ai.http.AiToolHttpHandler;
import cn.managame.network.http.HttpInitializer;
import cn.managame.network.netty.NetworkServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class AiToolHttpHandlerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record GiveGold(long roleId, int amount) {}
    record Balance(long roleId, int gold) {}

    static class AdminTools {
        @AiTool(name = "server_time", description = "Return server timestamp", group = "npc", readOnly = true)
        long serverTime() { return 1700000000L; }

        @AiTool(name = "give_gold", description = "Grant gold to a player", group = "gm")
        AiToolResult giveGold(GiveGold req) {
            if (req.amount() <= 0) return AiToolResult.fail(20001, "amount must be positive");
            return AiToolResult.ok(new Balance(req.roleId(), req.amount()));
        }

        @AiTool(name = "async_notice", description = "Broadcast notice asynchronously", group = "gm")
        void asyncNotice(AiToolContext context, GiveGold req) {
            CompletableFuture.runAsync(() -> context.reply("notified:" + req.roleId()));
        }

        @AiTool(name = "broken", description = "Throws", group = "gm")
        String broken() { throw new IllegalStateException("bug"); }
    }

    @Test void listsFiltersAndInvokesToolsOverHttp() throws Exception {
        AiToolInterceptor auth = new AiToolInterceptor() {
            @Override public boolean allowList(Map<String, String> headers, AiToolDefinition tool) {
                return !"npc-only".equals(headers.get("Authorization")) || "npc".equals(tool.group());
            }

            @Override public boolean before(AiToolContext context, AiToolDefinition tool, Object argument) {
                if ("npc-only".equals(context.header("Authorization")) && !"npc".equals(tool.group())) {
                    context.fail(AI_TOOL_REJECTED, "gm tool denied");
                    return false;
                }
                return true;
            }
        };

        AiTools tools = AiTools.builder()
                .codec(new JacksonAiCodec())
                .intercept(List.of(auth))
                .errorHandler((ctx, err) -> {})
                .register(List.of(new AdminTools()))
                .build();

        try (NetworkServer server = NetworkServer.start(0,
                HttpInitializer.http().contextPath("/ai").handler(new AiToolHttpHandler(tools)).build());
             HttpClient http = HttpClient.newHttpClient()) {
            String base = "http://127.0.0.1:" + server.port() + "/ai";

            // GET /tools: full vs filtered by Authorization header
            HttpResponse<String> allResp = get(http, base + "/tools", "gm-admin");
            assertEquals(200, allResp.statusCode());
            assertEquals(4, MAPPER.readTree(allResp.body()).get("tools").size());

            HttpResponse<String> npcResp = get(http, base + "/tools", "npc-only");
            assertEquals(200, npcResp.statusCode());
            JsonNode npcList = MAPPER.readTree(npcResp.body()).get("tools");
            assertEquals(1, npcList.size());
            assertEquals("server_time", npcList.get(0).get("name").asText());
            assertEquals(true, npcList.get(0).get("readOnly").asBoolean());

            // POST /tools/server_time (zero-arg)
            HttpResponse<String> timeResp = post(http, base + "/tools/server_time", "npc-only", "");
            assertEquals(200, timeResp.statusCode());
            assertEquals(1700000000L, MAPPER.readTree(timeResp.body()).get("data").asLong());

            // POST /tools/give_gold (DTO success)
            HttpResponse<String> okResp = post(http, base + "/tools/give_gold", "gm-admin", "{\"roleId\":9,\"amount\":100}");
            assertEquals(200, okResp.statusCode());
            JsonNode okJson = MAPPER.readTree(okResp.body());
            assertEquals(0, okJson.get("code").asInt());
            assertEquals(100, okJson.get("data").get("gold").asInt());

            // POST /tools/async_notice (async void tool)
            HttpResponse<String> asyncResp = post(http, base + "/tools/async_notice", "gm-admin", "{\"roleId\":77,\"amount\":1}");
            assertEquals(200, asyncResp.statusCode());
            assertEquals("notified:77", MAPPER.readTree(asyncResp.body()).get("data").asText());

            // Business failure -> 200 with business code
            HttpResponse<String> bizResp = post(http, base + "/tools/give_gold", "gm-admin", "{\"roleId\":9,\"amount\":0}");
            assertEquals(200, bizResp.statusCode());
            assertEquals(20001, MAPPER.readTree(bizResp.body()).get("code").asInt());

            // Rejected by interceptor -> 403
            HttpResponse<String> denied = post(http, base + "/tools/give_gold", "npc-only", "{\"roleId\":9,\"amount\":5}");
            assertEquals(403, denied.statusCode());
            assertEquals(AI_TOOL_REJECTED, MAPPER.readTree(denied.body()).get("code").asInt());

            // Unknown tool -> 404; invalid args -> 400; tool bug -> 500; wrong method -> 405
            HttpResponse<String> missing = post(http, base + "/tools/unknown", "gm-admin", "{}");
            assertEquals(404, missing.statusCode());
            assertEquals(AI_TOOL_NOT_FOUND, MAPPER.readTree(missing.body()).get("code").asInt());

            HttpResponse<String> badArgs = post(http, base + "/tools/give_gold", "gm-admin", "{bad");
            assertEquals(400, badArgs.statusCode());
            assertEquals(AI_TOOL_INVALID_ARGS, MAPPER.readTree(badArgs.body()).get("code").asInt());

            HttpResponse<String> bug = post(http, base + "/tools/broken", "gm-admin", "{}");
            assertEquals(500, bug.statusCode());
            assertEquals(AI_TOOL_EXECUTION_ERROR, MAPPER.readTree(bug.body()).get("code").asInt());

            HttpResponse<String> wrongMethod = get(http, base + "/tools/give_gold", "gm-admin");
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("POST", wrongMethod.headers().firstValue("Allow").orElse(""));
        }
    }

    private static HttpResponse<String> get(HttpClient http, String url, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (auth != null) b.header("Authorization", auth);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient http, String url, String auth, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth != null) b.header("Authorization", auth);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
