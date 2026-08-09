package cn.managame.jpa.async;

/**
 * 刷盘调度参数。取代原先逐层加参数的多个构造器，调用方只写自己关心的项。
 *
 * <pre>{@code
 * new FlushScheduler(queue, new FlushOptions()
 *         .intervalMillis(5_000)
 *         .maxRetries(3)
 *         .maxBatchSize(500), metrics);
 * }</pre>
 *
 * 取值在 setter 里就校验，配置错误在装配阶段暴露而不是等到第一次刷盘。
 */
public final class FlushOptions {

    /** 单批落库的等待上限；超时不取消结果未知的数据库调用，只是不再阻塞调度线程。 */
    public static final long DEFAULT_BATCH_TIMEOUT_MILLIS = 30_000L;

    /** 单次落库调用的任务数上限，避免单批穿透数据库的批量上限。 */
    public static final int DEFAULT_MAX_BATCH_SIZE = 500;

    private long intervalMillis = 5_000L;
    private int maxRetries = 3;
    private FlushThreadMode threadMode = FlushThreadMode.VIRTUAL;
    private int threadCount = Math.max(2, Runtime.getRuntime().availableProcessors());
    private int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
    private long batchTimeoutMillis = DEFAULT_BATCH_TIMEOUT_MILLIS;

    public FlushOptions intervalMillis(long intervalMillis) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis must be positive");
        }
        this.intervalMillis = intervalMillis;
        return this;
    }

    public FlushOptions maxRetries(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        this.maxRetries = maxRetries;
        return this;
    }

    /** worker 线程类型；两种模式都使用 {@link #threadCount(int)} 指定的有界池。 */
    public FlushOptions threadMode(FlushThreadMode threadMode) {
        this.threadMode = threadMode != null ? threadMode : FlushThreadMode.VIRTUAL;
        return this;
    }

    /** 刷盘并发度，即最多同时写入的物理表数。 */
    public FlushOptions threadCount(int threadCount) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("threadCount must be positive");
        }
        this.threadCount = threadCount;
        return this;
    }

    public FlushOptions maxBatchSize(int maxBatchSize) {
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("maxBatchSize must be positive");
        }
        this.maxBatchSize = maxBatchSize;
        return this;
    }

    public FlushOptions batchTimeoutMillis(long batchTimeoutMillis) {
        if (batchTimeoutMillis <= 0) {
            throw new IllegalArgumentException("batchTimeoutMillis must be positive");
        }
        this.batchTimeoutMillis = batchTimeoutMillis;
        return this;
    }

    public long intervalMillis() { return intervalMillis; }
    public int maxRetries() { return maxRetries; }
    public FlushThreadMode threadMode() { return threadMode; }
    public int threadCount() { return threadCount; }
    public int maxBatchSize() { return maxBatchSize; }
    public long batchTimeoutMillis() { return batchTimeoutMillis; }
}
