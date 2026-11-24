package cn.managame.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.FrameworkErrorCodes;
import cn.managame.MetadataKey;
import cn.managame.ai.AiClient;
import cn.managame.ai.AiCodec;
import cn.managame.ai.AiRequest;
import cn.managame.ai.annotation.McpParam;
import cn.managame.ai.annotation.McpTool;
import cn.managame.ai.mcp.McpHttpHandler;
import cn.managame.ai.mcp.McpToolCall;
import cn.managame.ai.mcp.McpToolDefinition;
import cn.managame.ai.mcp.McpToolInterceptor;
import cn.managame.ai.mcp.McpToolResult;
import cn.managame.ai.mcp.McpTools;
import cn.managame.ai.spi.McpToolExecutor;
import cn.managame.example.ai.ContentSafetyFilter;
import cn.managame.network.http.HttpInitializer;
import cn.managame.network.netty.NetworkServer;
import cn.managame.runtime.Context;
import cn.managame.runtime.Continuation;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeException;
import cn.managame.runtime.GameTask;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.TaskInterceptor;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.http.HttpMethod;
import cn.managame.runtime.http.HttpResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 完整拼装示例：展示如何在不让 managame-ai 依赖 managame-runtime 的前提下，把 GameRuntime（含编译期 routeKey 与
 * GameTask.refused 闭环）、@HttpMethod/@McpTool 幂等写工具、跨玩家防越权拦截器、结构化选项决策与超时降级、
 * 流式敏感词滑动过滤、以及外部 AI 服务（通过官方 MCP SDK 回调游戏服）串起来。
 */
class AiRuntimeIntegrationExampleTest {
    private static final int ITEM_LIMIT_EXCEEDED = 10_001;
    private static final MetadataKey<String> TRACE_ID = MetadataKey.stringKey(1);

    enum NpcAction { ATTACK, TRADE, FLEE }

    record GrantItem(
            @McpParam("幂等单号") String opId,
            @McpParam("玩家角色ID") long roleId,
            @McpParam("道具ID") int itemId,
            @McpParam("数量") int count) {}
    record BagSnapshot(long roleId, int gold, int routeDomain, long routeKey) {}
    record DecideReq(String sessionId, long roleId, long npcId, List<NpcAction> allowedActions, String mode) {}
    record DecideRes(NpcAction action, String reason) {}

    /** 现有玩家业务类：同一个方法既可以是 HTTP GM 接口，也可以加 @McpTool 直接成为 MCP 工具。 */
    @Domain(DomainConstant.PLAYER)
    static class SampleBagService {
        private final Map<Long, Integer> goldByRole = new ConcurrentHashMap<>(Map.of(1001L, 500, 1002L, 10));
        private final Set<String> appliedOps = ConcurrentHashMap.newKeySet();

        @McpTool(name = "query_bag", description = "查询玩家背包与金币数量",
                group = "npc", readOnly = true, routeKey = "roleId")
        public BagSnapshot queryBag(@McpParam("玩家角色ID") long roleId) {
            Context ctx = Context.current();
            return new BagSnapshot(roleId, goldByRole.getOrDefault(roleId, 0), ctx.domain(), ctx.routeKey());
        }

        @HttpMethod(value = "/grant", routeKey = "roleId")
        @McpTool(name = "grant_item", description = "给玩家发放金币或道具",
                group = "gm", idempotent = true, routeKey = "roleId")
        public HttpResult grantItem(GrantItem req) {
            if (req.count() > 100) return HttpResult.fail(ITEM_LIMIT_EXCEEDED, "单次发放上限为 100");
            int current = appliedOps.add(req.roleId() + ":" + req.opId())
                    ? goldByRole.merge(req.roleId(), req.count(), Integer::sum)
                    : goldByRole.getOrDefault(req.roleId(), 0);
            return HttpResult.ok(new BagSnapshot(req.roleId(), current, Context.current().domain(), Context.current().routeKey()));
        }
    }

    @Test void runtimeRoutedMcpToolsDecisionRefusalIdempotencyAndSafetyFilter() throws Exception {
        // 0. 验证流式敏感词滑动前缀过滤：正常分片零延迟透出，跨分片敏感词（"私" + "服"）准确脱敏
        ContentSafetyFilter filter = new ContentSafetyFilter();
        List<String> streamed = new ArrayList<>();
        ContentSafetyFilter.StreamFilter sf = filter.newStreamFilter(streamed::add);
        sf.accept("小心私");
        assertEquals(List.of("小心"), streamed, "Normal prefix must emit immediately while holding '私'");
        sf.accept("服骗子");
        assertEquals(List.of("小心", "**骗子"), streamed, "Split sensitive word across chunks must be masked");
        sf.finish();

        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        AiCodec jackson = new AiCodec() {
            @Override public String encode(Object value) throws Exception { return mapper.writeValueAsString(value); }
            @Override public Object decode(String json, Type type) throws Exception {
                return mapper.readValue(json, mapper.constructType(type));
            }
        };

        AtomicBoolean freezeRole9999 = new AtomicBoolean(true);
        GameRuntime runtime = GameRuntime.builder()
                .domain(DomainConstant.PLAYER, RouteExecutors.virtualThreads(), RouteKeys.businessId())
                .intercept(List.of(new TaskInterceptor() {
                    @Override public boolean before(Context context) {
                        return !(freezeRole9999.get() && context.routeKey() == 9999L);
                    }
                }))
                .build();

        // 1. McpToolExecutor：读取方法上的 @Domain 和编译期 call.routeKey()，覆盖 GameTask.refused 防止拦截拒绝时悬挂超时
        McpToolExecutor runtimeExecutor = (call, task) -> {
            Domain domain = call.method().getAnnotation(Domain.class);
            if (domain == null) domain = call.method().getDeclaringClass().getAnnotation(Domain.class);
            if (domain == null) { McpToolExecutor.virtualThreads().execute(call, task); return; }
            long routeKey = call.hasRouteKey() ? call.routeKey() : 0L;
            GameContext ctx = GameContext.of(domain.value(), routeKey);
            try {
                runtime.execute(ctx, new GameTask<Context>() {
                    @Override public void run(Context c) { task.run(); }
                    @Override public void refused(Context c) {
                        call.context().fail(FrameworkErrorCodes.AI_TOOL_REJECTED, "Player route is frozen");
                    }
                });
            } catch (GameRuntimeException refused) {
                call.context().fail(refused.errorCode(), FrameworkErrorCodes.describe(refused.errorCode()));
            }
        };

        // 2. McpToolInterceptor：NPC 仅可见 npc 组只读工具，且强校验 X-Role-Id == call.routeKey()
        McpToolInterceptor scopeGuard = new McpToolInterceptor() {
            @Override public boolean allowList(Map<String, String> headers, McpToolDefinition tool) {
                return !"npc".equals(headers.get("X-Caller")) || ("npc".equals(tool.group()) && tool.readOnly());
            }
            @Override public boolean before(McpToolCall call) {
                if ("npc".equals(call.context().header("X-Caller"))) {
                    if (!call.definition().readOnly()) return false;
                    String boundRole = call.context().header("X-Role-Id");
                    if (boundRole == null || !call.hasRouteKey()
                            || !boundRole.equals(String.valueOf(call.routeKey()))) return false;
                }
                return true;
            }
        };

        SampleBagService bagService = new SampleBagService();
        McpTools mcpTools = McpTools.builder()
                .codec(jackson)
                .executor(runtimeExecutor)
                .intercept(List.of(scopeGuard))
                .timeout(Duration.ofSeconds(5))
                .resultAdapter(res -> res instanceof HttpResult r
                        ? (r.code() == 0 ? McpToolResult.ok(r.data()) : McpToolResult.fail(r.code(), r.msg()))
                        : null)
                .register(List.of(bagService))
                .build();

        NetworkServer mcpServer = NetworkServer.start(new InetSocketAddress("127.0.0.1", 0),
                HttpInitializer.http().contextPath("/mcp")
                        .handler(McpHttpHandler.builder(mcpTools).serverInfo("mana-example-game", "1.0.0").build())
                        .build());

        String gameMcpBase = "http://127.0.0.1:" + mcpServer.port();

        // 4. 模拟外部 AI 服务（返回响应中额外附带新版本字段 newSchemaField 验证兼容解码）
        NetworkServer fakeAiService = NetworkServer.start(new InetSocketAddress("127.0.0.1", 0),
                HttpInitializer.http().handler(exchange -> {
                    String body = exchange.request().content().toString(StandardCharsets.UTF_8);
                    Thread.ofVirtual().start(() -> {
                        try {
                            DecideReq req = mapper.readValue(body, DecideReq.class);
                            if ("timeout".equals(req.mode())) {
                                Thread.sleep(400);
                            }
                            if ("invalid-option".equals(req.mode())) {
                                respondJson(exchange, "{\"action\":\"ATTACK\",\"reason\":\"hallucinated\",\"extraField\":123}");
                                return;
                            }
                            var transport = HttpClientStreamableHttpTransport.builder(gameMcpBase)
                                    .endpoint("/mcp")
                                    .requestBuilder(HttpRequest.newBuilder()
                                            .header("X-Caller", "npc")
                                            .header("X-Role-Id", String.valueOf(req.roleId())))
                                    .build();
                            BagSnapshot bag;
                            try (McpSyncClient mcp = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
                                mcp.initialize();
                                assertEquals(1, mcp.listTools().tools().size(), "NPC should only see readOnly npc tools");
                                McpSchema.CallToolResult stolen = mcp.callTool(
                                        new McpSchema.CallToolRequest("query_bag", Map.of("roleId", 1002)));
                                assertTrue(Boolean.TRUE.equals(stolen.isError()), "Cross-player access must be rejected");

                                McpSchema.CallToolResult own = mcp.callTool(
                                        new McpSchema.CallToolRequest("query_bag", Map.of("roleId", req.roleId())));
                                assertFalse(Boolean.TRUE.equals(own.isError()));
                                String text = ((McpSchema.TextContent) own.content().getFirst()).text();
                                bag = mapper.readValue(text, BagSnapshot.class);
                            }
                            assertEquals(DomainConstant.PLAYER, bag.routeDomain());
                            assertEquals(req.roleId(), bag.routeKey());
                            NpcAction chosen = bag.gold() >= 100 ? NpcAction.TRADE : NpcAction.FLEE;
                            respondJson(exchange, "{\"action\":\"" + chosen + "\",\"reason\":\"gold="
                                    + bag.gold() + "\",\"futureVersionField\":\"v2\"}");
                        } catch (Exception e) {
                            exchange.respond(HttpResponseStatus.INTERNAL_SERVER_ERROR);
                        }
                    });
                }).build());

        AiClient aiClient = AiClient.builder("http://127.0.0.1:" + fakeAiService.port())
                .codec(jackson)
                .propagate(TRACE_ID, "X-Trace-Id")
                .build();

        try {
            NpcAction chosen = askNpcDecision(runtime, aiClient, 1001L, 7L, 1,
                    List.of(NpcAction.TRADE, NpcAction.FLEE), "normal", Duration.ofSeconds(5));
            assertEquals(NpcAction.TRADE, chosen);

            NpcAction onTimeout = askNpcDecision(runtime, aiClient, 1001L, 7L, 1,
                    List.of(NpcAction.TRADE, NpcAction.FLEE), "timeout", Duration.ofMillis(120));
            assertEquals(NpcAction.FLEE, onTimeout);

            NpcAction onDisallowed = askNpcDecision(runtime, aiClient, 1001L, 7L, 1,
                    List.of(NpcAction.TRADE, NpcAction.FLEE), "invalid-option", Duration.ofSeconds(5));
            assertEquals(NpcAction.FLEE, onDisallowed);

            var gmTransport = HttpClientStreamableHttpTransport.builder(gameMcpBase)
                    .endpoint("/mcp")
                    .requestBuilder(HttpRequest.newBuilder().header("X-Caller", "gm"))
                    .build();
            try (McpSyncClient gm = McpClient.sync(gmTransport).requestTimeout(Duration.ofSeconds(5)).build()) {
                gm.initialize();

                // TaskInterceptor 拒绝冻结玩家 9999 -> GameTask.refused 立即返回，不会死等 5s 超时
                long startNs = System.nanoTime();
                McpSchema.CallToolResult frozen = gm.callTool(
                        new McpSchema.CallToolRequest("grant_item",
                                Map.of("opId", "op-frozen", "roleId", 9999, "itemId", 0, "count", 10)));
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
                assertTrue(Boolean.TRUE.equals(frozen.isError()));
                assertTrue(((McpSchema.TextContent) frozen.content().getFirst()).text().contains("frozen"));
                assertTrue(elapsedMs < 2000, "GameTask.refused must fail immediately, took " + elapsedMs + "ms");

                // 幂等写工具：相同 opId 重试两次只加一次金币
                McpSchema.CallToolResult firstGrant = gm.callTool(
                        new McpSchema.CallToolRequest("grant_item",
                                Map.of("opId", "op-1", "roleId", 1001, "itemId", 0, "count", 50)));
                McpSchema.CallToolResult duplicateGrant = gm.callTool(
                        new McpSchema.CallToolRequest("grant_item",
                                Map.of("opId", "op-1", "roleId", 1001, "itemId", 0, "count", 50)));
                assertFalse(Boolean.TRUE.equals(firstGrant.isError()));
                assertFalse(Boolean.TRUE.equals(duplicateGrant.isError()));
                BagSnapshot afterDup = mapper.readValue(
                        ((McpSchema.TextContent) duplicateGrant.content().getFirst()).text(), BagSnapshot.class);
                assertEquals(550, afterDup.gold(), "Duplicate opId must not double-grant gold");

                // 单次超上限拦截
                McpSchema.CallToolResult tooMuch = gm.callTool(
                        new McpSchema.CallToolRequest("grant_item",
                                Map.of("opId", "op-big", "roleId", 1001, "itemId", 1, "count", 999)));
                assertTrue(Boolean.TRUE.equals(tooMuch.isError()));
                assertTrue(((McpSchema.TextContent) tooMuch.content().getFirst()).text().contains("上限"));
            }
        } finally {
            mcpServer.close();
            aiClient.close();
            mcpTools.close();
            fakeAiService.close();
            runtime.close();
        }
    }

    private static NpcAction askNpcDecision(GameRuntime runtime, AiClient ai, long roleId, long npcId, int epoch,
            List<NpcAction> allowed, String mode, Duration timeout) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<NpcAction> appliedOnRoute = new AtomicReference<>();
        GameContext playerCtx = GameContext.of(DomainConstant.PLAYER, roleId);
        runtime.execute(playerCtx, ctx -> {
            Continuation back = runtime.continuation();
            String sessionId = "npc:2:" + roleId + ":" + npcId + ":" + epoch;
            DecideReq req = new DecideReq(sessionId, roleId, npcId, allowed, mode);
            ai.call(AiRequest.of("/npc/decide", req, ctx.metadata()).withTimeout(timeout), DecideRes.class, res ->
                    back.execute(c -> {
                        assertEquals(DomainConstant.PLAYER, Context.current().domain());
                        assertEquals(roleId, Context.current().routeKey());
                        if (!res.isSuccess() || !allowed.contains(res.data().action())) {
                            appliedOnRoute.set(NpcAction.FLEE);
                        } else {
                            appliedOnRoute.set(res.data().action());
                        }
                        done.countDown();
                    }));
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        return appliedOnRoute.get();
    }

    private static void respondJson(cn.managame.network.http.HttpExchange exchange, String json) {
        FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.copiedBuffer(json, StandardCharsets.UTF_8));
        res.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        exchange.respond(res);
    }
}
