package cn.managame.ai;

import static cn.managame.FrameworkErrorCodes.AI_TIMEOUT;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_EXECUTION_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_INVALID_ARGS;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_REJECTED;
import static cn.managame.FrameworkErrorCodes.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.ai.annotation.McpParam;
import cn.managame.ai.annotation.McpTool;
import cn.managame.ai.internal.Json;
import cn.managame.ai.mcp.McpToolCall;
import cn.managame.ai.mcp.McpToolContext;
import cn.managame.ai.mcp.McpToolDefinition;
import cn.managame.ai.mcp.McpToolInterceptor;
import cn.managame.ai.mcp.McpToolResult;
import cn.managame.ai.mcp.McpTools;
import cn.managame.ai.spi.McpToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class McpToolsTest {
    private static final int BAG_FULL = 10_001;

    enum MailKind { SYSTEM, REWARD }

    record SendMail(
            @McpParam("Player role id") long roleId,
            @McpParam("Mail title") String title,
            @McpParam(value = "Mail kind", required = false) MailKind kind) {}

    record BagView(long roleId, String category, int count) {}

    static class MutablePojo {
        @McpParam("Target id") public long id;
        public String label;
    }

    static class GameTools {
        final List<Long> kicked = new ArrayList<>();

        @McpTool(name = "online_count", description = "Count online players", group = "gm", readOnly = true)
        public int onlineCount() { return 42; }

        @McpTool(name = "query_bag", description = "Query a player's bag", group = "npc", readOnly = true)
        public BagView queryBag(
                @McpParam("Player role id") long roleId,
                @McpParam(value = "Category filter", required = false) String category,
                Optional<Integer> limit) {
            return new BagView(roleId, category == null ? "all" : category, limit.orElse(10));
        }

        @McpTool(name = "send_mail", description = "Send a mail to a player", group = "gm")
        public McpToolResult sendMail(SendMail mail) {
            if (mail.title().equals("full")) return McpToolResult.fail(BAG_FULL, "Mailbox is full");
            return McpToolResult.ok("sent:" + mail.roleId() + ":" + mail.title() + ":" + mail.kind());
        }

        @McpTool(name = "kick_player", description = "Kick a player offline", group = "gm")
        public void kickPlayer(@McpParam("Player role id") long roleId) {
            kicked.add(roleId);
        }

        @McpTool(name = "query_rank", description = "Query player rank asynchronously", group = "npc", readOnly = true)
        public void queryRank(McpToolContext context, @McpParam("Player role id") long roleId) {
            String caller = context.header("x-agent");
            Thread.ofVirtual().start(() -> context.reply("rank#" + roleId + "@1(" + caller + ")"));
        }

        @McpTool(name = "returns_null", description = "Buggy tool returning null")
        public String returnsNull() { return null; }

        @McpTool(name = "throws_boom", description = "Buggy tool throwing exception")
        public String throwsBoom() { throw new IllegalStateException("boom"); }
    }

    @Test void schemasAndAllInvocationShapes() throws Exception {
        GameTools target = new GameTools();
        AiClientTest.RecordingMetrics metrics = new AiClientTest.RecordingMetrics();
        McpTools tools = McpTools.builder()
                .executor(McpToolExecutor.direct())
                .metrics(metrics)
                .register(List.of(target))
                .register(new McpToolDefinition("ping_tool", "Programmatic ping", "ops", true,
                                "{\"type\":\"object\",\"properties\":{\"msg\":{\"type\":\"string\"}}}"),
                        (ctx, args) -> ctx.reply("pong:" + args.get("msg")))
                .build();

        // Check generated JSON schemas
        Map<String, McpToolDefinition> byName = new java.util.HashMap<>();
        for (McpToolDefinition def : tools.definitions()) byName.put(def.name(), def);

        Map<String, Object> bagSchema = Json.parseObject(byName.get("query_bag").inputSchema());
        assertEquals("object", bagSchema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> bagProps = (Map<String, Map<String, Object>>) bagSchema.get("properties");
        assertEquals("integer", bagProps.get("roleId").get("type"));
        assertEquals("Player role id", bagProps.get("roleId").get("description"));
        assertEquals("string", bagProps.get("category").get("type"));
        assertEquals("integer", bagProps.get("limit").get("type"));
        assertEquals(List.of("roleId"), bagSchema.get("required"));

        Map<String, Object> mailSchema = Json.parseObject(byName.get("send_mail").inputSchema());
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> mailProps = (Map<String, Map<String, Object>>) mailSchema.get("properties");
        assertEquals(List.of("SYSTEM", "REWARD"), mailProps.get("kind").get("enum"));
        assertEquals(List.of("roleId", "title"), mailSchema.get("required"));

        // 0-arg
        assertEquals(42, invokeSync(tools, "online_count", "{}").data());

        // Multi-param with optional omitted and present
        assertEquals(new BagView(1001L, "all", 10), invokeSync(tools, "query_bag", "{\"roleId\":1001}").data());
        assertEquals(new BagView(1001L, "weapon", 5),
                invokeSync(tools, "query_bag", "{\"roleId\":1001,\"category\":\"weapon\",\"limit\":5}").data());

        // Missing required param -> AI_TOOL_INVALID_ARGS
        assertEquals(AI_TOOL_INVALID_ARGS, invokeSync(tools, "query_bag", "{}").code());
        assertEquals(AI_TOOL_INVALID_ARGS, invokeSync(tools, "query_bag", "not-json").code());

        // Single DTO + domain failure code
        assertEquals("sent:1001:Hi:REWARD",
                invokeSync(tools, "send_mail", "{\"roleId\":1001,\"title\":\"Hi\",\"kind\":\"REWARD\"}").data());
        McpToolResult domainFail = invokeSync(tools, "send_mail", "{\"roleId\":1001,\"title\":\"full\"}");
        assertEquals(BAG_FULL, domainFail.code());
        assertEquals("Mailbox is full", domainFail.message());

        // Sync void without McpToolContext
        assertEquals("ok", invokeSync(tools, "kick_player", "{\"roleId\":88}").data());
        assertEquals(List.of(88L), target.kicked);

        // Async void with McpToolContext
        CompletableFuture<McpToolResult> async = new CompletableFuture<>();
        tools.call("query_rank", "{\"roleId\":99}", Map.of("X-Agent", "npc-ai"), null, async::complete);
        assertEquals("rank#99@1(npc-ai)", async.get(5, TimeUnit.SECONDS).data());

        // Programmatic handler
        assertEquals("pong:hello", invokeSync(tools, "ping_tool", "{\"msg\":\"hello\"}").data());

        // Errors: not found, null return, exception
        assertEquals(AI_TOOL_NOT_FOUND, invokeSync(tools, "no_such_tool", "{}").code());
        assertEquals(AI_TOOL_EXECUTION_ERROR, invokeSync(tools, "returns_null", "{}").code());
        assertEquals(AI_TOOL_EXECUTION_ERROR, invokeSync(tools, "throws_boom", "{}").code());

        assertEquals(1L, metrics.counterValue("managame.ai.mcp.not_found", ""));
        assertEquals(2L, metrics.counterValue("managame.ai.mcp.invalid_args", ""));
        assertEquals(1L, metrics.counterValue("managame.ai.mcp.calls", "tool=online_count"));
    }

    @Test void interceptorsFilterAuthorizeAndAuditInOrder() throws Exception {
        List<String> trace = new ArrayList<>();
        McpToolInterceptor auth = new McpToolInterceptor() {
            @Override public boolean allowList(Map<String, String> headers, McpToolDefinition tool) {
                return !"npc".equals(headers.get("X-Role")) || tool.readOnly();
            }
            @Override public boolean before(McpToolCall call) {
                trace.add("auth:before");
                if ("npc".equals(call.context().header("X-Role")) && !call.definition().readOnly()) {
                    return false;
                }
                return true;
            }
            @Override public void after(McpToolCall call, McpToolResult result) {
                trace.add("auth:after:" + result.code());
            }
        };
        McpToolInterceptor audit = new McpToolInterceptor() {
            @Override public boolean before(McpToolCall call) {
                trace.add("audit:before");
                return true;
            }
            @Override public void after(McpToolCall call, McpToolResult result) {
                trace.add("audit:after:" + result.code());
            }
        };

        McpTools tools = McpTools.builder()
                .executor(McpToolExecutor.direct())
                .intercept(List.of(auth, audit))
                .register(List.of(new GameTools()))
                .build();

        // NPC sees only readOnly tools
        List<McpToolDefinition> npcVisible = tools.list(Map.of("x-role", "npc"));
        assertTrue(npcVisible.stream().allMatch(McpToolDefinition::readOnly));
        assertFalse(npcVisible.stream().anyMatch(d -> d.name().equals("kick_player")));

        // Allowed call: before in order, after in reverse order
        CompletableFuture<McpToolResult> ok = new CompletableFuture<>();
        tools.call("online_count", "{}", Map.of("X-Role", "npc"), null, ok::complete);
        assertEquals(SUCCESS, ok.get(5, TimeUnit.SECONDS).code());
        assertEquals(List.of("auth:before", "audit:before", "audit:after:0", "auth:after:0"), trace);

        // Refused by auth: audit never enters, neither after runs
        trace.clear();
        CompletableFuture<McpToolResult> refused = new CompletableFuture<>();
        tools.call("kick_player", "{\"roleId\":1}", Map.of("X-Role", "npc"), null, refused::complete);
        assertEquals(AI_TOOL_REJECTED, refused.get(5, TimeUnit.SECONDS).code());
        assertEquals(List.of("auth:before"), trace);
    }

    @Test void pojoRequiresCustomCodecAndValidationFailsEagerly() throws Exception {
        class PojoTools {
            @McpTool(description = "Uses mutable POJO")
            public String usePojo(MutablePojo pojo) { return pojo.id + ":" + pojo.label; }
        }
        // Fails eagerly under basic codec
        assertThrows(IllegalArgumentException.class,
                () -> McpTools.builder().register(List.of(new PojoTools())).build());

        // Succeeds with Jackson codec
        ObjectMapper mapper = new ObjectMapper();
        AiCodec jackson = new AiCodec() {
            @Override public String encode(Object v) throws Exception { return mapper.writeValueAsString(v); }
            @Override public Object decode(String j, Type t) throws Exception {
                return mapper.readValue(j, mapper.constructType(t));
            }
        };
        McpTools tools = McpTools.builder()
                .codec(jackson)
                .executor(McpToolExecutor.direct())
                .register(List.of(new PojoTools()))
                .build();
        assertEquals("7:ok", invokeSync(tools, "usePojo", "{\"id\":7,\"label\":\"ok\"}").data());

        // Duplicate tool names rejected
        assertThrows(IllegalArgumentException.class,
                () -> McpTools.builder().register(List.of(new GameTools(), new GameTools())).build());

        // Context in non-first position rejected
        class BadCtx {
            @McpTool(description = "bad")
            public void bad(long id, McpToolContext ctx) {}
        }
        assertThrows(IllegalArgumentException.class, () -> McpTools.builder().register(List.of(new BadCtx())));

        // Executor sees method and decoded singleArgument for routing
        AtomicReference<McpToolCall> captured = new AtomicReference<>();
        McpTools routed = McpTools.builder()
                .executor((call, task) -> { captured.set(call); task.run(); })
                .register(List.of(new GameTools()))
                .build();
        invokeSync(routed, "send_mail", "{\"roleId\":55,\"title\":\"hi\"}");
        assertNotNull(captured.get().method());
        assertEquals(55L, ((SendMail) captured.get().singleArgument()).roleId());
        assertEquals(55L, captured.get().rawArguments().get("roleId"));

        // Inherited and overridden @McpTool methods are deduplicated
        class BaseService {
            @McpTool(description = "base tool", readOnly = true)
            public String info() { return "base"; }
        }
        class SubService extends BaseService {
            @Override
            @McpTool(description = "sub tool", readOnly = true)
            public String info() { return "sub"; }
        }
        McpTools inherited = McpTools.builder().executor(McpToolExecutor.direct()).register(List.of(new SubService())).build();
        assertEquals(1, inherited.definitions().size());
        assertEquals("sub", invokeSync(inherited, "info", "{}").data());
    }

    @Test void toolTimeoutAndResultAdapter() throws Exception {
        record AppResult(int code, Object data, String msg) {}

        class AppService {
            @McpTool(name = "sync_app", description = "Returns app result type")
            public AppResult syncApp(@McpParam("ok flag") boolean ok) {
                return ok ? new AppResult(0, "done", "") : new AppResult(BAG_FULL, null, "not enough space");
            }

            @McpTool(name = "async_app", description = "Replies asynchronously with app result type")
            public void asyncApp(McpToolContext ctx) {
                Thread.ofVirtual().start(() -> ctx.reply(new AppResult(BAG_FULL, null, "async fail")));
            }

            @McpTool(name = "never_replies", description = "Async tool that forgets to answer")
            public void neverReplies(McpToolContext ctx) {
                // intentionally forgets to call ctx.reply / ctx.fail
            }
        }

        try (McpTools tools = McpTools.builder()
                .timeout(Duration.ofMillis(120))
                .resultAdapter(res -> res instanceof AppResult r
                        ? (r.code() == 0 ? McpToolResult.ok(r.data()) : McpToolResult.fail(r.code(), r.msg()))
                        : null)
                .register(List.of(new AppService()))
                .build()) {

            McpToolResult ok = invokeSync(tools, "sync_app", "{\"ok\":true}");
            assertTrue(ok.isSuccess());
            assertEquals("done", ok.data());

            McpToolResult syncFail = invokeSync(tools, "sync_app", "{\"ok\":false}");
            assertFalse(syncFail.isSuccess());
            assertEquals(BAG_FULL, syncFail.code());
            assertEquals("not enough space", syncFail.message());

            McpToolResult asyncFail = invokeSync(tools, "async_app", "{}");
            assertFalse(asyncFail.isSuccess());
            assertEquals(BAG_FULL, asyncFail.code());
            assertEquals("async fail", asyncFail.message());

            McpToolResult timedOut = invokeSync(tools, "never_replies", "{}");
            assertEquals(AI_TIMEOUT, timedOut.code());
        }
    }

    private static McpToolResult invokeSync(McpTools tools, String name, String argsJson) throws Exception {
        CompletableFuture<McpToolResult> f = new CompletableFuture<>();
        tools.call(name, argsJson, f::complete);
        return f.get(5, TimeUnit.SECONDS);
    }
}
