package cn.managame.example.ai;

import cn.managame.FrameworkErrorCodes;
import cn.managame.Metadata;
import cn.managame.ai.AiClient;
import cn.managame.ai.AiEventPublisher;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeException;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 游戏服业务路由上使用的 AI 门面：
 * 事实事件走 {@link AiEventPublisher}（供下游分析/记忆服务订阅聚合），
 * 决策和对话走 {@link AiClient} 的隔离通道（{@code decision} / {@code chat}）。
 */
public final class GameplayAi {
    public static final String DECISION_LANE = "decision";
    public static final String CHAT_LANE = "chat";
    private static final System.Logger LOG = System.getLogger(GameplayAi.class.getName());

    public record UserLoggedIn(long userId, int serverId, long roleId) {}
    public record Purchase(String orderId, long roleId, String item) {}
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

    /** 在决策通道请求装备推荐，回到原路由校验背包版本号和合法候选后再提示。 */
    public void recommend(EquipmentRequest request, LongSupplier inventoryRevision, Consumer<String> suggest) {
        long revision = inventoryRevision.getAsLong();
        var back = runtime.continuation();
        client.call(DECISION_LANE, "recommendEquipment", currentMetadata(), request, Choice.class, Duration.ofSeconds(2))
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

    /** 在决策通道选择 NPC 回合动作，超时、过载或非法选项统一降级为 wait。 */
    public void choose(NpcRequest request, LongSupplier currentTurn, Consumer<String> act) {
        var back = runtime.continuation();
        client.call(DECISION_LANE, "chooseNpcMove", currentMetadata(), request, Choice.class, Duration.ofMillis(250))
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
     * 在独立的 chat 通道流式请求 NPC 对话：文本分片直接推送避免刷爆玩家路由，仅在结束时经 continuation 回路由。
     * 玩家离场或打断对话时取消返回的 future。
     */
    public CompletableFuture<Void> chat(ChatRequest request, Consumer<String> pushChunk, Consumer<Throwable> onDone) {
        var back = runtime.continuation();
        var future = client.stream(CHAT_LANE, "npcChat", currentMetadata(), request, ChatChunk.class,
                Duration.ofSeconds(5), Duration.ofSeconds(30), chunk -> pushChunk.accept(chunk.text()));
        future.whenComplete((ignored, error) -> {
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
