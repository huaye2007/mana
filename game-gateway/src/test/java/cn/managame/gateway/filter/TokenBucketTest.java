package cn.managame.gateway.filter;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketTest {
    @Test void refillsAtTheConfiguredRate() {
        AtomicLong time = new AtomicLong();
        TokenBucket bucket = new TokenBucket(2, 2, time::get);

        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire());
        time.addAndGet(500_000_000L);
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire());
    }
}
