package cn.managame.gateway.filter;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 过滤器责任链。推荐顺序：DdosFilter（IP 级，最便宜的先挡）→
 * RateLimitFilter（会话级）→ AuthFilter（登录 gate）。
 */
public class FilterChain {

    private static final Logger logger = LoggerFactory.getLogger(FilterChain.class);

    private final GatewayFilter[] filters;

    public FilterChain(List<GatewayFilter> filters) {
        this.filters = filters.toArray(new GatewayFilter[0]);
    }

    /** 全部过滤器都同意才接入；某个拒绝后不再问后面的（已通过的会收到 onDisconnect 清理）。 */
    public boolean onConnect(GatewaySession session) {
        for (int i = 0; i < filters.length; i++) {
            if (!filters[i].onConnect(session)) {
                // 前 i 个已登记该连接，回滚它们的状态（如 IP 计数），否则计数泄漏
                for (int j = i - 1; j >= 0; j--) {
                    safeDisconnect(filters[j], session);
                }
                return false;
            }
        }
        return true;
    }

    /** 返回第一个拦截的错误码；全通过返回 OK。 */
    public int onPacket(GatewaySession session, GatewayPacket packet) {
        for (GatewayFilter filter : filters) {
            int code = filter.onPacket(session, packet);
            if (code != GatewayErrorCode.OK) {
                return code;
            }
        }
        return GatewayErrorCode.OK;
    }

    public void onDisconnect(GatewaySession session) {
        for (GatewayFilter filter : filters) {
            safeDisconnect(filter, session);
        }
    }

    private static void safeDisconnect(GatewayFilter filter, GatewaySession session) {
        try {
            filter.onDisconnect(session);
        } catch (RuntimeException e) {
            logger.warn("filter {} onDisconnect threw", filter.getClass().getSimpleName(), e);
        }
    }
}
