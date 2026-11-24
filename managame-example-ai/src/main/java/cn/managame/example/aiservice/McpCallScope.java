package cn.managame.example.aiservice;

import java.util.function.Supplier;

/**
 * 在一次同步 AI 请求执行期间绑定当前调用者身份（NPC / GM、绑定的玩家 roleId、链路 traceId），
 * 供 Spring AI 单例 MCP Client 的 {@code McpSyncHttpClientRequestCustomizer} 透传到游戏服 {@code /mcp} 请求头，
 * 由游戏服的 {@code McpToolInterceptor} 做强校验以防止提示词注入导致跨玩家越权。
 */
public final class McpCallScope {
    public record Caller(String role, Long roleId, String traceId) {}

    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

    private McpCallScope() {}

    public static Caller current() { return CURRENT.get(); }

    public static <T> T run(String role, Long roleId, String traceId, Supplier<T> action) {
        Caller previous = CURRENT.get();
        CURRENT.set(new Caller(role, roleId, traceId));
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
