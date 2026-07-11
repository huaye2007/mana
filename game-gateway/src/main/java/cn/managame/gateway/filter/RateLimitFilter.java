package cn.managame.gateway.filter;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;

import java.util.concurrent.ConcurrentHashMap;

public final class RateLimitFilter implements GatewayFilter {
    private final double packetsPerSecond;
    private final double burst;
    private final ConcurrentHashMap<Long, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(double packetsPerSecond, double burst) {
        if (!Double.isFinite(packetsPerSecond) || packetsPerSecond <= 0) throw new IllegalArgumentException("packetsPerSecond must be positive");
        if (!Double.isFinite(burst) || burst <= 0) throw new IllegalArgumentException("burst must be positive");
        this.packetsPerSecond = packetsPerSecond;
        this.burst = burst;
    }

    @Override
    public int onPacket(GatewaySession session, GatewayPacket packet) {
        TokenBucket bucket = buckets.computeIfAbsent(session.getSessionId(), ignored -> new TokenBucket(packetsPerSecond, burst));
        return bucket.tryConsume() ? GatewayErrorCode.OK : GatewayErrorCode.RATE_LIMITED;
    }

    @Override public void onDisconnect(GatewaySession session) { buckets.remove(session.getSessionId()); }
}
