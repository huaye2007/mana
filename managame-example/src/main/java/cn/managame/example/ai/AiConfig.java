package cn.managame.example.ai;

import cn.managame.ai.AiClient;
import cn.managame.ai.AiEventPublisher;
import cn.managame.ai.AiEventSink;
import cn.managame.ai.AiJson;
import cn.managame.ai.AiRequest;
import cn.managame.ai.AiTools;
import cn.managame.ai.AiTransport;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.dispatch.Dispatcher;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * 游戏服的 AI 三平面装配：
 * <ul>
 *   <li>出站事件流：{@link AiEventPublisher}，带批字节上限、瞬时重试与失败回调；</li>
 *   <li>出站 AI 调用：{@link AiClient}，按隔离通道（decision 限制单玩家并发防刷，chat 对同一 (roleId:npcId) 会话自动顶替旧流）并配熔断保护；</li>
 *   <li>入站工具目录：{@link AiToolGateway}，按受众（ops / player）隔离工具并可导出纯值描述符给外部网关。</li>
 * </ul>
 * {@code @DependsOn("gameRuntime")} 保证停服时 Spring 先关闭 AI 客户端和事件发布器，再关闭 GameRuntime。
 */
@Configuration
public class AiConfig {
    private static final System.Logger LOG = System.getLogger(AiConfig.class.getName());

    /** 生产环境替换为 Kafka / RocketMQ / 日志管道实现；示例默认打 DEBUG 日志。 */
    @Bean
    public AiEventSink aiEventSink() {
        return batch -> LOG.log(Level.DEBUG, () -> "AI event batch (" + batch.size() + "): " + batch.getFirst().event());
    }

    /** 生产环境替换为访问独立 AI 服务进程的 HTTP / SSE / gRPC / RPC 实现。 */
    @Bean
    public AiTransport aiTransport() {
        return (AiRequest request, Duration remaining) -> {
            throw new UnsupportedOperationException("Configure an AiTransport for external AI service: " + request.operation());
        };
    }

    /** 生产环境替换为持久化提案表或外部审批流客户端。 */
    @Bean
    public AiToolGateway.ProposalSink aiProposalSink() {
        return (caller, tool, message) -> CompletableFuture.completedFuture(
                Map.of("status", "PENDING_APPROVAL", "tool", tool.name(), "proposal", message));
    }

    @Bean
    @DependsOn("gameRuntime")
    public AiEventPublisher aiEventPublisher(AiEventSink sink) {
        return AiEventPublisher.builder(sink)
                .events(List.of(
                        new AiEventPublisher.EventType("user.logged_in", GameplayAi.UserLoggedIn.class),
                        new AiEventPublisher.EventType("purchase.completed", GameplayAi.Purchase.class),
                        new AiEventPublisher.EventType("chat.truncated", GameplayAi.ChatTruncated.class)))
                .limits(1, 4096, 64, Duration.ofMillis(50))
                .maxBatchBytes(512 * 1024)
                .retry(2, Duration.ofMillis(100))
                .build();
    }

    @Bean
    @DependsOn("gameRuntime")
    public AiClient aiClient(AiTransport transport) {
        return AiClient.builder(transport)
                .lane(GameplayAi.DECISION_LANE, 32, 128, 2, AiClient.KeyConflict.REJECT)
                .circuitBreaker(GameplayAi.DECISION_LANE, 5, Duration.ofSeconds(10))
                .lane(GameplayAi.CHAT_LANE, 128, 256, 1, AiClient.KeyConflict.CANCEL_PREVIOUS)
                .circuitBreaker(GameplayAi.CHAT_LANE, 5, Duration.ofSeconds(15))
                .build();
    }

    @Bean
    public GameplayAi gameplayAi(AiEventPublisher events, AiClient client, GameRuntime runtime) {
        return new GameplayAi(events, client, runtime);
    }

    /** 扫描当前游戏服 @Domain 处理器中的 @AiTool，按受众拆成独立目录，64 位 ID 按字符串序列化。 */
    @Bean
    public AiToolGateway aiToolGateway(Dispatcher dispatcher, AiToolGateway.ProposalSink proposals, ApplicationContext context) {
        Collection<Object> handlers = context.getBeansWithAnnotation(Domain.class).values();
        AiJson codec = AiJson.jacksonStringLongs();
        AiTools ops = AiTools.builder().audience(PlayerAiTools.AUDIENCE_OPS).json(codec).register(handlers).build();
        AiTools player = AiTools.builder().audience(PlayerAiTools.AUDIENCE_PLAYER).json(codec).register(handlers).build();
        return new AiToolGateway(dispatcher, proposals, Map.of(
                PlayerAiTools.AUDIENCE_OPS, ops,
                PlayerAiTools.AUDIENCE_PLAYER, player));
    }
}
