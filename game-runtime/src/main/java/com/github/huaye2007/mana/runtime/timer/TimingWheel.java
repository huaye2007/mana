package com.github.huaye2007.mana.runtime.timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 哈希时间轮。单个 worker 线程按 tick 推进，到期任务在 worker 线程上执行，
 * 因此任务必须轻量非阻塞（runtime 内的用法只是把游戏任务派发到执行器组）。
 *
 * <p>调度完全基于相对延迟（delayMs → tick 数），不依赖墙钟时间戳：worker 步进
 * 用单调钟（{@link System#nanoTime()}）定速，因此系统时钟回拨、VM 挂起恢复都不会
 * 让定时器冻结或瞬时爆发。超过一圈跨度的延迟通过 remainingRounds 处理；取消采用
 * 惰性删除，worker 扫到对应 bucket 时移除。</p>
 *
 * <p>需要"延迟一次性执行"直接调用 {@link #schedule(long, Runnable)}；周期/cron 调度
 * 由业务在此基础上封装（见 {@link CronTask}）。</p>
 */
public final class TimingWheel {

    private final static Logger logger = LoggerFactory.getLogger(TimingWheel.class);

    private final static long DEFAULT_TICK_MS = 100;
    private final static int DEFAULT_WHEEL_SIZE = 512;

    /** 默认共享时间轮：tick 100ms × 512 槽。业务延迟任务与 cron 都走这一根计时线程。 */
    private final static TimingWheel INSTANCE = new TimingWheel("timer-wheel", DEFAULT_TICK_MS, DEFAULT_WHEEL_SIZE);

    public static TimingWheel getInstance() {
        return INSTANCE;
    }

    private final long tickMs;
    private final long tickNanos;
    private final int mask;
    private final List<TimeoutTask>[] buckets;
    private final ConcurrentLinkedQueue<TimeoutTask> pending = new ConcurrentLinkedQueue<>();
    /** 在轮上等待触发的任务数；已取消但未被 worker 扫到的任务仍计入（惰性删除） */
    private final AtomicLong pendingCount = new AtomicLong();
    /** worker 当前正在处理的 tick，调度线程读它换算目标 tick；worker 单写、其余线程读 */
    private volatile long currentTick;
    private final Thread worker;
    private volatile boolean running = true;

    @SuppressWarnings("unchecked")
    public TimingWheel(String name, long tickMs, int wheelSize) {
        if (tickMs <= 0) {
            throw new IllegalArgumentException("tickMs must be positive: " + tickMs);
        }
        int normalized = normalizeToPowerOfTwo(wheelSize);
        this.tickMs = tickMs;
        this.tickNanos = tickMs * 1_000_000L;
        this.mask = normalized - 1;
        this.buckets = new List[normalized];
        for (int i = 0; i < normalized; i++) {
            buckets[i] = new LinkedList<>();
        }
        this.worker = new Thread(this::workerLoop, name);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    private static int normalizeToPowerOfTwo(int wheelSize) {
        if (wheelSize <= 0) {
            throw new IllegalArgumentException("wheelSize must be positive: " + wheelSize);
        }
        int n = Integer.highestOneBit(wheelSize);
        return n == wheelSize ? n : n << 1;
    }

    /**
     * delayMs 后执行一次 task。延迟会向上取整到 tick 粒度，并换算成相对当前 tick 的
     * 偏移；不读取墙钟时间。
     */
    public Timeout schedule(long delayMs, Runnable task) {
        if (!running) {
            throw new IllegalStateException("timing wheel already shut down");
        }
        long delayTicks = (Math.max(0, delayMs) + tickMs - 1) / tickMs;
        long targetTick = currentTick + delayTicks;
        TimeoutTask timeout = new TimeoutTask(task, targetTick);
        pendingCount.incrementAndGet();
        pending.add(timeout);
        return timeout;
    }

    /**
     * 等待触发的任务数（瞬时值，监控采样用）。包含已取消但尚未被惰性清除的任务。
     */
    public long pendingCount() {
        return pendingCount.get();
    }

    public void shutdown() {
        running = false;
        worker.interrupt();
    }

    private void workerLoop() {
        long startNanos = System.nanoTime();
        long tick = 0;
        while (running) {
            long sleepNanos = startNanos + (tick + 1) * tickNanos - System.nanoTime();
            if (sleepNanos > 0) {
                try {
                    Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                } catch (InterruptedException e) {
                    if (!running) {
                        return;
                    }
                    continue;
                }
            }
            currentTick = tick;
            transferPending(tick);
            expireBucket(buckets[(int) (tick & mask)]);
            tick++;
        }
    }

    private void transferPending(long currentTick) {
        TimeoutTask timeout;
        while ((timeout = pending.poll()) != null) {
            if (timeout.isCancelled()) {
                pendingCount.decrementAndGet();
                continue;
            }
            // 调度时算出的 targetTick 可能已落后于当前 tick（入队到被处理之间 worker 推进了），
            // 取 max 保证最迟在本 tick 触发，不会被推后将近一整圈。
            long target = Math.max(timeout.targetTick, currentTick);
            timeout.remainingRounds = (target - currentTick) / buckets.length;
            buckets[(int) (target & mask)].add(timeout);
        }
    }

    private void expireBucket(List<TimeoutTask> bucket) {
        Iterator<TimeoutTask> it = bucket.iterator();
        while (it.hasNext()) {
            TimeoutTask timeout = it.next();
            if (timeout.isCancelled()) {
                it.remove();
                pendingCount.decrementAndGet();
            } else if (timeout.remainingRounds <= 0) {
                it.remove();
                pendingCount.decrementAndGet();
                timeout.expire();
            } else {
                timeout.remainingRounds--;
            }
        }
    }

    private static final class TimeoutTask implements Timeout {

        private static final int WAITING = 0;
        private static final int CANCELLED = 1;
        private static final int EXPIRED = 2;

        private static final AtomicIntegerFieldUpdater<TimeoutTask> STATE =
                AtomicIntegerFieldUpdater.newUpdater(TimeoutTask.class, "state");

        private final Runnable task;
        /** 目标 tick（相对调度时的 currentTick + 延迟 tick 数） */
        private final long targetTick;
        /** 仅 worker 线程读写 */
        private long remainingRounds;
        private volatile int state = WAITING;

        TimeoutTask(Runnable task, long targetTick) {
            this.task = task;
            this.targetTick = targetTick;
        }

        @Override
        public boolean cancel() {
            return STATE.compareAndSet(this, WAITING, CANCELLED);
        }

        @Override
        public boolean isCancelled() {
            return state == CANCELLED;
        }

        @Override
        public boolean isExpired() {
            return state == EXPIRED;
        }

        void expire() {
            if (!STATE.compareAndSet(this, WAITING, EXPIRED)) {
                return;
            }
            try {
                task.run();
            } catch (Throwable e) {
                logger.error("timing wheel task failed", e);
            }
        }
    }
}
