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
import cn.managame.network.http.HttpInitializer;
import cn.managame.network.netty.NetworkServer;
import cn.managame.runtime.Context;
import cn.managame.runtime.Continuation;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeException;
import cn.managame.runtime.RouteKeys;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.executor.RouteExecutors;
import cn.managame.runtime.http.HttpMethod;
import cn.managame.runtime.http.HttpResult;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 完整拼装示例：展示如何在不让 managame-ai 依赖 managame-runtime 的前提下，把 GameRuntime、@HttpMethod/@McpTool、
 * 跨玩家防越权拦截器、结构化选项决策与超时降级、以及外部 AI 服务（通过官方 MCP SDK 回调游戏服）串起来。
 */
class AiRuntimeIntegrationExampleTest {
    private static final int ITEM_LIMIT_EXCEEDED = 10_001;
    private static final MetadataKey<String> TRACE_ID = MetadataKey.stringKey(1);

    enum NpcAction { ATTACK, TRADE, FLEE }

    record GrantItem(@McpParam("玩家角色ID") long roleId, @McpParam("道具ID") int itemId, @McpParam("数量") int count) {}
    record BagSnapshot(long roleId, int gold, int routeDomain, long routeKey) {}
    record DecideReq(String sessionId, long roleId, long npcId, List<NpcAction> allowedActions, String mode) {}
    record DecideRes(NpcAction action, String reason) {}

    /** 现有玩家业务类：同一个方法既可以是 HTTP GM 接口，也可以加 @McpTool 直接成为 MCP 工具。 */
    @Domain(DomainConstant.PLAYER)
    static class PlayerBagService {
        private final Map<Long, Integer> goldByRole = new ConcurrentHashMap<>(Map.of(1001L, 500, 1002L, 10));

        @McpTool(name = "query_bag", description = "查询玩家背包与金币数量", group = "npc", readOnly = true)
        public BagSnapshot queryBag(@McpParam("玩家角色ID") long roleId) {
            Context ctx = Context.current(); // 校验确实运行在 GameRuntime 的玩家路由上
            return new BagSnapshot(roleId, goldByRole.getOrDefault(roleId, 0), ctx.domain(), ctx.routeKey());
        }

        @HttpMethod(value = "/grant", routeKey = "roleId")
        @McpTool(name = "grant_item", description = "给玩家发放金币或道具", group = "gm")
        public HttpResult grantItem(GrantItem req) {
            if (req.count() > 100) return HttpResult.fail(ITEM_LIMIT_EXCEEDED, "单次发放上限为 100");
            int updated = goldByRole.merge(req.roleId(), req.count(), Integer::sum);
            return HttpResult.ok(new BagSnapshot(req.roleId(), updated, Context.current().domain(), Context.current().routeKey()));
        }
    }

    @Test void runtimeRoutedMcpToolsDecisionAndFallback() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AiCodec jackson = new AiCodec() {
            @Override public String encode(Object value) throws Exception { return mapper.writeValueAsString(value); }
            @Override public Object decode(String json, Type type) throws Exception {
                return mapper.readValue(json, mapper.constructType(type));
            }
        };

        GameRuntime runtime = GameRuntime.builder()
                .domain(DomainConstant.PLAYER, RouteExecutors.virtualThreads(), RouteKeys.businessId())
                .build();

        // 1. McpToolExecutor：读取方法上的 @Domain 和参数里的 roleId，路由到 GameRuntime 串行执行
        McpToolExecutor runtimeExecutor = (call, task) -> {
            Domain domain = call.method().getAnnotation(Domain.class);
            if (domain == null) domain = call.method().getDeclaringClass().getAnnotation(Domain.class);
            if (domain == null) { McpToolExecutor.virtualThreads().execute(call, task); return; }
            Object rawRoleId = call.rawArguments().get("roleId");
            long routeKey = rawRoleId instanceof Number n ? n.longValue() : 0L;
            GameContext ctx = GameContext.of(domain.value(), routeKey);
            try {
                runtime.execute(ctx, c -> task.run());
            } catch (GameRuntimeException refused) {
                call.context().fail(refused.errorCode(), FrameworkErrorCodes.describe(refused.errorCode()));
            }
        };

        // 2. McpToolInterceptor：NPC 仅可见 npc 组只读工具，且强校验 X-Role-Id 防止提示词注入跨玩家越权
        McpToolInterceptor scopeGuard = new McpToolInterceptor() {
            @Override public boolean allowList(Map<String, String> headers, McpToolDefinition tool) {
                return !"npc".equals(headers.get("X-Caller")) || ("npc".equals(tool.group()) && tool.readOnly());
            }
            @Override public boolean before(McpToolCall call) {
                if ("npc".equals(call.context().header("X-Caller"))) {
                    if (!call.definition().readOnly()) return false;
                    String boundRole = call.context().header("X-Role-Id");
                    Object argRole = call.rawArguments().get("roleId");
                    if (boundRole == null || !boundRole.equals(String.valueOf(argRole))) return false;
                }
                return true;
            }
        };

        // 3. McpTools：通过 resultAdapter 无缝复用返回 HttpResult 的已有业务方法，并配工具超时兜底
        McpTools mcpTools = McpTools.builder()
                .codec(jackson)
                .executor(runtimeExecutor)
                .intercept(List.of(scopeGuard))
                .timeout(Duration.ofSeconds(5))
                .resultAdapter(res -> res instanceof HttpResult r
                        ? (r.code() == 0 ? McpToolResult.ok(r.data()) : McpToolResult.fail(r.code(), r.msg()))
                        : null)
                .register(List.of(new PlayerBagService()))
                .build();

        NetworkServer mcpServer = NetworkServer.start(new InetSocketAddress("127.0.0.1", 0),
                HttpInitializer.http().contextPath("/mcp")
                        .handler(McpHttpHandler.builder(mcpTools).serverInfo("mana-example-game", "1.0.0").build())
                        .build());

        String gameMcpBase = "http://127.0.0.1:" + mcpServer.port();

        // 4. 模拟外部 AI 服务（如 Spring AI）：收到决策请求后，携带绑定的 X-Role-Id 通过 MCP 回查游戏服背包再做选项决策
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
                                respondJson(exchange, mapper.writeValueAsString(new DecideRes(NpcAction.ATTACK, "hallucinated")));
                                return;
                            }
                            // 外部 AI 服务使用官方 MCP SDK 回调游戏服查询玩家背包
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
                                // 模拟提示词注入试图跨玩家查询 1002 -> 必须被 scopeGuard 拦截
                                McpSchema.CallToolResult stolen = mcp.callTool(
                                        new McpSchema.CallToolRequest("query_bag", Map.of("roleId", 1002)));
                                assertTrue(Boolean.TRUE.equals(stolen.isError()), "Cross-player access must be rejected");

                                // 正常查询当前绑定的玩家背包（并在玩家的 GameRuntime 路由上执行）
                                McpSchema.CallToolResult own = mcp.callTool(
                                        new McpSchema.CallToolRequest("query_bag", Map.of("roleId", req.roleId())));
                                assertFalse(Boolean.TRUE.equals(own.isError()));
                                String text = ((McpSchema.TextContent) own.content().getFirst()).text();
                                bag = mapper.readValue(text, BagSnapshot.class);
                            }
                            assertEquals(DomainConstant.PLAYER, bag.routeDomain());
                            assertEquals(req.roleId(), bag.routeKey());
                            NpcAction chosen = bag.gold() >= 100 ? NpcAction.TRADE : NpcAction.FLEE;
                            respondJson(exchange, mapper.writeValueAsString(new DecideRes(chosen, "gold=" + bag.gold())));
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
            // 5. 游戏服在 PLAYER 路由上向 AI 请求结构化选项决策，用 Continuation 回到原路由校验并应用
            NpcAction chosen = askNpcDecision(runtime, aiClient, 1001L, 7L, 1,
                    List.of(NpcAction.TRADE, NpcAction.FLEE), "normal", Duration.ofSeconds(5));
            assertEquals(NpcAction.TRADE, chosen);

            // 超时 -> 自动降级到规则引擎默认值 FLEE
            NpcAction onTimeout = askNpcDecision(runtime, aiClient, 1001L, 7L, 1,
                    List.of(NpcAction.TRADE, NpcAction.FLEE), "timeout", Duration.ofMillis(120));
            assertEquals(NpcAction.FLEE, onTimeout);

            // AI 返回不在允许列表内的选项（ATTACK 不在 [TRADE, FLEE] 中）-> 游戏服校验拦截并降级为 FLEE
            NpcAction onDisallowed = askNpcDecision(runtime, aiClient, 1001L, 7L, 1,
                    List.of(NpcAction.TRADE, NpcAction.FLEE), "invalid-option", Duration.ofSeconds(5));
            assertEquals(NpcAction.FLEE, onDisallowed);

            // GM 通过 MCP 调用复用的 @HttpMethod("grant_item")：HttpResult.fail 经 resultAdapter 映射为 isError=true
            var gmTransport = HttpClientStreamableHttpTransport.builder(gameMcpBase)
                    .endpoint("/mcp")
                    .requestBuilder(HttpRequest.newBuilder().header("X-Caller", "gm"))
                    .build();
            try (McpSyncClient gm = McpClient.sync(gmTransport).requestTimeout(Duration.ofSeconds(5)).build()) {
                gm.initialize();
                McpSchema.CallToolResult tooMuch = gm.callTool(
                        new McpSchema.CallToolRequest("grant_item", Map.of("roleId", 1001, "itemId", 1, "count", 999)));
                assertTrue(Boolean.TRUE.equals(tooMuch.isError()));
                assertTrue(((McpSchema.TextContent) tooMuch.content().getFirst()).text().contains("上限"));
            }
        } finally {
            // 6. 正确关闭顺序：停入口 -> 关 AI 客户端与 MCP 工具 -> 关 GameRuntime
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
            String sessionId = "npc:" + roleId + ":" + npcId + ":" + epoch;
            DecideReq req = new DecideReq(sessionId, roleId, npcId, allowed, mode);
            ai.call(AiRequest.of("/npc/decide", req, ctx.metadata()).withTimeout(timeout), DecideRes.class, res ->
                    back.execute(c -> {
                        assertEquals(DomainConstant.PLAYER, Context.current().domain());
                        assertEquals(roleId, Context.current().routeKey());
                        if (!res.isSuccess() || !allowed.contains(res.data().action())) {
                            appliedOnRoute.set(NpcAction.FLEE); // 规则降级
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
