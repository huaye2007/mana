package cn.managame.ai;

import static cn.managame.FrameworkErrorCodes.AI_CLOSED;
import static cn.managame.FrameworkErrorCodes.AI_OVERLOADED;
import static cn.managame.FrameworkErrorCodes.AI_TIMEOUT;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_EXECUTION_ERROR;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_INVALID_ARGS;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_NOT_FOUND;
import static cn.managame.FrameworkErrorCodes.AI_TOOL_REJECTED;
import static cn.managame.FrameworkErrorCodes.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.MetadataKey;
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
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class McpToolsTest {
    private static final int BAG_FULL = 10_001;
    private static final MetadataKey<String> CALLER_ID = MetadataKey.stringKey(7);

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

    static class FieldDto {
        public Long targetRole;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AiCodec JACKSON_CODEC = new AiCodec() {
        @Override public String encode(Object v) throws Exception { return MAPPER.writeValueAsString(v); }
        @Override public Object decode(String j, Type t) throws Exception {
            return MAPPER.readValue(j, MAPPER.constructType(t));
        }
    };

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

        @McpTool(name = "kick_player", description = "Kick a player offline", group = "gm", destructive = true)
        public void kickPlayer(@McpParam("Player role id") long roleId) {
            kicked.add(roleId);
        }

        @McpTool(name = "query_rank", description = "Query player rank asynchronously", group = "npc", readOnly = true)
        public void queryRank(McpToolContext context, @McpParam("Player role id") long roleId) {
            String caller = context.header("x-agent");
            Thread.ofVirtual().name("simulated-rpc-io").start(
                    () -> context.reply("rank#" + roleId + "@1(" + caller + ")"));
        }

        @McpTool(name = "returns_null", description = "Buggy tool returning null")
        public String returnsNull() { return null; }

        @McpTool(name = "throws_boom", description = "Buggy tool throwing exception")
        public String throwsBoom() { throw new IllegalStateException("boom"); }
    }

    @Test void schemasInvocationShapesPrepareHookAndDistinctFailureMetrics() throws Exception {
        GameTools target = new GameTools();
        AiClientTest.RecordingMetrics metrics = new AiClientTest.RecordingMetrics();
        List<String> preparedTools = new ArrayList<>();
        McpToolExecutor customExec = new McpToolExecutor() {
            @Override public void prepare(McpToolDefinition definition, Method method) {
                preparedTools.add(definition.name());
            }
            @Override public void execute(McpToolCall call, Runnable task) { task.run(); }
            @Override public void complete(McpToolCall call, Runnable completion) { completion.run(); }
        };

        try (McpTools tools = McpTools.builder()
                .executor(customExec)
                .metrics(metrics)
                .register(List.of(target))
                .register(new McpToolDefinition("ping_tool", "Programmatic ping", "ops", true,
                                "{\"type\":\"object\",\"properties\":{\"msg\":{\"type\":\"string\"}}}"),
                        (ctx, args) -> ctx.reply("pong:" + args.get("msg")))
                .build()) {

            assertEquals(8, preparedTools.size());

            Map<String, McpToolDefinition> byName = new java.util.HashMap<>();
            for (McpToolDefinition def : tools.definitions()) byName.put(def.name(), def);

            assertTrue(byName.get("kick_player").destructive());
            assertFalse(byName.get("query_bag").destructive());

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

            assertEquals(42, invokeSync(tools, "online_count", "{}").data());
            assertEquals(new BagView(1001L, "all", 10), invokeSync(tools, "query_bag", "{\"roleId\":1001}").data());
            assertEquals(new BagView(1001L, "weapon", 5),
                    invokeSync(tools, "query_bag", "{\"roleId\":1001,\"category\":\"weapon\",\"limit\":5}").data());

            assertEquals(AI_TOOL_INVALID_ARGS, invokeSync(tools, "query_bag", "{}").code());
            assertEquals(AI_TOOL_INVALID_ARGS, invokeSync(tools, "query_bag", "not-json").code());

            assertEquals("sent:1001:Hi:REWARD",
                    invokeSync(tools, "send_mail", "{\"roleId\":1001,\"title\":\"Hi\",\"kind\":\"REWARD\"}").data());
            McpToolResult domainFail = invokeSync(tools, "send_mail", "{\"roleId\":1001,\"title\":\"full\"}");
            assertEquals(BAG_FULL, domainFail.code());
            assertEquals("Mailbox is full", domainFail.message());

            assertEquals("ok", invokeSync(tools, "kick_player", "{\"roleId\":88}").data());
            assertEquals(List.of(88L), target.kicked);

            CompletableFuture<McpToolResult> async = new CompletableFuture<>();
            tools.call("query_rank", "{\"roleId\":99}", Map.of("X-Agent", "npc-ai"), null, async::complete);
            assertEquals("rank#99@1(npc-ai)", async.get(5, TimeUnit.SECONDS).data());

            assertEquals("pong:hello", invokeSync(tools, "ping_tool", "{\"msg\":\"hello\"}").data());

            assertEquals(AI_TOOL_NOT_FOUND, invokeSync(tools, "no_such_tool", "{}").code());
            assertEquals(AI_TOOL_EXECUTION_ERROR, invokeSync(tools, "returns_null", "{}").code());
            assertEquals(AI_TOOL_EXECUTION_ERROR, invokeSync(tools, "throws_boom", "{}").code());

            assertEquals(1L, metrics.counterValue("managame.ai.mcp.not_found", ""));
            assertEquals(2L, metrics.counterValue("managame.ai.mcp.invalid_args", ""));
            assertEquals(1L, metrics.counterValue("managame.ai.mcp.calls", "tool=online_count"));
            assertEquals(1L, metrics.counterValue("managame.ai.mcp.business_failures", "tool=send_mail"));
            assertEquals(0L, metrics.counterValue("managame.ai.mcp.failures", "tool=send_mail"));
            assertEquals(1L, metrics.counterValue("managame.ai.mcp.failures", "tool=throws_boom"));
        }
    }

    @Test void interceptorsPreserveMetadataIsolateAsyncIoThreadsAndExposeRawArgumentsJson() throws Exception {
        List<String> trace = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> auditedRawJson = new AtomicReference<>();
        AtomicReference<String> afterThreadName = new AtomicReference<>();

        McpToolInterceptor auth = new McpToolInterceptor() {
            @Override public boolean allowList(Map<String, String> headers, McpToolDefinition tool) {
                return !"npc".equals(headers.get("X-Role")) || tool.readOnly();
            }
            @Override public boolean before(McpToolCall call) {
                call.context().put(CALLER_ID, "verified-" + call.context().header("X-Role"));
                trace.add("auth:before");
                if ("npc".equals(call.context().header("X-Role")) && !call.definition().readOnly()) {
                    return false;
                }
                return true;
            }
            @Override public void after(McpToolCall call, McpToolResult result) {
                afterThreadName.set(Thread.currentThread().getName());
                auditedRawJson.set(call.rawArgumentsJson());
                trace.add("auth:after:" + result.code() + ":" + call.context().metadata().get(CALLER_ID));
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

        McpToolExecutor routeLikeExec = new McpToolExecutor() {
            @Override public void execute(McpToolCall call, Runnable task) {
                Thread.ofVirtual().name("player-route").start(task);
            }
            @Override public void complete(McpToolCall call, Runnable completion) {
                Thread.ofVirtual().name("player-route").start(completion);
            }
        };

        try (McpTools tools = McpTools.builder()
                .executor(routeLikeExec)
                .intercept(List.of(auth, audit))
                .register(List.of(new GameTools()))
                .build()) {

            List<McpToolDefinition> npcVisible = tools.list(Map.of("x-role", "npc"));
            assertTrue(npcVisible.stream().allMatch(McpToolDefinition::readOnly));
            assertFalse(npcVisible.stream().anyMatch(d -> d.name().equals("kick_player")));

            // Async query_rank calls ctx.reply on "simulated-rpc-io", yet `after` runs on "player-route" via complete()!
            CompletableFuture<McpToolResult> ok = new CompletableFuture<>();
            String verbatimArgs = "{ \"roleId\" : 99 }";
            tools.call("query_rank", verbatimArgs, Map.of("X-Role", "npc", "X-Agent", "bot"), null, ok::complete);
            assertEquals(SUCCESS, ok.get(5, TimeUnit.SECONDS).code());
            assertNotEquals("simulated-rpc-io", afterThreadName.get(), "after must not block the RPC I/O thread");
            assertEquals("player-route", afterThreadName.get());
            assertEquals(verbatimArgs, auditedRawJson.get());
            assertEquals(List.of("auth:before", "audit:before", "audit:after:0", "auth:after:0:verified-npc"), trace);

            trace.clear();
            CompletableFuture<McpToolResult> refused = new CompletableFuture<>();
            tools.call("kick_player", "{\"roleId\":1}", Map.of("X-Role", "npc"), null, refused::complete);
            assertEquals(AI_TOOL_REJECTED, refused.get(5, TimeUnit.SECONDS).code());
            assertEquals(List.of("auth:before"), trace);
        }
    }

    @Test void pojoParamAndReturnTypeRequireCustomCodecAndFailEagerlyAtStartup() throws Exception {
        class PojoParamTools {
            @McpTool(description = "Uses mutable POJO")
            public String usePojo(MutablePojo pojo) { return pojo.id + ":" + pojo.label; }
        }
        class PojoReturnTools {
            @McpTool(description = "Returns mutable POJO")
            public MutablePojo returnPojo() { return new MutablePojo(); }
        }
        assertThrows(IllegalArgumentException.class,
                () -> McpTools.builder().register(List.of(new PojoParamTools())).build());
        assertThrows(IllegalArgumentException.class,
                () -> McpTools.builder().register(List.of(new PojoReturnTools())).build());

        try (McpTools tools = McpTools.builder()
                .codec(JACKSON_CODEC)
                .executor(McpToolExecutor.direct())
                .register(List.of(new PojoParamTools()))
                .build()) {
            assertEquals("7:ok", invokeSync(tools, "usePojo", "{\"id\":7,\"label\":\"ok\"}").data());
        }

        assertThrows(IllegalArgumentException.class,
                () -> McpTools.builder().register(List.of(new GameTools(), new GameTools())).build());

        class BadCtx {
            @McpTool(description = "bad")
            public void bad(long id, McpToolContext ctx) {}
        }
        assertThrows(IllegalArgumentException.class, () -> McpTools.builder().register(List.of(new BadCtx())));

        AtomicReference<McpToolCall> captured = new AtomicReference<>();
        try (McpTools routed = McpTools.builder()
                .executor((call, task) -> { captured.set(call); task.run(); })
                .register(List.of(new GameTools()))
                .build()) {
            invokeSync(routed, "send_mail", "{\"roleId\":55,\"title\":\"hi\"}");
            assertNotNull(captured.get().method());
            assertEquals(55L, ((SendMail) captured.get().singleArgument()).roleId());
            assertEquals(55L, captured.get().rawArguments().get("roleId"));
        }

        class BaseService {
            @McpTool(description = "base tool", readOnly = true)
            public String info() { return "base"; }
        }
        class SubService extends BaseService {
            @Override
            @McpTool(description = "sub tool", readOnly = true)
            public String info() { return "sub"; }
        }
        try (McpTools inherited = McpTools.builder().executor(McpToolExecutor.direct()).register(List.of(new SubService())).build()) {
            assertEquals(1, inherited.definitions().size());
            assertEquals("sub", invokeSync(inherited, "info", "{}").data());
        }
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

        AiClientTest.RecordingMetrics metrics = new AiClientTest.RecordingMetrics();
        try (McpTools tools = McpTools.builder()
                .timeout(Duration.ofMillis(120))
                .metrics(metrics)
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
            assertTrue(timedOut.message().contains("may still take effect"),
                    "Started non-idempotent mutating tool timeout must warn model not to blindly retry: " + timedOut.message());
            assertEquals(1L, metrics.counterValue("managame.ai.mcp.timeouts", ""));
        }
    }

    @Test void queuedTimeoutSkipsSideEffectsMaxInFlightAndCloseAbortsInFlight() throws Exception {
        AtomicBoolean sideEffectRan = new AtomicBoolean();
        AtomicBoolean beforeRan = new AtomicBoolean();
        AtomicReference<Runnable> deferredTask = new AtomicReference<>();
        CountDownLatch holdEntered = new CountDownLatch(1);

        class MutatingTools {
            @McpTool(name = "grant_once", description = "Idempotent mutating tool", idempotent = true)
            public String grantOnce() {
                sideEffectRan.set(true);
                return "granted";
            }

            @McpTool(name = "hold", description = "Async holding tool")
            public void hold(McpToolContext ctx) {
                heldContext.set(ctx);
                holdEntered.countDown();
            }

            final AtomicReference<McpToolContext> heldContext = new AtomicReference<>();
        }

        MutatingTools target = new MutatingTools();
        AiClientTest.RecordingMetrics metrics = new AiClientTest.RecordingMetrics();
        McpTools tools = McpTools.builder()
                .timeout(Duration.ofMillis(80))
                .maxInFlight(1)
                .metrics(metrics)
                .executor((call, task) -> {
                    if ("grant_once".equals(call.toolName())) deferredTask.set(task);
                    else task.run();
                })
                .intercept(List.of(new McpToolInterceptor() {
                    @Override public boolean before(McpToolCall call) {
                        if ("grant_once".equals(call.toolName())) beforeRan.set(true);
                        return true;
                    }
                }))
                .register(List.of(target))
                .build();

        assertTrue(tools.definitions().stream().filter(d -> d.name().equals("grant_once")).findFirst().orElseThrow().idempotent());

        CompletableFuture<McpToolResult> timedOut = new CompletableFuture<>();
        tools.call("grant_once", "{}", timedOut::complete);
        McpToolResult queuedTimeout = timedOut.get(5, TimeUnit.SECONDS);
        assertEquals(AI_TIMEOUT, queuedTimeout.code());
        assertTrue(queuedTimeout.message().contains("before it started; it will not run"), queuedTimeout.message());
        assertNotNull(deferredTask.get());
        deferredTask.get().run();
        assertFalse(beforeRan.get(), "before interceptor must not run after call already timed out");
        assertFalse(sideEffectRan.get(), "tool side effect must not run after call already timed out");
        assertEquals(0, tools.inFlight());
        tools.close();

        // maxInFlight=1 rejects 2nd concurrent call with AI_OVERLOADED, and close() aborts in-flight async call with AI_CLOSED
        McpTools limited = McpTools.builder()
                .maxInFlight(1)
                .executor(McpToolExecutor.direct())
                .metrics(metrics)
                .register(List.of(target))
                .build();
        CompletableFuture<McpToolResult> first = new CompletableFuture<>();
        limited.call("hold", "{}", first::complete);
        assertTrue(holdEntered.await(5, TimeUnit.SECONDS));
        assertEquals(1, limited.inFlight());

        McpToolResult second = invokeSync(limited, "hold", "{}");
        assertEquals(AI_OVERLOADED, second.code());
        assertEquals(1L, metrics.counterValue("managame.ai.mcp.overloaded", ""));

        limited.close();
        assertTrue(limited.isClosed());
        assertEquals(AI_CLOSED, first.get(5, TimeUnit.SECONDS).code());
        assertEquals(0, limited.inFlight());
        assertEquals(AI_CLOSED, invokeSync(limited, "grant_once", "{}").code());
        assertEquals(2L, metrics.counterValue("managame.ai.mcp.closed", ""));

        AiClientTest.RecordingMetrics namedMetrics = new AiClientTest.RecordingMetrics();
        try (McpTools gmRegistry = McpTools.builder()
                .executor(McpToolExecutor.direct())
                .metrics(namedMetrics, "gm")
                .register(List.of(target))
                .build()) {
            assertEquals("granted", invokeSync(gmRegistry, "grant_once", "{}").data());
            assertEquals(1L, namedMetrics.counterValue("managame.ai.mcp.calls", "server=gm,tool=grant_once"));
        }
    }

    @Test void routeKeyCompiledAndValidatedAtRegistration() throws Exception {
        record GuildCmd(int guildId, String notice) {}

        class RoutedTools {
            @McpTool(name = "by_param", description = "Routed by parameter", routeKey = "roleId", readOnly = true)
            public long byParam(@McpParam("player") long roleId, @McpParam("tag") String tag) { return roleId; }

            @McpTool(name = "by_dto", description = "Routed by record component", routeKey = "guildId")
            public int byDto(GuildCmd cmd) { return cmd.guildId(); }

            @McpTool(name = "by_field", description = "Routed by POJO field", routeKey = "targetRole")
            public long byField(FieldDto dto) { return dto.targetRole; }

            @McpTool(name = "unrouted", description = "No route key", readOnly = true)
            public String unrouted() { return "ok"; }
        }

        List<String> observedRoutes = new ArrayList<>();
        try (McpTools tools = McpTools.builder()
                .codec(JACKSON_CODEC)
                .executor((call, task) -> {
                    observedRoutes.add(call.toolName() + ":" + call.hasRouteKey() + ":" + call.routeKey());
                    task.run();
                })
                .register(List.of(new RoutedTools()))
                .build()) {
            assertEquals(1001L, invokeSync(tools, "by_param", "{\"roleId\":1001,\"tag\":\"hi\"}").data());
            assertEquals(77, invokeSync(tools, "by_dto", "{\"guildId\":77,\"notice\":\"war\"}").data());
            assertEquals(2002L, invokeSync(tools, "by_field", "{\"targetRole\":2002}").data());
            assertEquals("ok", invokeSync(tools, "unrouted", "{}").data());
            assertEquals(List.of(
                    "by_param:true:1001",
                    "by_dto:true:77",
                    "by_field:true:2002",
                    "unrouted:false:0"), observedRoutes);

            assertEquals(AI_TOOL_INVALID_ARGS, invokeSync(tools, "by_field", "{}").code());
        }

        class BadMissingParam {
            @McpTool(description = "bad", routeKey = "noSuch")
            public void f(@McpParam("x") long roleId) {}
        }
        class BadMissingMember {
            @McpTool(description = "bad", routeKey = "noSuch")
            public void f(GuildCmd cmd) {}
        }
        class BadZeroArg {
            @McpTool(description = "bad", routeKey = "roleId")
            public void f() {}
        }
        class BadType {
            @McpTool(description = "bad", routeKey = "tag")
            public void f(@McpParam("tag") String tag) {}
        }
        assertThrows(IllegalArgumentException.class, () -> McpTools.builder().register(List.of(new BadMissingParam())));
        assertThrows(IllegalArgumentException.class, () -> McpTools.builder().register(List.of(new BadMissingMember())));
        assertThrows(IllegalArgumentException.class, () -> McpTools.builder().register(List.of(new BadZeroArg())));
        assertThrows(IllegalArgumentException.class, () -> McpTools.builder().register(List.of(new BadType())));
    }

    interface GatewayBagContract {
        @McpTool(name = "proxy_bag", description = "Query bag via gateway", group = "npc", readOnly = true, routeKey = "roleId")
        String queryBag(@McpParam("player") long roleId, @McpParam("itemId") int itemId);
    }

    record ProxyGrantDto(long roleId, int count) {}

    static final class UninstantiableBackendService {
        UninstantiableBackendService() {
            throw new AssertionError("Stateless gateway must never instantiate backend service");
        }

        @McpTool(name = "proxy_grant", description = "Grant via gateway", group = "gm", idempotent = true, routeKey = "roleId")
        public String grant(ProxyGrantDto dto) {
            throw new AssertionError("Must not invoke local method");
        }
    }

    @Test void statelessScanDefinitionsAndProxyRegistrationWithSnowflakeString() throws Exception {
        List<McpToolDefinition> scanned = McpTools.scanDefinitions(
                List.of(GatewayBagContract.class, UninstantiableBackendService.class));
        assertEquals(2, scanned.size());
        assertEquals("proxy_bag", scanned.get(0).name());
        assertTrue(scanned.get(0).readOnly());
        assertEquals("proxy_grant", scanned.get(1).name());
        assertTrue(scanned.get(1).idempotent());

        long snowflake = 1795519194523264001L;
        McpToolInterceptor scopeGuard = new McpToolInterceptor() {
            @Override public boolean before(McpToolCall call) {
                String bound = call.context().header("X-Role-Id");
                return bound == null || (call.hasRouteKey() && bound.equals(Long.toString(call.routeKey())));
            }
        };

        try (McpTools gateTools = McpTools.builder()
                .intercept(List.of(scopeGuard))
                .registerProxy(List.of(GatewayBagContract.class, UninstantiableBackendService.class), call -> {
                    assertNotNull(call.method());
                    assertTrue(call.hasRouteKey());
                    assertTrue(call.rawArgumentsJson().contains("1795519194523264001"));
                    Thread.ofVirtual().start(() ->
                            call.context().reply("forwarded:" + call.toolName() + ":" + call.routeKey()));
                })
                .build()) {
            CompletableFuture<McpToolResult> ok = new CompletableFuture<>();
            gateTools.call("proxy_bag",
                    "{\"roleId\":\"1795519194523264001\",\"itemId\":\"2001\"}",
                    Map.of("X-Role-Id", "1795519194523264001"),
                    null,
                    ok::complete);
            assertEquals("forwarded:proxy_bag:" + snowflake, ok.get(5, TimeUnit.SECONDS).data());

            CompletableFuture<McpToolResult> denied = new CompletableFuture<>();
            gateTools.call("proxy_bag",
                    "{\"roleId\":\"1795519194523264002\",\"itemId\":2001}",
                    Map.of("X-Role-Id", "1795519194523264001"),
                    null,
                    denied::complete);
            assertEquals(AI_TOOL_REJECTED, denied.get(5, TimeUnit.SECONDS).code());

            assertEquals("forwarded:proxy_grant:1795519194523264001",
                    invokeSync(gateTools, "proxy_grant", "{\"roleId\":\"1795519194523264001\",\"count\":5}").data());
        }
    }

    private static McpToolResult invokeSync(McpTools tools, String name, String argsJson) throws Exception {
        CompletableFuture<McpToolResult> f = new CompletableFuture<>();
        tools.call(name, argsJson, f::complete);
        return f.get(5, TimeUnit.SECONDS);
    }
}
