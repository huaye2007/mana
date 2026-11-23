package cn.managame.example.ai;

import cn.managame.FrameworkErrorCodes;
import cn.managame.Metadata;
import cn.managame.ai.AiClient;
import cn.managame.ai.AiClient.CallOptions;
import cn.managame.ai.AiEventPublisher;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeException;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 游戏服业务路由上使用的 AI 门面：
 * <ul>
 *   <li>事实事件走 {@link AiEventPublisher}（供下游分析/记忆服务订阅聚合）；</li>
 *   <li>决策调用 {@code decision} 服务（按 roleId 限单玩家并发防刷）；</li>
 *   <li>NPC 聊天调用 {@code chat} 服务，按 {@code roleId:npcId} 做单会话互斥顶替；
 *       流被打断时上报 {@link ChatTruncated} 事件，便于外部 AI 服务对齐该玩家对该 NPC 的真实可见记忆。</li>
 * </ul>
 */
public final class GameplayAi {
    public static final String DECISION_SERVICE = "decision";
    public static final String CHAT_SERVICE = "chat";
    private static final System.Logger LOG = System.getLogger(GameplayAi.class.getName());

    public record UserLoggedIn(long userId, int serverId, long roleId) {}
    public record Purchase(String orderId, long roleId, String item) {}
    public record ChatTruncated(String requestId, long roleId, String npcId, int deliveredChars) {}
    public record EquipmentRequest(long roleId, List<String> available) {
        public EquipmentRequest { available = List.copyOf(available); }
    }
    public record NpcRequest(long turn, List<String> available) {
        public NpcRequest { available = List.copyOf(available); }
    }
    public record ChatRequest(long roleId, String npcId, String message) {}
    public record Choice(String selected) {}
    public record ChatChunk(String text) {}

    private final AiEventPublisher events;
    private final AiClient client;
    private final GameRuntime runtime;

    public GameplayAi(AiEventPublisher events, AiClient client, GameRuntime runtime) {
        this.events = events;
        this.client = client;
        this.runtime = runtime;
    }

    /** 玩家登录后上报事实事件，按 userId 分区保序。 */
    public void onLogin(long userId, int serverId, long roleId) {
        events.publish(Long.toString(userId), currentMetadata(), new UserLoggedIn(userId, serverId, roleId));
    }

    /** 玩家购买后上报事实事件，按 roleId 分区保序。 */
    public void purchased(Purchase purchase) {
        events.publish(Long.toString(purchase.roleId()), currentMetadata(), purchase);
    }

    /** 向决策 AI 服务请求装备推荐，按 roleId 隔离防刷，回到原路由校验背包版本号和合法候选后再提示。 */
    public void recommend(EquipmentRequest request, LongSupplier inventoryRevision, Consumer<String> suggest) {
        long revision = inventoryRevision.getAsLong();
        var back = runtime.continuation();
        var opts = CallOptions.service(DECISION_SERVICE).routingKey(Long.toString(request.roleId())).metadata(currentMetadata());
        client.call(opts, "recommendEquipment", request, Choice.class, Duration.ofSeconds(2))
                .thenAccept(choice -> {
                    try {
                        back.execute(context -> {
                            if (inventoryRevision.getAsLong() == revision && choice.selected() != null
                                    && request.available().contains(choice.selected()))
                                suggest.accept(choice.selected());
                        });
                    } catch (GameRuntimeException refused) {
                        LOG.log(Level.WARNING, "AI recommendation could not return to route: " + FrameworkErrorCodes.describe(refused.errorCode()));
                    }
                });
    }

    /** 向决策 AI 服务选择 NPC 回合动作，超时、过载、熔断或非法选项统一降级为 wait。 */
    public void choose(NpcRequest request, LongSupplier currentTurn, Consumer<String> act) {
        var back = runtime.continuation();
        var opts = CallOptions.service(DECISION_SERVICE).metadata(currentMetadata());
        client.call(opts, "chooseNpcMove", request, Choice.class, Duration.ofMillis(250))
                .whenComplete((choice, error) -> {
                    try {
                        back.execute(context -> {
                            if (currentTurn.getAsLong() != request.turn()) return;
                            String move = error == null && choice != null && choice.selected() != null
                                    && request.available().contains(choice.selected())
                                    ? choice.selected() : "wait";
                            act.accept(move);
                        });
                    } catch (GameRuntimeException refused) {
                        LOG.log(Level.WARNING, "AI decision could not return to route: " + FrameworkErrorCodes.describe(refused.errorCode()));
                    }
                });
    }

    /**
     * 向独立的 chat AI 服务按 {@code roleId:npcId} 流式请求 NPC 对话：
     * 同一玩家对同一 NPC 再发新消息会自动取消旧流；若旧流已向客户端推送过部分文本后被打断或超时，
     * 通过事件流上报 {@link ChatTruncated} 供外部 AI 记忆服务截断未展示内容。
     */
    public CompletableFuture<Void> chat(ChatRequest request, Consumer<String> pushChunk, Consumer<Throwable> onDone) {
        String sessionKey = request.roleId() + ":" + request.npcId();
        String requestId = client.nextRequestId();
        Metadata metadata = currentMetadata();
        var deliveredChars = new AtomicInteger();
        var back = runtime.continuation();
        var opts = CallOptions.service(CHAT_SERVICE).routingKey(sessionKey).requestId(requestId).metadata(metadata);
        var future = client.stream(opts, "npcChat", request, ChatChunk.class,
                Duration.ofSeconds(5), Duration.ofSeconds(30), chunk -> {
                    deliveredChars.addAndGet(chunk.text().length());
                    pushChunk.accept(chunk.text());
                });
        future.whenComplete((ignored, error) -> {
            if (error != null && deliveredChars.get() > 0) {
                events.publish(sessionKey, metadata,
                        new ChatTruncated(requestId, request.roleId(), request.npcId(), deliveredChars.get()));
            }
            try {
                back.execute(context -> onDone.accept(error));
            } catch (GameRuntimeException refused) {
                LOG.log(Level.WARNING, "AI chat completion could not return to route: " + FrameworkErrorCodes.describe(refused.errorCode()));
            }
        });
        return future;
    }

    private static Metadata currentMetadata() {
        return Context.current().metadata();
    }
}
