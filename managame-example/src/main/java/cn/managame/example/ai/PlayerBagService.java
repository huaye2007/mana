package cn.managame.example.ai;

import cn.managame.ai.annotation.McpParam;
import cn.managame.ai.annotation.McpTool;
import cn.managame.example.DomainConstant;
import cn.managame.example.ai.AiDtos.BagView;
import cn.managame.example.ai.AiDtos.GrantItemReq;
import cn.managame.runtime.Context;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.http.HttpMethod;
import cn.managame.runtime.http.HttpResult;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 玩家背包业务服务：同一套方法既可作为游戏内/HTTP 业务方法，又通过 {@link McpTool} 直接暴露给外部 Spring AI 服务。
 * 在 {@code AiConfig} 的 {@code McpToolExecutor} 调度下，每个工具调用都严格串行运行在目标玩家的 {@code GameRuntime} 路由上。
 */
@Service
@Domain(DomainConstant.PLAYER)
public class PlayerBagService {
    public static final int ITEM_LIMIT_EXCEEDED = 10_001;

    private final Map<Long, Integer> goldByRole = new ConcurrentHashMap<>();
    private final Map<Long, Map<Integer, Integer>> itemsByRole = new ConcurrentHashMap<>();

    public PlayerBagService() {
        goldByRole.put(1001L, 500);
        itemsByRole.put(1001L, new ConcurrentHashMap<>(Map.of(100, 3, 200, 1)));
        goldByRole.put(1002L, 30);
        itemsByRole.put(1002L, new ConcurrentHashMap<>(Map.of(100, 1)));
    }

    @McpTool(name = "query_bag", description = "Query a player's current gold and bag items", group = "npc", readOnly = true)
    public BagView queryBag(@McpParam("Player role id") long roleId) {
        Context ctx = Context.current();
        int gold = goldByRole.getOrDefault(roleId, 0);
        Map<Integer, Integer> items = Map.copyOf(itemsByRole.getOrDefault(roleId, Map.of()));
        return new BagView(roleId, gold, items, ctx.domain(), ctx.routeKey());
    }

    @HttpMethod(value = "/grant", routeKey = "roleId")
    @McpTool(name = "grant_item", description = "Grant gold (itemId=0) or items to a player; count must be 1..100", group = "gm")
    public HttpResult grantItem(GrantItemReq req) {
        if (req.count() <= 0 || req.count() > 100) {
            return HttpResult.fail(ITEM_LIMIT_EXCEEDED, "Grant count must be between 1 and 100");
        }
        if (req.itemId() == 0) {
            goldByRole.merge(req.roleId(), req.count(), Integer::sum);
        } else {
            itemsByRole.computeIfAbsent(req.roleId(), id -> new ConcurrentHashMap<>())
                    .merge(req.itemId(), req.count(), Integer::sum);
        }
        return HttpResult.ok(queryBag(req.roleId()));
    }

    @McpTool(name = "online_count", description = "Query how many players currently have bags loaded", group = "gm", readOnly = true)
    public int onlineCount() {
        return goldByRole.size();
    }

    public int goldOf(long roleId) { return goldByRole.getOrDefault(roleId, 0); }
}
