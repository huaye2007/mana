package cn.managame.gateway.filter;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;

import java.util.List;
import java.util.Objects;

public final class FilterChain {
    private final List<GatewayFilter> filters;

    public FilterChain(List<GatewayFilter> filters) {
        this.filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
    }

    public boolean onConnect(GatewaySession session) {
        int accepted = 0;
        try {
            for (; accepted < filters.size(); accepted++) {
                if (!filters.get(accepted).onConnect(session)) {
                    rollback(session, accepted);
                    return false;
                }
            }
            return true;
        } catch (RuntimeException error) {
            rollback(session, accepted);
            throw error;
        }
    }

    public int onPacket(GatewaySession session, GatewayPacket packet) {
        for (GatewayFilter filter : filters) {
            int code = filter.onPacket(session, packet);
            if (code != GatewayErrorCode.OK) return code;
        }
        return GatewayErrorCode.OK;
    }

    public void onDisconnect(GatewaySession session) {
        for (int i = filters.size() - 1; i >= 0; i--) {
            try { filters.get(i).onDisconnect(session); } catch (RuntimeException ignored) { }
        }
    }

    private void rollback(GatewaySession session, int accepted) {
        for (int i = accepted - 1; i >= 0; i--) {
            try { filters.get(i).onDisconnect(session); } catch (RuntimeException ignored) { }
        }
    }
}
