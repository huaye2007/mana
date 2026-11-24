package cn.managame.example.aiservice;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 核心装配：
 * <ul>
 *   <li>{@link ChatMemory}：按 {@code sessionId}（{@code npc:roleId:npcId:epoch}）隔离不同玩家和不同 NPC 的多轮记忆</li>
 *   <li>{@link McpSyncHttpClientRequestCustomizer}：为每次 MCP 请求注入 Bearer Token 与当前玩家的 {@code X-Caller / X-Role-Id / X-Trace-Id}</li>
 *   <li>{@code npcTools} / {@code gmTools}：基于 MCP Tool annotations 将只读 NPC 工具与 GM 写工具彻底隔离</li>
 * </ul>
 */
@Configuration
public class AiServiceConfig {

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder().maxMessages(20).build();
    }

    /** 带多轮会话记忆的 ChatClient，用于 NPC 对话与流式对话。 */
    @Bean("conversationalChatClient")
    public ChatClient conversationalChatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /** 无记忆的 ChatClient，用于结构化选项决策与 GM 单轮指令，避免污染 NPC 对话上下文。 */
    @Bean("statelessChatClient")
    public ChatClient statelessChatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /** 保持游戏服 @McpTool 定义的原始工具名（如 query_bag、grant_item），不自动加前缀。 */
    @Bean
    public McpToolNamePrefixGenerator mcpToolNamePrefixGenerator() {
        return McpToolNamePrefixGenerator.noPrefix();
    }

    /** 向游戏服 /mcp 发送请求时注入 Bearer Token 与当前请求绑定的调用者身份头。 */
    @Bean
    public McpSyncHttpClientRequestCustomizer gameMcpRequestCustomizer(
            @Value("${game.mcp.token:dev-mcp-token}") String mcpToken) {
        return (builder, method, uri, body, context) -> {
            builder.setHeader("Authorization", "Bearer " + mcpToken);
            McpCallScope.Caller caller = McpCallScope.current();
            if (caller == null) {
                // 启动期 initialize / tools/list 拉取完整目录，再由 npcTools / gmTools 分别过滤
                builder.setHeader("X-Caller", "gm");
            } else {
                builder.setHeader("X-Caller", caller.role());
                if (caller.roleId() != null) builder.setHeader("X-Role-Id", String.valueOf(caller.roleId()));
                if (caller.traceId() != null && !caller.traceId().isBlank()) builder.setHeader("X-Trace-Id", caller.traceId());
            }
        };
    }

    /** NPC 对话可见的工具集：仅暴露只读的背包查询工具，模型看不到任何 GM 写工具。 */
    @Bean("npcTools")
    public ToolCallbackProvider npcTools(List<McpSyncClient> mcpClients) {
        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcpClients)
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .toolFilter((conn, tool) -> tool.annotations() != null
                        && Boolean.TRUE.equals(tool.annotations().readOnlyHint())
                        && !"online_count".equals(tool.name()))
                .build();
    }

    /** GM 助手可见的工具集：包含 query_bag、grant_item、online_count。 */
    @Bean("gmTools")
    public ToolCallbackProvider gmTools(List<McpSyncClient> mcpClients) {
        return SyncMcpToolCallbackProvider.builder()
                .mcpClients(mcpClients)
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .build();
    }
}
