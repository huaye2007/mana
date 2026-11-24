package cn.managame.example.aiservice;

import cn.managame.example.aiservice.AiServiceDtos.GmAskReq;
import cn.managame.example.aiservice.AiServiceDtos.GmAskRes;
import cn.managame.example.aiservice.AiServiceDtos.NpcChatReq;
import cn.managame.example.aiservice.AiServiceDtos.NpcChatRes;
import cn.managame.example.aiservice.AiServiceDtos.NpcDecideReq;
import cn.managame.example.aiservice.AiServiceDtos.NpcDecideRes;
import cn.managame.example.aiservice.AiServiceDtos.RefreshToolsRes;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * 面向游戏服的 Spring AI 业务接口：
 * <ul>
 *   <li>{@code POST /api/ai/npc/chat}：多轮对话（JDBC 持久化记忆）+ 按 {@code X-Game-Server-Id} 路由 MCP 回查对应区服背包 + 返回 Token 用量</li>
 *   <li>{@code POST /api/ai/npc/stream}：W3C SSE 流式对话，游戏服断线/取消时立即中断上游生成</li>
 *   <li>{@code POST /api/ai/npc/decide}：结构化选项决策（Spring AI Structured Output）</li>
 *   <li>{@code POST /api/ai/gm/ask}：GM 自然语言运营助手（可指定目标区服调用受控幂等写工具）</li>
 *   <li>{@code DELETE /api/ai/sessions/{sessionId}}：清理单个玩家+NPC 会话记忆</li>
 *   <li>{@code DELETE /api/ai/players/{sessionPrefix}}：删号时按角色会话前缀批量清理全部历史对话记忆</li>
 *   <li>{@code POST /api/ai/admin/mcp/refresh}：游戏服滚动发布新增工具后热刷新 MCP 工具目录缓存</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai")
public class GameAiController {
    private final ChatClient conversationalChatClient;
    private final ChatClient statelessChatClient;
    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;
    private final GameServerMcpRegistry mcpRegistry;

    public GameAiController(
            @Qualifier("conversationalChatClient") ChatClient conversationalChatClient,
            @Qualifier("statelessChatClient") ChatClient statelessChatClient,
            ChatMemory chatMemory,
            ChatMemoryRepository chatMemoryRepository,
            GameServerMcpRegistry mcpRegistry) {
        this.conversationalChatClient = conversationalChatClient;
        this.statelessChatClient = statelessChatClient;
        this.chatMemory = chatMemory;
        this.chatMemoryRepository = chatMemoryRepository;
        this.mcpRegistry = mcpRegistry;
    }

    @PostMapping("/npc/chat")
    public NpcChatRes npcChat(
            @RequestBody NpcChatReq req,
            @RequestHeader(value = "X-Game-Server-Id", required = false) String serverId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String systemPrompt = "You are game NPC '" + req.npcName() + "' (id=" + req.npcId()
                + ") talking to player roleId=" + req.roleId()
                + ". Use query_bag when asked about the player's gold or items. Keep replies concise.";
        ChatResponse response = McpCallScope.run("npc", req.roleId(), traceId, () ->
                conversationalChatClient.prompt()
                        .system(systemPrompt)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, req.sessionId()))
                        .tools(mcpRegistry.npcTools(serverId))
                        .user(req.text())
                        .call()
                        .chatResponse());
        String reply = extractText(response);
        return new NpcChatRes(req.sessionId(), reply, extractTotalTokens(response));
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
            @RequestHeader(value = "X-Game-Server-Id", required = false) String headerServerId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String targetServerId = req.serverId() != null ? String.valueOf(req.serverId()) : headerServerId;
        ChatResponse response = McpCallScope.run("gm", null, traceId, () ->
                statelessChatClient.prompt()
                        .system("You are a game operations assistant. Use the game server MCP tools to answer or perform GM actions.")
                        .tools(mcpRegistry.gmTools(targetServerId))
                        .user(req.instruction())
                        .call()
                        .chatResponse());
        return new GmAskRes(extractText(response), extractTotalTokens(response));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable("sessionId") String sessionId) {
        chatMemory.clear(sessionId);
        return ResponseEntity.noContent().build();
    }

    /** 删号清理：按 {@code npc:<serverId>:<roleId>:} 前缀删除该角色与所有 NPC 的持久化对话记忆。 */
    @DeleteMapping("/players/{sessionPrefix}")
    public ResponseEntity<Void> clearPlayerSessions(@PathVariable("sessionPrefix") String sessionPrefix) {
        for (String id : chatMemoryRepository.findConversationIds()) {
            if (id.startsWith(sessionPrefix)) {
                chatMemory.clear(id);
            }
        }
        return ResponseEntity.noContent().build();
    }

    /** 游戏服滚动发布新增工具后，主动刷新指定区服或全部区服缓存的 MCP 工具目录。 */
    @PostMapping("/admin/mcp/refresh")
    public RefreshToolsRes refreshMcpTools(@RequestParam(value = "serverId", required = false) String serverId) {
        return new RefreshToolsRes(mcpRegistry.refreshTools(serverId));
    }

    private static String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) return "";
        String text = response.getResult().getOutput().getText();
        return text != null ? text : "";
    }

    private static int extractTotalTokens(ChatResponse response) {
        if (response == null || response.getMetadata() == null) return 0;
        Usage usage = response.getMetadata().getUsage();
        if (usage == null || usage.getTotalTokens() == null) return 0;
        return usage.getTotalTokens();
    }
}
