package cn.managame.gateway.filter;

import java.util.function.LongSupplier;

/** Internal thread-safe token-bucket state used by built-in gateway filters. */
final class TokenBucket {
    private final double tokensPerNano;
    private final double capacity;
    private final LongSupplier nanoTime;
    private double tokens;
    private long updatedAt;

    TokenBucket(double tokensPerSecond, double capacity) {
        this(tokensPerSecond, capacity, System::nanoTime);
    }

    TokenBucket(double tokensPerSecond, double capacity, LongSupplier nanoTime) {
        if (!Double.isFinite(tokensPerSecond) || tokensPerSecond <= 0) throw new IllegalArgumentException("rate must be positive");
        if (!Double.isFinite(capacity) || capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.tokensPerNano = tokensPerSecond / 1_000_000_000d;
        this.capacity = capacity;
        if (nanoTime == null) throw new NullPointerException("nanoTime");
        this.nanoTime = nanoTime;
        this.tokens = capacity;
        this.updatedAt = nanoTime.getAsLong();
    }

    synchronized boolean tryAcquire() {
        long now = nanoTime.getAsLong();
        long elapsed = Math.max(0, now - updatedAt);
        tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
        updatedAt = now;
        if (tokens < 1) return false;
        tokens--;
        return true;
    }
}
