package cn.managame.example.aiservice;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 核心装配：
 * <ul>
 *   <li>{@link ChatMemory}：基于 {@code JdbcChatMemoryRepository} 持久化多轮记忆，支持 AI 服务无状态多实例水平扩容与重启续接</li>
 *   <li>{@link GameServerMcpRegistry}：按区服 {@code serverId} 懒加载路由到玩家所在游戏服的 {@code /mcp} 端点，并隔离只读 NPC 工具与 GM 写工具</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(GameMcpProperties.class)
public class AiServiceConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    /** 带 JDBC 持久化多轮会话记忆的 ChatClient，用于 NPC 对话与流式对话。 */
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
}
