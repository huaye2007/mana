package cn.managame.example.ai;

import cn.managame.MetadataKey;
import cn.managame.ai.AiCall;
import cn.managame.ai.AiCallback;
import cn.managame.ai.AiClient;
import cn.managame.ai.AiRequest;
import cn.managame.ai.AiResult;
import cn.managame.ai.AiStreamEvent;
import cn.managame.ai.AiStreamListener;
import cn.managame.example.ai.AiDtos.GmAskReq;
import cn.managame.example.ai.AiDtos.GmAskRes;
import cn.managame.example.ai.AiDtos.NpcAction;
import cn.managame.example.ai.AiDtos.NpcChatReq;
import cn.managame.example.ai.AiDtos.NpcChatRes;
import cn.managame.example.ai.AiDtos.NpcDecideReq;
import cn.managame.example.ai.AiDtos.NpcDecideRes;
import cn.managame.runtime.Context;
import cn.managame.runtime.Continuation;
import cn.managame.runtime.GameRuntime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * 游戏服里的 AI 业务入口，写法与 {@link cn.managame.example.rpc.GameRpc} 一致：必须在当前玩家的
 * {@link GameRuntime} 任务里调用，不返回 {@code CompletableFuture}，只返回可随时取消的 {@link AiCall}；
 * 所有结果回调都经 {@link Continuation} 回到发起调用的那条玩家路由上执行。
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

    /** NPC 多轮对话：在当前玩家路由任务中调用，回调经 Continuation 回到同一条玩家路由执行。 */
    public AiCall talk(long npcId, String npcName, String text, AiCallback<NpcChatRes> onResponse) {
        Context ctx = Context.current();
        long roleId = ctx.routeKey();
        int epoch = dialogueEpochs.getOrDefault(roleId + ":" + npcId, 1);
        String sid = "npc:" + roleId + ":" + npcId + ":" + epoch;
        Continuation back = runtime.continuation();
        NpcChatReq req = new NpcChatReq(sid, roleId, npcId, npcName, text);
        return aiClient.call(AiRequest.of("/npc/chat", req, ctx.metadata()), NpcChatRes.class, res ->
                back.execute(c -> {
                    if (res.isSuccess() && dialogueEpochs.getOrDefault(roleId + ":" + npcId, 1) != epoch) {
                        onResponse.onResponse(AiResult.ok(res.status(), new NpcChatRes(sid, "[stale dialogue ignored]")));
                        return;
                    }
                    onResponse.onResponse(res);
                }));
    }

    /**
     * NPC 流式对话（SSE）：每个分片立即回调 {@code onToken}（可直接推给客户端连接），
     * 结束或取消时经 Continuation 回到原玩家路由执行 {@code onDone}；返回的 {@link AiCall} 可随时 cancel。
     */
    public AiCall streamTalk(long npcId, String npcName, String text,
            Consumer<String> onToken, AiCallback<Void> onDone) {
        Context ctx = Context.current();
        long roleId = ctx.routeKey();
        Continuation back = runtime.continuation();
        NpcChatReq req = new NpcChatReq(sessionId(roleId, npcId), roleId, npcId, npcName, text);
        return aiClient.stream(AiRequest.of("/npc/stream", req, ctx.metadata()), new AiStreamListener() {
            @Override public void onEvent(AiStreamEvent event) {
                if ("token".equals(event.event()) || "message".equals(event.event())) {
                    onToken.accept(event.data());
                }
            }

            @Override public void onComplete(AiResult<Void> result) {
                back.execute(c -> onDone.onResponse(result));
            }
        });
    }

    /** NPC 结构化选项决策：在当前玩家路由调用，回到同一路由校验选项合法性并在超时/失败时降级为 {@code fallback}。 */
    public AiCall decide(long npcId, String situation, List<NpcAction> allowedActions,
            NpcAction fallback, Duration timeout, Consumer<NpcAction> onDecision) {
        Context ctx = Context.current();
        long roleId = ctx.routeKey();
        Continuation back = runtime.continuation();
        NpcDecideReq req = new NpcDecideReq(sessionId(roleId, npcId), roleId, npcId, situation, allowedActions);
        return aiClient.call(AiRequest.of("/npc/decide", req, ctx.metadata()).withTimeout(timeout),
                NpcDecideRes.class, res -> back.execute(c -> {
                    if (!res.isSuccess() || res.data() == null || res.data().action() == null
                            || !allowedActions.contains(res.data().action())) {
                        onDecision.accept(fallback);
                    } else {
                        onDecision.accept(res.data().action());
                    }
                }));
    }

    /** 重置当前玩家与指定 NPC 的对话：递增游戏侧代号，同时发 DELETE 清理外部 Spring AI 侧的 ChatMemory。 */
    public AiCall resetDialogue(long npcId, AiCallback<Void> onDone) {
        Context ctx = Context.current();
        long roleId = ctx.routeKey();
        String key = roleId + ":" + npcId;
        int oldEpoch = dialogueEpochs.getOrDefault(key, 1);
        dialogueEpochs.put(key, oldEpoch + 1);
        String oldSessionId = "npc:" + roleId + ":" + npcId + ":" + oldEpoch;
        Continuation back = runtime.continuation();
        return aiClient.call(AiRequest.delete("/sessions/" + oldSessionId, ctx.metadata()),
                Void.class, res -> back.execute(c -> onDone.onResponse(res)));
    }

    /** 运营/GM 自然语言指令入口：外部 Spring AI 会通过 GM 角色调用游戏服的受控 MCP 写工具。 */
    public AiCall askGm(String instruction, AiCallback<GmAskRes> onResponse) {
        if (Context.hasCurrent()) {
            Context ctx = Context.current();
            Continuation back = runtime.continuation();
            return aiClient.call(AiRequest.of("/gm/ask", new GmAskReq(instruction), ctx.metadata()),
                    GmAskRes.class, res -> back.execute(c -> onResponse.onResponse(res)));
        }
        return aiClient.call("/gm/ask", new GmAskReq(instruction), GmAskRes.class, onResponse);
    }
}
