package cn.managame.example.aiservice;

import cn.managame.example.aiservice.AiServiceDtos.GmAskReq;
import cn.managame.example.aiservice.AiServiceDtos.GmAskRes;
import cn.managame.example.aiservice.AiServiceDtos.NpcChatReq;
import cn.managame.example.aiservice.AiServiceDtos.NpcChatRes;
import cn.managame.example.aiservice.AiServiceDtos.NpcDecideReq;
import cn.managame.example.aiservice.AiServiceDtos.NpcDecideRes;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * 面向游戏服的 Spring AI 业务接口：
 * <ul>
 *   <li>{@code POST /api/ai/npc/chat}：多轮对话（按 sessionId 隔离记忆）+ 自动通过 MCP 查游戏服背包</li>
 *   <li>{@code POST /api/ai/npc/stream}：W3C SSE 流式对话，游戏服 cancel 时立即中断上游生成</li>
 *   <li>{@code POST /api/ai/npc/decide}：结构化选项决策（Spring AI Structured Output）</li>
 *   <li>{@code POST /api/ai/gm/ask}：GM 自然语言运营助手（调用受控 MCP 写工具）</li>
 *   <li>{@code DELETE /api/ai/sessions/{sessionId}}：清理指定玩家+NPC 会话记忆</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai")
public class GameAiController {
    private final ChatClient conversationalChatClient;
    private final ChatClient statelessChatClient;
    private final ChatMemory chatMemory;
    private final ToolCallbackProvider npcTools;
    private final ToolCallbackProvider gmTools;

    public GameAiController(
            @Qualifier("conversationalChatClient") ChatClient conversationalChatClient,
            @Qualifier("statelessChatClient") ChatClient statelessChatClient,
            ChatMemory chatMemory,
            @Qualifier("npcTools") ToolCallbackProvider npcTools,
            @Qualifier("gmTools") ToolCallbackProvider gmTools) {
        this.conversationalChatClient = conversationalChatClient;
        this.statelessChatClient = statelessChatClient;
        this.chatMemory = chatMemory;
        this.npcTools = npcTools;
        this.gmTools = gmTools;
    }

    @PostMapping("/npc/chat")
    public NpcChatRes npcChat(
            @RequestBody NpcChatReq req,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String systemPrompt = "You are game NPC '" + req.npcName() + "' (id=" + req.npcId()
                + ") talking to player roleId=" + req.roleId()
                + ". Use query_bag when asked about the player's gold or items. Keep replies concise.";
        String reply = McpCallScope.run("npc", req.roleId(), traceId, () ->
                conversationalChatClient.prompt()
                        .system(systemPrompt)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, req.sessionId()))
                        .toolCallbacks(npcTools)
                        .user(req.text())
                        .call()
                        .content());
        return new NpcChatRes(req.sessionId(), reply == null ? "" : reply);
    }

    @PostMapping(path = "/npc/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter npcStream(@RequestBody NpcChatReq req) {
        SseEmitter emitter = new SseEmitter(60_000L);
        AtomicReference<Disposable> subRef = new AtomicReference<>();
        Runnable cancelUpstream = () -> {
            Disposable d = subRef.getAndSet(null);
            if (d != null && !d.isDisposed()) d.dispose();
        };
        emitter.onCompletion(cancelUpstream);
        emitter.onTimeout(cancelUpstream);
        emitter.onError(ex -> cancelUpstream.run());

        String systemPrompt = "You are game NPC '" + req.npcName() + "' (id=" + req.npcId()
                + ") talking to player roleId=" + req.roleId() + ". Reply concisely.";
        Disposable sub = conversationalChatClient.prompt()
                .system(systemPrompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, req.sessionId()))
                .user(req.text())
                .stream()
                .content()
                .subscribe(
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(chunk));
                            } catch (IOException disconnected) {
                                cancelUpstream.run();
                            }
                        },
                        error -> {
                            cancelUpstream.run();
                            emitter.completeWithError(error);
                        },
                        () -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                emitter.complete();
                            } catch (IOException ignored) {
                                emitter.complete();
                            }
                        });
        subRef.set(sub);
        return emitter;
    }

    @PostMapping("/npc/decide")
    public NpcDecideRes npcDecide(@RequestBody NpcDecideReq req) {
        String systemPrompt = "You decide the next action for game NPC " + req.npcId()
                + " facing player " + req.roleId() + ". Choose one action from " + req.allowedActions() + ".";
        NpcDecideRes result = statelessChatClient.prompt()
                .system(systemPrompt)
                .user(req.situation())
                .call()
                .entity(NpcDecideRes.class);
        return result != null ? result : new NpcDecideRes(null, "empty");
    }

    @PostMapping("/gm/ask")
    public GmAskRes gmAsk(
            @RequestBody GmAskReq req,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String reply = McpCallScope.run("gm", null, traceId, () ->
                statelessChatClient.prompt()
                        .system("You are a game operations assistant. Use the game server MCP tools to answer or perform GM actions.")
                        .toolCallbacks(gmTools)
                        .user(req.instruction())
                        .call()
                        .content());
        return new GmAskRes(reply == null ? "" : reply);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable("sessionId") String sessionId) {
        chatMemory.clear(sessionId);
        return ResponseEntity.noContent().build();
    }
}
