package cn.managame.example.ai;

import cn.managame.ai.annotation.McpParam;
import java.util.List;
import java.util.Map;

/** 游戏服与外部 Spring AI 服务交互用的业务 DTO（以及 MCP 工具参数/返回值）。 */
public final class AiDtos {
    private AiDtos() {}

    public enum NpcAction { ATTACK, TRADE, FLEE }

    public record BagView(long roleId, int gold, Map<Integer, Integer> items, int routeDomain, long routeKey) {}

    public record GrantItemReq(
            @McpParam("幂等操作单号（重试同一笔发放时必须传相同 opId，防止超时重试重复发放）") String opId,
            @McpParam("玩家角色ID") long roleId,
            @McpParam("道具ID（0 表示金币）") int itemId,
            @McpParam("发放数量，单次不超过 100（超额需走人工审批）") int count) {}

    public record NpcChatReq(String sessionId, long roleId, long npcId, String npcName, String text) {}

    public record NpcChatRes(String sessionId, String reply, int totalTokens) {
        public NpcChatRes(String sessionId, String reply) {
            this(sessionId, reply, 0);
        }
    }

    public record NpcDecideReq(String sessionId, long roleId, long npcId, String situation, List<NpcAction> allowedActions) {}

    public record NpcDecideRes(NpcAction action, String reason) {}

    public record GmAskReq(String instruction, Integer serverId) {
        public GmAskReq(String instruction) {
            this(instruction, null);
        }
    }

    public record GmAskRes(String reply, int totalTokens) {
        public GmAskRes(String reply) {
            this(reply, 0);
        }
    }
}
