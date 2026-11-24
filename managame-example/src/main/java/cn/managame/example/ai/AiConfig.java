package cn.managame.example.ai;

import cn.managame.FrameworkErrorCodes;
import cn.managame.IdLayout;
import cn.managame.SnowflakeIdGenerator;
import cn.managame.ai.AiClient;
import cn.managame.ai.AiCodec;
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
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeException;
import cn.managame.runtime.GameTask;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.http.HttpResult;
import cn.managame.runtime.time.GameTime;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;

/**
 * 游戏服的 AI 接入装配与全生命周期编排：
 * <ul>
 *   <li>出站：{@link AiClient} 调外部 Spring AI 服务，自动携带 {@code X-Game-Server-Id} 与透传 {@code X-Trace-Id}</li>
 *   <li>入站：{@link McpTools} + {@link McpHttpHandler} 在 {@code /mcp} 暴露注解工具，配多 Token 热轮换鉴权、
 *       编译期 {@code routeKey} 路由、跨玩家防越权拦截器与写工具 {@link AiAuditLogRepository} 审计落盘</li>
 *   <li>启停顺序：{@link McpEndpoint} 实现最高阶段 {@link SmartLifecycle}，保证开服就绪后再监听 {@code /mcp}，
 *       停服时最先关 {@code /mcp} 入口 → 再关客户端网关/RPC → 再关 {@code AiClient}/{@code McpTools}（在途回调安全回到
 *       仍存活的 {@code GameRuntime}）→ 最后关 {@code GameRuntime} 与 {@code GameData}（刷盘实体与审计日志）</li>
 * </ul>
 */
@Configuration
public class AiConfig {

    @Bean
    public AiCodec aiCodec() {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new AiCodec() {
            @Override public String encode(Object value) throws Exception { return mapper.writeValueAsString(value); }
            @Override public byte[] encodeBytes(Object value) throws Exception { return mapper.writeValueAsBytes(value); }
            @Override public Object decode(String json, Type type) throws Exception {
                return mapper.readValue(json, mapper.constructType(type));
            }
            @Override public Object decodeBytes(byte[] utf8, Type type) throws Exception {
                return mapper.readValue(utf8, mapper.constructType(type));
            }
        };
    }

    @Bean
    @DependsOn("gameRuntime")
    public AiClient aiClient(AiCodec aiCodec, Environment env) {
        String baseUrl = env.getProperty("ai.service.url", "http://127.0.0.1:8081/api/ai");
        String token = env.getProperty("ai.service.token", "dev-ai-token");
        int serverId = env.getProperty("rpc.node", Integer.class, 2);
        return AiClient.builder(baseUrl)
                .codec(aiCodec)
                .timeout(Duration.ofSeconds(10))
                .streamIdleTimeout(Duration.ofSeconds(5))
                .header("Authorization", () -> "Bearer " + token)
                .header("X-Game-Server-Id", String.valueOf(serverId))
                .propagate(NpcAiService.TRACE_ID, "X-Trace-Id")
                .build();
    }

    @Bean
    @DependsOn("gameRuntime")
    public McpTools mcpTools(GameRuntime runtime, AiCodec aiCodec, PlayerBagService bagService,
            AiAuditLogRepository auditLogRepo, Environment env) {
        int serverId = env.getProperty("rpc.node", Integer.class, 2);
        SnowflakeIdGenerator auditIds = new SnowflakeIdGenerator(IdLayout.DEFAULT, serverId);

        // 1. 把带 @Domain 的 @McpTool 调度到编译好的 call.routeKey() 玩家路由上串行执行，并覆盖 GameTask.refused
        McpToolExecutor runtimeExecutor = (call, task) -> {
            Domain domain = call.method() != null ? call.method().getAnnotation(Domain.class) : null;
            if (domain == null && call.method() != null) {
                domain = call.method().getDeclaringClass().getAnnotation(Domain.class);
            }
            if (domain == null) {
                McpToolExecutor.virtualThreads().execute(call, task);
                return;
            }
            long routeKey = call.hasRouteKey() ? call.routeKey() : 0L;
            try {
                runtime.execute(GameContext.of(domain.value(), routeKey), new GameTask<Context>() {
                    @Override public void run(Context context) { task.run(); }

                    @Override public void refused(Context context) {
                        call.context().fail(FrameworkErrorCodes.AI_TOOL_REJECTED,
                                "MCP tool refused on route (" + domainFrom(call) + ":" + routeKey + ")");
                    }
                });
            } catch (GameRuntimeException refused) {
                call.context().fail(refused.errorCode(), FrameworkErrorCodes.describe(refused.errorCode()));
            }
        };

        // 2. 角色权限 + 跨玩家防越权：NPC 只能调 npc 组只读工具，且编译路由键必须等于绑定头 X-Role-Id
        McpToolInterceptor playerScopeGuard = new McpToolInterceptor() {
            @Override public boolean allowList(Map<String, String> headers, McpToolDefinition tool) {
                return !"npc".equals(headers.get("X-Caller")) || ("npc".equals(tool.group()) && tool.readOnly());
            }

            @Override public boolean before(McpToolCall call) {
                if ("npc".equals(call.context().header("X-Caller"))) {
                    if (!"npc".equals(call.definition().group()) || !call.definition().readOnly()) return false;
                    String boundRoleId = call.context().header("X-Role-Id");
                    if (boundRoleId == null || !call.hasRouteKey()
                            || !boundRoleId.equals(String.valueOf(call.routeKey()))) {
                        call.context().fail(FrameworkErrorCodes.AI_TOOL_REJECTED,
                                "Cross-player access denied: bound role " + boundRoleId
                                        + ", requested " + (call.hasRouteKey() ? call.routeKey() : "none"));
                        return false;
                    }
                }
                return true;
            }
        };

        // 3. 写工具全量审计：无论成功或失败，均异步写入 ai_audit_log 表留痕
        McpToolInterceptor writeAuditInterceptor = new McpToolInterceptor() {
            @Override public void after(McpToolCall call, McpToolResult result) {
                if (call.definition().readOnly()) return;
                String caller = call.context().header("X-Caller");
                String traceId = call.context().header("X-Trace-Id");
                long roleId = call.hasRouteKey() ? call.routeKey() : 0L;
                auditLogRepo.insert(new AiAuditLog(
                        auditIds.generateId(),
                        "MCP_WRITE",
                        caller != null ? caller : "unknown",
                        roleId,
                        call.toolName(),
                        traceId != null ? traceId : "",
                        call.rawArguments().toString(),
                        result.code(),
                        0,
                        GameTime.currentTimeMillis()));
            }
        };

        return McpTools.builder()
                .codec(aiCodec)
                .executor(runtimeExecutor)
                .intercept(List.of(playerScopeGuard, writeAuditInterceptor))
                .timeout(Duration.ofSeconds(10))
                .resultAdapter(res -> res instanceof HttpResult r
                        ? (r.code() == 0 ? McpToolResult.ok(r.data()) : McpToolResult.fail(r.code(), r.msg()))
                        : null)
                .register(List.of(bagService))
                .build();
    }

    @Bean
    @DependsOn({"gameServer", "aiClient"})
    public McpEndpoint mcpEndpoint(McpTools mcpTools, Environment env) {
        int port = env.getProperty("ai.mcp.port", Integer.class, 8082);
        String mcpToken = env.getProperty("ai.mcp.token", "dev-mcp-token");
        String gmToken = env.getProperty("ai.mcp.gm-token", "");
        BearerTokenAuthHandler authHandler = new BearerTokenAuthHandler(mcpToken, gmToken);
        return new McpEndpoint(port, mcpTools, authHandler);
    }

    private static int domainFrom(McpToolCall call) {
        Domain d = call.method().getAnnotation(Domain.class);
        if (d == null) d = call.method().getDeclaringClass().getAnnotation(Domain.class);
        return d != null ? d.value() : 0;
    }

    /**
     * 包装 MCP HTTP 监听并绑定 Spring {@link SmartLifecycle}（{@code phase = Integer.MAX_VALUE}）：
     * 启动时在所有游戏仓储、运行时与网关就绪后最后开放 {@code /mcp}；关闭时在任何 Bean 销毁前最先停止接收新 MCP 请求。
     */
    public static final class McpEndpoint implements SmartLifecycle, AutoCloseable {
        private final int configuredPort;
        private final McpTools mcpTools;
        private final BearerTokenAuthHandler authHandler;
        private volatile NetworkServer server;

        McpEndpoint(int configuredPort, McpTools mcpTools, BearerTokenAuthHandler authHandler) {
            this.configuredPort = configuredPort;
            this.mcpTools = mcpTools;
            this.authHandler = authHandler;
        }

        @Override public synchronized void start() {
            if (server != null) return;
            server = NetworkServer.start(new InetSocketAddress("127.0.0.1", configuredPort),
                    HttpInitializer.http()
                            .contextPath("/mcp")
                            .addLast("mcp-auth", authHandler)
                            .handler(McpHttpHandler.builder(mcpTools)
                                    .serverInfo("managame-example", "1.0.0")
                                    .instructions("Game server MCP tools for NPC queries and GM operations")
                                    .build())
                            .build());
        }

        @Override public synchronized void stop() {
            NetworkServer s = server;
            if (s != null) {
                server = null;
                s.close();
            }
        }

        @Override public boolean isRunning() { return server != null; }

        @Override public int getPhase() { return Integer.MAX_VALUE; }

        public int port() {
            NetworkServer s = server;
            if (s == null) throw new IllegalStateException("McpEndpoint is not running");
            return s.port();
        }

        public BearerTokenAuthHandler authHandler() { return authHandler; }

        @Override public void close() { stop(); }
    }
}
