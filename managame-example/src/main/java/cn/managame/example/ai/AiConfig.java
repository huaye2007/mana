package cn.managame.example.ai;

import cn.managame.FrameworkErrorCodes;
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
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeException;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.http.HttpResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;

/**
 * 游戏服的 AI 接入装配：
 * <ul>
 *   <li>出站：{@link AiClient} 调外部 Spring AI 服务（默认 {@code http://127.0.0.1:8081/api/ai}）</li>
 *   <li>入站：{@link McpTools} + {@link McpHttpHandler} 在 {@code /mcp} 暴露注解工具，配 Bearer Token 鉴权、
 *       跨玩家防越权拦截器与 {@link GameRuntime} 路由执行器</li>
 * </ul>
 * 关闭顺序由 Spring 依赖反序保证：{@code mcpEndpoint -> aiClient / mcpTools -> gameRuntime -> gameData}。
 */
@Configuration
public class AiConfig {

    @Bean
    public AiCodec aiCodec() {
        ObjectMapper mapper = new ObjectMapper();
        return new AiCodec() {
            @Override public String encode(Object value) throws Exception { return mapper.writeValueAsString(value); }
            @Override public Object decode(String json, Type type) throws Exception {
                return mapper.readValue(json, mapper.constructType(type));
            }
        };
    }

    @Bean
    @DependsOn("gameRuntime")
    public AiClient aiClient(AiCodec aiCodec, Environment env) {
        String baseUrl = env.getProperty("ai.service.url", "http://127.0.0.1:8081/api/ai");
        String token = env.getProperty("ai.service.token", "dev-ai-token");
        return AiClient.builder(baseUrl)
                .codec(aiCodec)
                .timeout(Duration.ofSeconds(30))
                .streamIdleTimeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .propagate(NpcAiService.TRACE_ID, "X-Trace-Id")
                .build();
    }

    @Bean
    @DependsOn("gameRuntime")
    public McpTools mcpTools(GameRuntime runtime, AiCodec aiCodec, PlayerBagService bagService) {
        // 把带 @Domain 的 @McpTool 调度到 GameRuntime 的对应 (domain, roleId) 路由上串行执行
        McpToolExecutor runtimeExecutor = (call, task) -> {
            Domain domain = call.method() != null ? call.method().getAnnotation(Domain.class) : null;
            if (domain == null && call.method() != null) {
                domain = call.method().getDeclaringClass().getAnnotation(Domain.class);
            }
            if (domain == null) {
                McpToolExecutor.virtualThreads().execute(call, task);
                return;
            }
            Object rawRoleId = call.rawArguments().get("roleId");
            long routeKey = rawRoleId instanceof Number n ? n.longValue() : 0L;
            try {
                runtime.execute(GameContext.of(domain.value(), routeKey), c -> task.run());
            } catch (GameRuntimeException refused) {
                call.context().fail(refused.errorCode(), FrameworkErrorCodes.describe(refused.errorCode()));
            }
        };

        // 角色权限 + 跨玩家防越权：NPC 只能调 npc 组只读工具，且参数 roleId 必须等于绑定头 X-Role-Id
        McpToolInterceptor playerScopeGuard = new McpToolInterceptor() {
            @Override public boolean allowList(Map<String, String> headers, McpToolDefinition tool) {
                return !"npc".equals(headers.get("X-Caller")) || ("npc".equals(tool.group()) && tool.readOnly());
            }

            @Override public boolean before(McpToolCall call) {
                if ("npc".equals(call.context().header("X-Caller"))) {
                    if (!"npc".equals(call.definition().group()) || !call.definition().readOnly()) return false;
                    String boundRoleId = call.context().header("X-Role-Id");
                    Object argRoleId = call.rawArguments().get("roleId");
                    if (boundRoleId == null || !boundRoleId.equals(String.valueOf(argRoleId))) {
                        call.context().fail(FrameworkErrorCodes.AI_TOOL_REJECTED,
                                "Cross-player access denied: bound role " + boundRoleId + ", requested " + argRoleId);
                        return false;
                    }
                }
                return true;
            }
        };

        return McpTools.builder()
                .codec(aiCodec)
                .executor(runtimeExecutor)
                .intercept(List.of(playerScopeGuard))
                .timeout(Duration.ofSeconds(10))
                .resultAdapter(res -> res instanceof HttpResult r
                        ? (r.code() == 0 ? McpToolResult.ok(r.data()) : McpToolResult.fail(r.code(), r.msg()))
                        : null)
                .register(List.of(bagService))
                .build();
    }

    @Bean
    public McpEndpoint mcpEndpoint(McpTools mcpTools, Environment env) {
        int port = env.getProperty("ai.mcp.port", Integer.class, 8082);
        String mcpToken = env.getProperty("ai.mcp.token", "dev-mcp-token");
        NetworkServer server = NetworkServer.start(new InetSocketAddress("127.0.0.1", port),
                HttpInitializer.http()
                        .contextPath("/mcp")
                        .addLast("mcp-auth", new BearerTokenAuthHandler(mcpToken))
                        .handler(McpHttpHandler.builder(mcpTools)
                                .serverInfo("managame-example", "1.0.0")
                                .instructions("Game server MCP tools for NPC queries and GM operations")
                                .build())
                        .build());
        return new McpEndpoint(server);
    }

    /** 包装 MCP HTTP 监听，避免与客户端游戏服 {@code NetworkServer} Bean 按类型冲突。 */
    public static final class McpEndpoint implements AutoCloseable {
        private final NetworkServer server;

        McpEndpoint(NetworkServer server) { this.server = server; }

        public int port() { return server.port(); }

        @Override public void close() { server.close(); }
    }
}
