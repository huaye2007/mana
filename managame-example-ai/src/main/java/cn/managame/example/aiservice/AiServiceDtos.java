package cn.managame.example.aiservice;

import java.util.List;

/** 外部 AI 服务对外暴露的 HTTP/JSON 请求与响应契约（与游戏服保持相同 JSON 字段，支持向后兼容演进）。 */
public final class AiServiceDtos {
    private AiServiceDtos() {}

    public enum NpcAction { ATTACK, TRADE, FLEE }

    public record NpcChatReq(String sessionId, long roleId, long npcId, String npcName, String text) {}

    public record NpcChatRes(String sessionId, String reply, int totalTokens) {}

    public record NpcDecideReq(String sessionId, long roleId, long npcId, String situation, List<NpcAction> allowedActions) {}

    public record NpcDecideRes(NpcAction action, String reason) {}

    public record GmAskReq(String instruction, Integer serverId) {}

    public record GmAskRes(String reply, int totalTokens) {}

    public record RefreshToolsRes(int refreshedServers) {}
}
