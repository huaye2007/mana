package cn.managame.gateway.filter;

final class TokenBucket {
    private final double tokensPerNano;
    private final double capacity;
    private double tokens;
    private long updatedAt;

    TokenBucket(double tokensPerSecond, double capacity) {
        if (!Double.isFinite(tokensPerSecond) || tokensPerSecond <= 0) throw new IllegalArgumentException("rate must be positive");
        if (!Double.isFinite(capacity) || capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.tokensPerNano = tokensPerSecond / 1_000_000_000d;
        this.capacity = capacity;
        this.tokens = capacity;
        this.updatedAt = System.nanoTime();
    }

    synchronized boolean tryConsume() {
        long now = System.nanoTime();
        long elapsed = Math.max(0, now - updatedAt);
        tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
        updatedAt = now;
        if (tokens < 1d) return false;
        tokens -= 1d;
        return true;
    }
}
