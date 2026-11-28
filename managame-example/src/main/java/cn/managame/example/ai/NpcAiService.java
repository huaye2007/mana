package cn.managame.example.ai;

import cn.managame.FrameworkErrorCodes;
import cn.managame.IdLayout;
import cn.managame.MetadataKey;
import cn.managame.SnowflakeIdGenerator;
import cn.managame.ai.AiCall;
import cn.managame.ai.AiCallback;
import cn.managame.ai.AiClient;
import cn.managame.ai.AiRequest;
import cn.managame.ai.AiResult;
import cn.managame.example.DomainConstant;
import cn.managame.example.ai.AiDtos.GmAskReq;
import cn.managame.example.ai.AiDtos.GmAskRes;
import cn.managame.example.ai.AiDtos.NpcAction;
import cn.managame.example.ai.AiDtos.NpcChatReq;
import cn.managame.example.ai.AiDtos.NpcChatRes;
import cn.managame.example.ai.AiDtos.NpcDecideReq;
import cn.managame.example.ai.AiDtos.NpcDecideRes;
import cn.managame.runtime.Context;
import cn.managame.runtime.Continuation;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.time.GameTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 游戏服里的 NPC / GM AI 业务模块示例：全服所有业务模块只需注入唯一一个 {@link AiClient}。
 * 因为 {@link AiConfig} 已为 {@code AiClient} 配置了桥接 {@link GameRuntime} 的 {@code AiDispatcher}，业务代码无需
 * 手写 {@code Continuation} 或透传 {@code ctx.metadata()}——单次回调与流式分片均自动回到发起调用的玩家路由上串行执行，
 * 回调内直接按 id 重新取最新实体即可。
 */
@Service
public class NpcAiService {
    public static final MetadataKey<String> TRACE_ID = MetadataKey.stringKey(101);
    private static final AiCall NOOP_CALL = new AiCall() {
        @Override public boolean cancel() { return false; }
        @Override public boolean isDone() { return true; }
    };

    private final GameRuntime runtime;
    private final AtomicReference<AiClient> clientRef;
    private final PlayerAiStateRepository aiStateRepo;
    private final AiAuditLogRepository auditLogRepo;
    private final ContentSafetyFilter safetyFilter;
    private final SnowflakeIdGenerator logIds;
    private final int serverId;

    /** 在途调用表：roleId -> (npcId -> ActiveCall)，完成或断线时自动移除。 */
    private final Map<Long, Map<Long, ActiveCall>> inFlightByRole = new ConcurrentHashMap<>();
    /** 玩家上次发起 AI 对话的单调时间戳（nanos），用于冷却限流。 */
    private final Map<Long, Long> lastCallNanoByRole = new ConcurrentHashMap<>();

    private volatile boolean aiEnabled;
    private volatile long cooldownMillis;
    private volatile int dailyQuota;

    public NpcAiService(GameRuntime runtime, AiClient aiClient, PlayerAiStateRepository aiStateRepo,
            AiAuditLogRepository auditLogRepo, ContentSafetyFilter safetyFilter, Environment env) {
        this.runtime = runtime;
        this.clientRef = new AtomicReference<>(Objects.requireNonNull(aiClient, "aiClient"));
        this.aiStateRepo = aiStateRepo;
        this.auditLogRepo = auditLogRepo;
        this.safetyFilter = safetyFilter;
        this.serverId = env.getProperty("rpc.node", Integer.class, 2);
        this.logIds = new SnowflakeIdGenerator(IdLayout.DEFAULT, serverId);
        this.aiEnabled = env.getProperty("ai.enabled", Boolean.class, true);
        this.cooldownMillis = env.getProperty("ai.player.cooldown-ms", Long.class, 0L);
        this.dailyQuota = env.getProperty("ai.player.daily-quota", Integer.class, 200);
    }

    public int serverId() { return serverId; }

    public boolean isAiEnabled() { return aiEnabled; }

    /** 功能降级开关：关闭时 NPC 对话退回本地脚本台词，选项决策退回规则默认值，不再请求外部 AI 服务。 */
    public void setAiEnabled(boolean aiEnabled) { this.aiEnabled = aiEnabled; }

    public void setCooldownMillis(long cooldownMillis) { this.cooldownMillis = Math.max(0L, cooldownMillis); }

    public void setDailyQuota(int dailyQuota) { this.dailyQuota = Math.max(1, dailyQuota); }

    /** 热替换底层 {@link AiClient}（例如切换网关地址），返回旧实例供调用方在排空后关闭。 */
    public AiClient rotateClient(AiClient next) {
        return clientRef.getAndSet(Objects.requireNonNull(next, "next"));
    }

    /** 当前玩家与指定 NPC 的会话键（含 serverId 与持久化代号，合服/多服与重启均不冲突）。 */
    public String sessionId(long roleId, long npcId) {
        PlayerAiState state = getOrCreateState(roleId);
        int epoch = state.getNpcEpochs().getOrDefault(npcId, 1);
        return formatSessionId(roleId, npcId, epoch);
    }

    public int activeCallCount(long roleId) {
        Map<Long, ActiveCall> map = inFlightByRole.get(roleId);
        return map == null ? 0 : map.size();
    }

    /** NPC 多轮对话：在玩家路由任务中直接调用，回调已由 AiDispatcher 自动切回同一条玩家路由。 */
    public AiCall talk(long npcId, String npcName, String text, AiCallback<NpcChatRes> onResponse) {
        Context ctx = Context.current();
        long roleId = ctx.routeKey();
        PlayerAiState state = getOrCreateState(roleId);
        int epoch = state.getNpcEpochs().getOrDefault(npcId, 1);
        String sid = formatSessionId(roleId, npcId, epoch);

        if (!aiEnabled) {
            Continuation back = runtime.continuation();
            back.execute(c -> onResponse.onResponse(AiResult.ok(200, new NpcChatRes(sid, scriptedFallback(npcName), 0))));
            return NOOP_CALL;
        }
        String limitReason = checkAndConsumeQuota(roleId, npcId, state);
        if (limitReason != null) {
            Continuation back = runtime.continuation();
            back.execute(c -> onResponse.onResponse(AiResult.fail(FrameworkErrorCodes.AI_OVERLOADED, 429, limitReason)));
            return NOOP_CALL;
        }

        ActiveCall slot = registerSlot(roleId, npcId);
        if (slot == null) {
            Continuation back = runtime.continuation();
            back.execute(c -> onResponse.onResponse(AiResult.fail(
                    FrameworkErrorCodes.AI_OVERLOADED, 429, "NPC is still replying to your previous message")));
            return NOOP_CALL;
        }

        String traceId = ctx.metadata().get(TRACE_ID);
        NpcChatReq req = new NpcChatReq(sid, roleId, npcId, npcName, text);
        AiCall call = clientRef.get().call("/npc/chat", req, NpcChatRes.class, res -> {
            removeSlot(roleId, npcId, slot);
            PlayerAiState latest = aiStateRepo.get(roleId);
            int currentEpoch = latest == null ? 1 : latest.getNpcEpochs().getOrDefault(npcId, 1);
            if (res.isSuccess() && currentEpoch != epoch) {
                onResponse.onResponse(AiResult.ok(res.status(), new NpcChatRes(sid, "[stale dialogue ignored]", 0)));
                return;
            }
            AiResult<NpcChatRes> finalRes;
            int tokens = 0;
            if (res.isSuccess() && res.data() != null) {
                tokens = res.data().totalTokens();
                String safeReply = safetyFilter.filter(res.data().reply());
                finalRes = AiResult.ok(res.status(), new NpcChatRes(res.data().sessionId(), safeReply, tokens));
            } else {
                finalRes = res;
            }
            auditLogRepo.insert(new AiAuditLog(logIds.generateId(), "NPC_CHAT", "player", roleId,
                    "/npc/chat", traceId != null ? traceId : "", sid, finalRes.code(), tokens, GameTime.currentTimeMillis()));
            onResponse.onResponse(finalRes);
        });
        slot.bind(call);
        return call;
    }

    /**
     * NPC 流式对话（SSE）：每个分片与结束回调均经 {@code AiDispatcher} 按序在原玩家路由上串行执行，
     * 经过 {@link ContentSafetyFilter.StreamFilter} 过滤后回调；玩家断线或手动 cancel 均会立即中断上游生成。
     */
    public AiCall streamTalk(long npcId, String npcName, String text,
            Consumer<String> onToken, AiCallback<Void> onDone) {
        long roleId = Context.current().routeKey();
        PlayerAiState state = getOrCreateState(roleId);
        int epoch = state.getNpcEpochs().getOrDefault(npcId, 1);
        String sid = formatSessionId(roleId, npcId, epoch);

        if (!aiEnabled) {
            String fallback = scriptedFallback(npcName);
            Continuation back = runtime.continuation();
            back.execute(c -> {
                onToken.accept(fallback);
                onDone.onResponse(AiResult.ok(200, null));
            });
            return NOOP_CALL;
        }
        String limitReason = checkAndConsumeQuota(roleId, npcId, state);
        if (limitReason != null) {
            Continuation back = runtime.continuation();
            back.execute(c -> onDone.onResponse(AiResult.fail(FrameworkErrorCodes.AI_OVERLOADED, 429, limitReason)));
            return NOOP_CALL;
        }

        ActiveCall slot = registerSlot(roleId, npcId);
        if (slot == null) {
            Continuation back = runtime.continuation();
            back.execute(c -> onDone.onResponse(AiResult.fail(
                    FrameworkErrorCodes.AI_OVERLOADED, 429, "NPC stream already in progress")));
            return NOOP_CALL;
        }

        ContentSafetyFilter.StreamFilter streamFilter = safetyFilter.newStreamFilter(onToken);
        NpcChatReq req = new NpcChatReq(sid, roleId, npcId, npcName, text);
        AiCall call = clientRef.get().stream("/npc/stream", req,
                event -> {
                    if (("token".equals(event.event()) || "message".equals(event.event()))
                            && !"[DONE]".equals(event.data().trim())) {
                        streamFilter.accept(event.data());
                    }
                },
                result -> {
                    removeSlot(roleId, npcId, slot);
                    if (result.isSuccess()) streamFilter.finish();
                    onDone.onResponse(result);
                });
        slot.bind(call);
        return call;
    }

    /** NPC 结构化选项决策：在当前玩家路由调用，关闭或异常/超时/越界选项均自动降级为 {@code fallback}。 */
    public AiCall decide(long npcId, String situation, List<NpcAction> allowedActions,
            NpcAction fallback, Duration timeout, Consumer<NpcAction> onDecision) {
        long roleId = Context.current().routeKey();
        if (!aiEnabled) {
            Continuation back = runtime.continuation();
            back.execute(c -> onDecision.accept(fallback));
            return NOOP_CALL;
        }
        NpcDecideReq req = new NpcDecideReq(sessionId(roleId, npcId), roleId, npcId, situation, allowedActions);
        return clientRef.get().call(AiRequest.of("/npc/decide", req).withTimeout(timeout),
                NpcDecideRes.class, res -> {
                    if (!res.isSuccess() || res.data() == null || res.data().action() == null
                            || !allowedActions.contains(res.data().action())) {
                        onDecision.accept(fallback);
                    } else {
                        onDecision.accept(res.data().action());
                    }
                });
    }

    /** 重置当前玩家与指定 NPC 的对话：取消在途请求，持久化递增会话代号，并发 DELETE 清理外部 Spring AI ChatMemory。 */
    public AiCall resetDialogue(long npcId, AiCallback<Void> onDone) {
        long roleId = Context.current().routeKey();
        cancelInFlight(roleId, npcId);
        PlayerAiState state = getOrCreateState(roleId);
        int oldEpoch = state.getNpcEpochs().getOrDefault(npcId, 1);
        state.getNpcEpochs().put(npcId, oldEpoch + 1);
        aiStateRepo.update(state);
        String oldSessionId = formatSessionId(roleId, npcId, oldEpoch);
        return clientRef.get().call(AiRequest.delete("/sessions/" + AiRequest.encodeSegment(oldSessionId)),
                Void.class, onDone);
    }

    /** 玩家断线清理（由网关在 {@code GameRuntime.handOn} 中触发）：立即取消该角色所有在途 AI 请求与 SSE 流。 */
    public void onPlayerDisconnect(long roleId) {
        lastCallNanoByRole.remove(roleId);
        Map<Long, ActiveCall> active = inFlightByRole.remove(roleId);
        if (active != null) {
            active.values().forEach(ActiveCall::cancel);
        }
    }

    /** 角色删号清理：取消在途请求、删除本地持久化状态，并通知外部 AI 服务按角色前缀清理全部历史对话记忆。 */
    public AiCall onRoleDelete(long roleId, AiCallback<Void> onDone) {
        onPlayerDisconnect(roleId);
        aiStateRepo.delete(roleId);
        String prefix = "npc:" + serverId + ":" + roleId + ":";
        AiRequest req = AiRequest.delete("/players/" + AiRequest.encodeSegment(prefix));
        if (!Context.hasCurrent()) {
            req = req.withContext(GameContext.of(DomainConstant.PLAYER, roleId));
        }
        return clientRef.get().call(req, Void.class, onDone);
    }

    /** 运营/GM 自然语言指令入口：可指定目标区服 {@code serverId}（默认当前游戏服）。 */
    public AiCall askGm(String instruction, AiCallback<GmAskRes> onResponse) {
        return askGm(instruction, serverId, onResponse);
    }

    public AiCall askGm(String instruction, int targetServerId, AiCallback<GmAskRes> onResponse) {
        GmAskReq req = new GmAskReq(instruction, targetServerId);
        String traceId = Context.hasCurrent() ? Context.current().metadata().get(TRACE_ID) : "";
        AiRequest aiReq = AiRequest.of("/gm/ask", req);
        if (!Context.hasCurrent()) {
            aiReq = aiReq.withContext(GameContext.of(DomainConstant.PLAYER, 0L));
        }
        return clientRef.get().call(aiReq, GmAskRes.class, res -> {
            int tokens = res.isSuccess() && res.data() != null ? res.data().totalTokens() : 0;
            auditLogRepo.insert(new AiAuditLog(logIds.generateId(), "GM_ASK", "gm", 0L,
                    "/gm/ask", traceId != null ? traceId : "", instruction, res.code(), tokens, GameTime.currentTimeMillis()));
            onResponse.onResponse(res);
        });
    }

    private PlayerAiState getOrCreateState(long roleId) {
        PlayerAiState state = aiStateRepo.get(roleId);
        if (state == null) {
            state = new PlayerAiState();
            state.setRoleId(roleId);
            state.setQuotaDay(GameTime.now().toLocalDate().toEpochDay());
            aiStateRepo.insert(state);
        }
        return state;
    }

    private String checkAndConsumeQuota(long roleId, long npcId, PlayerAiState state) {
        Map<Long, ActiveCall> roleCalls = inFlightByRole.get(roleId);
        if (roleCalls != null && roleCalls.containsKey(npcId)) {
            return "NPC is still replying to your previous message";
        }
        long cdMs = this.cooldownMillis;
        long nowNano = System.nanoTime();
        if (cdMs > 0) {
            Long prev = lastCallNanoByRole.get(roleId);
            if (prev != null && (nowNano - prev) < cdMs * 1_000_000L) {
                return "AI talk is on cooldown";
            }
        }
        long today = GameTime.now().toLocalDate().toEpochDay();
        if (state.getQuotaDay() != today) {
            state.setQuotaDay(today);
            state.setDailyCalls(0);
        }
        if (state.getDailyCalls() >= dailyQuota) {
            return "Daily AI dialogue quota exceeded (" + dailyQuota + ")";
        }
        state.setDailyCalls(state.getDailyCalls() + 1);
        aiStateRepo.update(state);
        lastCallNanoByRole.put(roleId, nowNano);
        return null;
    }

    private ActiveCall registerSlot(long roleId, long npcId) {
        ActiveCall created = new ActiveCall();
        Map<Long, ActiveCall> map = inFlightByRole.computeIfAbsent(roleId, k -> new ConcurrentHashMap<>());
        return map.putIfAbsent(npcId, created) == null ? created : null;
    }

    private void removeSlot(long roleId, long npcId, ActiveCall slot) {
        slot.markFinished();
        Map<Long, ActiveCall> map = inFlightByRole.get(roleId);
        if (map != null) {
            map.remove(npcId, slot);
            if (map.isEmpty()) inFlightByRole.remove(roleId, map);
        }
    }

    private void cancelInFlight(long roleId, long npcId) {
        Map<Long, ActiveCall> map = inFlightByRole.get(roleId);
        if (map != null) {
            ActiveCall removed = map.remove(npcId);
            if (removed != null) removed.cancel();
        }
    }

    private String formatSessionId(long roleId, long npcId, int epoch) {
        return "npc:" + serverId + ":" + roleId + ":" + npcId + ":" + epoch;
    }

    private static String scriptedFallback(String npcName) {
        return "[" + npcName + "] 客官稍坐，老夫正忙着呢，咱们回头再聊。";
    }

    private static final class ActiveCall {
        private final AtomicReference<AiCall> delegate = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();

        void bind(AiCall call) {
            delegate.set(call);
            if (cancelled.get() && !finished.get()) call.cancel();
        }

        void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                AiCall c = delegate.get();
                if (c != null) c.cancel();
            }
        }

        void markFinished() { finished.set(true); }
    }
}
