package cn.managame.ai.internal;

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import java.util.concurrent.TimeUnit;

/**
 * Shared daemon {@link HashedWheelTimer} across {@code managame-ai} so an entire JVM uses at most one 10 ms timing
 * wheel thread regardless of whether both {@code AiClient} and {@code McpTools} (or multiple test instances) use
 * timeouts. Not API.
 */
public final class AiTimer {
    private static final long TICK_MILLIS = 10L;
    private static final int WHEEL_SIZE = 512;

    private static final class Holder {
        static final HashedWheelTimer INSTANCE = new HashedWheelTimer(
                Thread.ofPlatform().name("managame-ai-timer").daemon().factory(),
                TICK_MILLIS, TimeUnit.MILLISECONDS, WHEEL_SIZE, false, -1);
    }

    private AiTimer() {}

    public static Timeout newTimeout(TimerTask task, long delayNanos) {
        return Holder.INSTANCE.newTimeout(task, delayNanos, TimeUnit.NANOSECONDS);
    }
}
