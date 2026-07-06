package cn.managame.gateway.filter;

/**
 * 令牌桶：容量 burst，按 permitsPerSecond 匀速补充。
 * 实例级 synchronized——每个会话/IP 一个桶，锁只在同源包之间竞争，
 * 且临界区仅几次算术运算，不构成热点。
 */
final class TokenBucket {

    private final double permitsPerSecond;
    private final double burst;

    private double tokens;
    private long lastRefillNanos;

    TokenBucket(double permitsPerSecond, double burst) {
        this.permitsPerSecond = permitsPerSecond;
        this.burst = burst;
        this.tokens = burst;
        this.lastRefillNanos = System.nanoTime();
    }

    synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        double refilled = tokens + (now - lastRefillNanos) / 1_000_000_000.0 * permitsPerSecond;
        tokens = Math.min(refilled, burst);
        lastRefillNanos = now;
        if (tokens < 1.0) {
            return false;
        }
        tokens -= 1.0;
        return true;
    }
}
