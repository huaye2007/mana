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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 玩家背包业务服务：同一套方法既可作为游戏内/HTTP 业务方法，又通过 {@link McpTool} 直接暴露给外部 Spring AI 服务。
 * 通过 {@code @McpTool(routeKey = "roleId")} 在启动期编译路由键提取器，每个工具调用都严格串行运行在目标玩家的
 * {@code GameRuntime} 路由上；写工具带 {@code opId} 幂等去重与高价值单次上限拦截。
 */
@Service
@Domain(DomainConstant.PLAYER)
public class PlayerBagService {
    public static final int ITEM_LIMIT_EXCEEDED = 10_001;
    public static final int OP_ID_REQUIRED = 10_002;
    private static final int MAX_RECENT_OPS_PER_ROLE = 64;

    private final Map<Long, Integer> goldByRole = new ConcurrentHashMap<>();
    private final Map<Long, Map<Integer, Integer>> itemsByRole = new ConcurrentHashMap<>();
    /** 每个玩家最近已成功入账的幂等单号结果（仅在该玩家的串行路由内读写）。 */
    private final Map<Long, Map<String, BagView>> processedGrants = new ConcurrentHashMap<>();

    public PlayerBagService() {
        goldByRole.put(1001L, 500);
        itemsByRole.put(1001L, new ConcurrentHashMap<>(Map.of(100, 3, 200, 1)));
        goldByRole.put(1002L, 30);
        itemsByRole.put(1002L, new ConcurrentHashMap<>(Map.of(100, 1)));
    }

    @McpTool(name = "query_bag", description = "Query a player's current gold and bag items",
            group = "npc", readOnly = true, routeKey = "roleId")
    public BagView queryBag(@McpParam("Player role id") long roleId) {
        Context ctx = Context.current();
        int gold = goldByRole.getOrDefault(roleId, 0);
        Map<Integer, Integer> items = Map.copyOf(itemsByRole.getOrDefault(roleId, Map.of()));
        return new BagView(roleId, gold, items, ctx.domain(), ctx.routeKey());
    }

    @HttpMethod(value = "/grant", routeKey = "roleId")
    @McpTool(name = "grant_item",
            description = "Grant gold (itemId=0) or items to a player with idempotency key opId; count must be 1..100",
            group = "gm", idempotent = true, routeKey = "roleId")
    public HttpResult grantItem(GrantItemReq req) {
        if (req.opId() == null || req.opId().isBlank()) {
            return HttpResult.fail(OP_ID_REQUIRED, "Idempotency key 'opId' is required for grant_item");
        }
        if (req.count() <= 0 || req.count() > 100) {
            return HttpResult.fail(ITEM_LIMIT_EXCEEDED,
                    "Grant count must be between 1 and 100; larger grants require manual GM approval");
        }
        Map<String, BagView> history = processedGrants.computeIfAbsent(req.roleId(), id -> new LinkedHashMap<>() {
            @Override protected boolean removeEldestEntry(Map.Entry<String, BagView> eldest) {
                return size() > MAX_RECENT_OPS_PER_ROLE;
            }
        });
        BagView duplicate = history.get(req.opId());
        if (duplicate != null) {
            return HttpResult.ok(duplicate);
        }
        if (req.itemId() == 0) {
            goldByRole.merge(req.roleId(), req.count(), Integer::sum);
        } else {
            itemsByRole.computeIfAbsent(req.roleId(), id -> new ConcurrentHashMap<>())
                    .merge(req.itemId(), req.count(), Integer::sum);
        }
        BagView updated = queryBag(req.roleId());
        history.put(req.opId(), updated);
        return HttpResult.ok(updated);
    }

    @McpTool(name = "online_count", description = "Query how many players currently have bags loaded",
            group = "gm", readOnly = true)
    public int onlineCount() {
        return goldByRole.size();
    }

    public int goldOf(long roleId) { return goldByRole.getOrDefault(roleId, 0); }
}
