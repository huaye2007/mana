package cn.managame.jpa.async;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 异步批量写的退避重试策略。
 *
 * @param maxRetries        首次写入失败后最多再重试的次数
 * @param initialDelayMillis 第一次重试前的等待时间
 * @param maxDelayMillis     单次等待时间上限
 * @param multiplier         后续等待时间的指数倍数
 * @param jitterFactor       抖动比例（0~1），避免数据库恢复时所有游戏服同时重试
 */
public record RetryPolicy(
        int maxRetries,
        long initialDelayMillis,
        long maxDelayMillis,
        double multiplier,
        double jitterFactor) {

    public RetryPolicy {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        if (initialDelayMillis < 0) {
            throw new IllegalArgumentException("initialDelayMillis must not be negative");
        }
        if (maxDelayMillis < initialDelayMillis) {
            throw new IllegalArgumentException("maxDelayMillis must be >= initialDelayMillis");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0");
        }
        if (jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("jitterFactor must be between 0 and 1");
        }
    }

    /** 游戏服默认策略：100ms 起步，指数退避到 5s，并加入 20% 抖动。 */
    public static RetryPolicy defaults(int maxRetries) {
        return new RetryPolicy(maxRetries, 100, 5_000, 2.0, 0.2);
    }

    /** 无等待策略，主要用于测试或由外部流控系统负责退避的场景。 */
    public static RetryPolicy immediate(int maxRetries) {
        return new RetryPolicy(maxRetries, 0, 0, 1.0, 0.0);
    }

    long delayMillis(int retryNumber) {
        if (retryNumber <= 0 || initialDelayMillis == 0) {
            return 0;
        }
        double exponential = initialDelayMillis * Math.pow(multiplier, retryNumber - 1);
        long capped = (long) Math.min(maxDelayMillis, exponential);
        if (capped == 0 || jitterFactor == 0.0) {
            return capped;
        }
        double lower = 1.0 - jitterFactor;
        double upper = 1.0 + jitterFactor;
        return Math.max(0, Math.round(capped * ThreadLocalRandom.current().nextDouble(lower, upper)));
    }
}
