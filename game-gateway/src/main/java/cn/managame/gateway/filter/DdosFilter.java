package cn.managame.gateway.filter;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class DdosFilter implements GatewayFilter {
    private final int maxConnectionsPerIp;
    private final double packetsPerSecondPerIp;
    private final double burstPerIp;
    private final ConcurrentHashMap<String, IpState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> acceptedSessions = new ConcurrentHashMap<>();

    public DdosFilter(int maxConnectionsPerIp, double packetsPerSecondPerIp, double burstPerIp) {
        if (maxConnectionsPerIp < 1) throw new IllegalArgumentException("maxConnectionsPerIp must be positive");
        if (!Double.isFinite(packetsPerSecondPerIp) || packetsPerSecondPerIp <= 0) throw new IllegalArgumentException("packetsPerSecondPerIp must be positive");
        if (!Double.isFinite(burstPerIp) || burstPerIp <= 0) throw new IllegalArgumentException("burstPerIp must be positive");
        this.maxConnectionsPerIp = maxConnectionsPerIp;
        this.packetsPerSecondPerIp = packetsPerSecondPerIp;
        this.burstPerIp = burstPerIp;
    }

    @Override
    public boolean onConnect(GatewaySession session) {
        boolean[] accepted = {false};
        states.compute(session.getClientIp(), (ip, current) -> {
            IpState state = current == null ? new IpState(packetsPerSecondPerIp, burstPerIp) : current;
            int count = state.connections.incrementAndGet();
            if (count <= maxConnectionsPerIp) {
                accepted[0] = true;
                return state;
            }
            state.connections.decrementAndGet();
            return state.connections.get() == 0 ? null : state;
        });
        if (accepted[0]) {
            acceptedSessions.put(session.getSessionId(), session.getClientIp());
            return true;
        }
        return false;
    }

    @Override
    public int onPacket(GatewaySession session, GatewayPacket packet) {
        IpState state = states.get(session.getClientIp());
        return state != null && state.bucket.tryConsume() ? GatewayErrorCode.OK : GatewayErrorCode.CONNECTION_LIMITED;
    }

    @Override
    public void onDisconnect(GatewaySession session) {
        String ip = acceptedSessions.remove(session.getSessionId());
        if (ip == null) return;
        states.computeIfPresent(ip, (ignored, state) ->
                state.connections.updateAndGet(value -> Math.max(0, value - 1)) == 0 ? null : state);
    }

    private static final class IpState {
        final AtomicInteger connections = new AtomicInteger();
        final TokenBucket bucket;
        IpState(double rate, double burst) { bucket = new TokenBucket(rate, burst); }
    }
}
