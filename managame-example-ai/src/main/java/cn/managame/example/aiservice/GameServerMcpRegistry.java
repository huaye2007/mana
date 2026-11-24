package cn.managame.example.aiservice;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

/**
 * 多区服 MCP 客户端与工具目录注册表：
 * <ul>
 *   <li>按 {@code serverId} 懒创建并缓存各游戏服的 {@link McpSyncClient}——AI 服务可先于游戏服启动，互不阻塞</li>
 *   <li>每次请求按 {@link McpCallScope} 注入对应的 NPC / GM 独立 Token 及绑定玩家 {@code X-Role-Id}、{@code X-Trace-Id}</li>
 *   <li>游戏服热更或滚动升级新增 {@code @McpTool} 后，调用 {@link #refreshTools} 即可刷新缓存的工具目录，无需重启 AI 服务</li>
 * </ul>
 */
@Component
public class GameServerMcpRegistry implements AutoCloseable {
    private static final String DEFAULT_KEY = "default";

    private final GameMcpProperties props;
    private final McpSyncHttpClientRequestCustomizer requestCustomizer;
    private final Map<String, ServerHandle> handles = new ConcurrentHashMap<>();

    public GameServerMcpRegistry(GameMcpProperties props) {
        this.props = props;
        this.requestCustomizer = (builder, method, uri, body, context) -> {
            McpCallScope.Caller caller = McpCallScope.current();
            if (caller == null) {
                // 初始化与拉取完整工具目录时使用 GM 身份获取全集，再由 npcTools / gmTools 按注解分流
                builder.setHeader("Authorization", "Bearer " + props.effectiveGmToken());
                builder.setHeader("X-Caller", "gm");
            } else {
                String bearer = "gm".equalsIgnoreCase(caller.role()) ? props.effectiveGmToken() : props.getToken();
                builder.setHeader("Authorization", "Bearer " + bearer);
                builder.setHeader("X-Caller", caller.role());
                if (caller.roleId() != null) builder.setHeader("X-Role-Id", String.valueOf(caller.roleId()));
                if (caller.traceId() != null && !caller.traceId().isBlank()) {
                    builder.setHeader("X-Trace-Id", caller.traceId());
                }
                if (caller.hasDeadline()) {
                    long remainingMs = caller.remainingMillis();
                    if (remainingMs <= 0) {
                        throw new IllegalStateException("Caller X-Ai-Timeout-Ms deadline already expired before MCP tool call");
                    }
                    long boundedMs = Math.min(remainingMs, props.getRequestTimeout().toMillis());
                    builder.timeout(Duration.ofMillis(Math.max(1L, boundedMs)));
                }
            }
        };
    }

    /** 获取指定游戏服面向 NPC 的只读工具集（自动过滤掉 GM 工具与全局统计工具）。 */
    public ToolCallbackProvider npcTools(String serverId) {
        return handleOf(serverId).npcTools;
    }

    /** 获取指定游戏服面向 GM 运营助手的完整工具集。 */
    public ToolCallbackProvider gmTools(String serverId) {
        return handleOf(serverId).gmTools;
    }

    /** 刷新指定游戏服（为空时刷新全部已连接游戏服）的 MCP {@code tools/list} 缓存。 */
    public int refreshTools(String serverId) {
        if (serverId != null && !serverId.isBlank()) {
            ServerHandle h = handles.get(normalizeKey(serverId));
            if (h == null) return 0;
            h.invalidate();
            return 1;
        }
        handles.values().forEach(ServerHandle::invalidate);
        return handles.size();
    }

    private ServerHandle handleOf(String serverId) {
        String key = normalizeKey(serverId);
        return handles.computeIfAbsent(key, this::connectServer);
    }

    private ServerHandle connectServer(String key) {
        String baseUrl = props.resolveUrl(DEFAULT_KEY.equals(key) ? null : key);
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(props.getEndpoint())
                .addHttpRequestCustomizer(requestCustomizer)
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(props.getRequestTimeout())
                .build();
        // 以无绑定身份初始化握手，随后工具发现缓存由 SyncMcpToolCallbackProvider 按需拉取
        McpCallScope.clearRun(client::initialize);

        SyncMcpToolCallbackProvider npcTools = SyncMcpToolCallbackProvider.builder()
                .mcpClients(List.of(client))
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .toolFilter((conn, tool) -> tool.annotations() != null
                        && Boolean.TRUE.equals(tool.annotations().readOnlyHint())
                        && !"online_count".equals(tool.name()))
                .build();
        SyncMcpToolCallbackProvider gmTools = SyncMcpToolCallbackProvider.builder()
                .mcpClients(List.of(client))
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .build();
        // 在初始化阶段用无绑定身份预热一次完整工具目录，避免首次处于 NPC Scope 时拉到裁剪后的目录影响 gmTools
        McpCallScope.clearRun(() -> {
            npcTools.getToolCallbacks();
            gmTools.getToolCallbacks();
        });
        return new ServerHandle(client, npcTools, gmTools);
    }

    private String normalizeKey(String serverId) {
        if (serverId == null || serverId.isBlank()) return DEFAULT_KEY;
        return props.getServers().containsKey(serverId) ? serverId : DEFAULT_KEY;
    }

    @Override public void close() {
        handles.values().forEach(h -> {
            try {
                h.client.close();
            } catch (Exception ignored) {
            }
        });
        handles.clear();
    }

    private record ServerHandle(
            McpSyncClient client,
            SyncMcpToolCallbackProvider npcTools,
            SyncMcpToolCallbackProvider gmTools) {
        void invalidate() {
            npcTools.invalidateCache();
            gmTools.invalidateCache();
            McpCallScope.clearRun(() -> {
                npcTools.getToolCallbacks();
                gmTools.getToolCallbacks();
            });
        }
    }
}
