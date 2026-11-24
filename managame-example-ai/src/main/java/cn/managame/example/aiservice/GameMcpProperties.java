package cn.managame.example.aiservice;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 游戏服 MCP 连接路由配置：支持单服默认地址（{@code game.mcp.default-url}）与多区服映射表（{@code game.mcp.servers.<serverId>}），
 * 并支持为 NPC 与 GM 分别配置独立 Bearer Token。
 */
@ConfigurationProperties(prefix = "game.mcp")
public class GameMcpProperties {
    private String defaultUrl = "http://127.0.0.1:8082";
    private String endpoint = "/mcp";
    private String token = "dev-mcp-token";
    private String gmToken = "";
    private Duration requestTimeout = Duration.ofSeconds(15);
    private Map<String, String> servers = new HashMap<>();

    public String getDefaultUrl() { return defaultUrl; }
    public void setDefaultUrl(String defaultUrl) { this.defaultUrl = defaultUrl; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getGmToken() { return gmToken; }
    public void setGmToken(String gmToken) { this.gmToken = gmToken; }

    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }

    public Map<String, String> getServers() { return servers; }
    public void setServers(Map<String, String> servers) { this.servers = servers != null ? servers : new HashMap<>(); }

    public String resolveUrl(String serverId) {
        if (serverId != null && !serverId.isBlank()) {
            String mapped = servers.get(serverId);
            if (mapped != null && !mapped.isBlank()) return mapped;
        }
        return defaultUrl;
    }

    public String effectiveGmToken() {
        return gmToken != null && !gmToken.isBlank() ? gmToken : token;
    }
}
