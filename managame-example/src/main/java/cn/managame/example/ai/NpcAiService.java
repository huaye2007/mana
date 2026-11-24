package cn.managame.example.ai;

import cn.managame.Metadata;
import cn.managame.MetadataKey;
import cn.managame.ai.AiCall;
import cn.managame.ai.AiClient;
import cn.managame.ai.AiRequest;
import cn.managame.ai.AiResult;
import cn.managame.ai.AiStreamEvent;
import cn.managame.ai.AiStreamListener;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * 游戏服里的 AI 业务入口：在 {@link GameRuntime} 玩家路由上发起非阻塞 AI 调用，通过 {@link Continuation}
 * 回到原路由校验并应用结果，演示不同玩家/不同 NPC 会话隔离、会话代号重置、SSE 流式取消、结构化选项决策与降级。
 */
@Service
public class NpcAiService {
    public static final MetadataKey<String> TRACE_ID = MetadataKey.stringKey(101);

    private final GameRuntime runtime;
    private final AiClient aiClient;
    /** 记录每个 (roleId:npcId) 当前会话代号，重置会话时加 1 防止在途旧回复污染新会话。 */
    private final Map<String, Integer> dialogueEpochs = new ConcurrentHashMap<>();

    public NpcAiService(GameRuntime runtime, AiClient aiClient) {
        this.runtime = runtime;
        this.aiClient = aiClient;
    }

    public String sessionId(long roleId, long npcId) {
        int epoch = dialogueEpochs.getOrDefault(roleId + ":" + npcId, 1);
        return "npc:" + roleId + ":" + npcId + ":" + epoch;
    }

    /** NPC 多轮对话：在玩家路由上发出调用，经 Continuation 回到同一路由完成状态核对与应答。 */
    public CompletableFuture<NpcChatRes> talk(long roleId, long npcId, String npcName, String text) {
        CompletableFuture<NpcChatRes> promise = new CompletableFuture<>();
        Metadata md = Metadata.builder().put(TRACE_ID, "trace-" + UUID.randomUUID()).build();
        GameContext playerCtx = new GameContext(DomainConstant.PLAYER, roleId, (byte) 1, roleId, md);
        runtime.execute(playerCtx, ctx -> {
            int epoch = dialogueEpochs.getOrDefault(roleId + ":" + npcId, 1);
            String sid = "npc:" + roleId + ":" + npcId + ":" + epoch;
            Continuation back = runtime.continuation();
            NpcChatReq req = new NpcChatReq(sid, roleId, npcId, npcName, text);
            aiClient.call(AiRequest.of("/npc/chat", req, ctx.metadata()), NpcChatRes.class, res ->
                    back.execute(c -> {
                        if (Context.current().domain() != DomainConstant.PLAYER || Context.current().routeKey() != roleId) {
                            promise.completeExceptionally(new IllegalStateException("Continuation ran on wrong route"));
                            return;
                        }
                        if (!res.isSuccess()) {
                            promise.completeExceptionally(new IllegalStateException("AI chat failed: " + res));
                            return;
                        }
                        int currentEpoch = dialogueEpochs.getOrDefault(roleId + ":" + npcId, 1);
                        if (currentEpoch != epoch) {
                            promise.complete(new NpcChatRes(sid, "[stale dialogue ignored]"));
                            return;
                        }
                        promise.complete(res.data());
                    }));
        });
        return promise;
    }

    /** NPC 流式对话（SSE）：每个分片实时推送给上层，返回的 AiCall 可随时 cancel（如玩家下线）。 */
    public AiCall streamTalk(long roleId, long npcId, String npcName, String text,
            Consumer<String> onToken, Consumer<AiResult<Void>> onDone) {
        Metadata md = Metadata.builder().put(TRACE_ID, "trace-" + UUID.randomUUID()).build();
        NpcChatReq req = new NpcChatReq(sessionId(roleId, npcId), roleId, npcId, npcName, text);
        return aiClient.stream(AiRequest.of("/npc/stream", req, md), new AiStreamListener() {
            @Override public void onEvent(AiStreamEvent event) {
                if ("token".equals(event.event()) || "message".equals(event.event())) {
                    onToken.accept(event.data());
                }
            }
            @Override public void onComplete(AiResult<Void> result) {
                onDone.accept(result);
            }
        });
    }

    /** NPC 结构化选项决策：AI 从 allowedActions 中做选择，游戏服回到玩家路由校验合法性并在失败/超时时降级。 */
    public CompletableFuture<NpcAction> decide(long roleId, long npcId, String situation,
            List<NpcAction> allowedActions, NpcAction fallback, Duration timeout) {
        CompletableFuture<NpcAction> promise = new CompletableFuture<>();
        GameContext playerCtx = GameContext.of(DomainConstant.PLAYER, roleId);
        runtime.execute(playerCtx, ctx -> {
            Continuation back = runtime.continuation();
            NpcDecideReq req = new NpcDecideReq(sessionId(roleId, npcId), roleId, npcId, situation, allowedActions);
            aiClient.call(AiRequest.of("/npc/decide", req, ctx.metadata()).withTimeout(timeout), NpcDecideRes.class, res ->
                    back.execute(c -> {
                        if (!res.isSuccess() || res.data() == null || res.data().action() == null
                                || !allowedActions.contains(res.data().action())) {
                            promise.complete(fallback);
                        } else {
                            promise.complete(res.data().action());
                        }
                    }));
        });
        return promise;
    }

    /** 重置某玩家与某 NPC 的对话：在玩家路由上递增代号，同时发 DELETE 清理外部 Spring AI 侧的 ChatMemory。 */
    public CompletableFuture<Void> resetDialogue(long roleId, long npcId) {
        CompletableFuture<Void> promise = new CompletableFuture<>();
        GameContext playerCtx = GameContext.of(DomainConstant.PLAYER, roleId);
        runtime.execute(playerCtx, ctx -> {
            String key = roleId + ":" + npcId;
            int oldEpoch = dialogueEpochs.getOrDefault(key, 1);
            dialogueEpochs.put(key, oldEpoch + 1);
            String oldSessionId = "npc:" + roleId + ":" + npcId + ":" + oldEpoch;
            aiClient.call(AiRequest.delete("/sessions/" + oldSessionId), Void.class, res -> {
                if (res.isSuccess()) promise.complete(null);
                else promise.completeExceptionally(new IllegalStateException("Reset session failed: " + res));
            });
        });
        return promise;
    }

    /** 运营/GM 自然语言指令入口：外部 Spring AI 会通过 GM 角色调用游戏服的受控 MCP 写工具。 */
    public CompletableFuture<GmAskRes> askGm(String instruction) {
        CompletableFuture<GmAskRes> promise = new CompletableFuture<>();
        aiClient.call("/gm/ask", new GmAskReq(instruction), GmAskRes.class, res -> {
            if (res.isSuccess()) promise.complete(res.data());
            else promise.completeExceptionally(new IllegalStateException("GM ask failed: " + res));
        });
        return promise;
    }
}
