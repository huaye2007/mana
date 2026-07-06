package cn.managame.gateway.filter;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级限流：每个会话一个令牌桶（正常玩家的操作频率远低于阈值），
 * 超限的包拦下回 {@link GatewayErrorCode#RATE_LIMITED}，不断连——
 * 瞬时超频可能只是客户端重连风暴或卡顿后补发，断连反而放大问题。
 */
public class RateLimitFilter implements GatewayFilter {

    private final double packetsPerSecond;
    private final double burst;
    private final Map<Long, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(double packetsPerSecond, double burst) {
        this.packetsPerSecond = packetsPerSecond;
        this.burst = burst;
    }

    @Override
    public int onPacket(GatewaySession session, GatewayPacket packet) {
        TokenBucket bucket = buckets.computeIfAbsent(session.getSessionId(),
                id -> new TokenBucket(packetsPerSecond, burst));
        return bucket.tryAcquire() ? GatewayErrorCode.OK : GatewayErrorCode.RATE_LIMITED;
    }

    @Override
    public void onDisconnect(GatewaySession session) {
        buckets.remove(session.getSessionId());
    }
}
