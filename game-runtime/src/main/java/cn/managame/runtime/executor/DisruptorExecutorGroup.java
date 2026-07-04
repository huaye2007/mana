package cn.managame.runtime.executor;

import cn.managame.runtime.monitor.GameTaskMonitors;
import cn.managame.runtime.runnable.IGameTaskRunnable;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 基于 LMAX Disruptor 的执行器组实现：组内 N 条单消费者 RingBuffer，
 * 任务按 routerKey 哈希落到固定 ring，保证同一 routerKey（如同一玩家）
 * 的任务串行执行。语义与 {@link DefaultExecutorGroup} 一致：环满直接
 * 丢弃并回调监控，不缓冲不重试。
 *
 * <p>消费者固定为平台线程，适合 SCENE 这类延迟敏感、不允许阻塞的组。
 * 等待策略两档：{@link #blockingWait} 空闲挂起省 CPU；
 * {@link #yieldingWait} 空闲自旋让出，延迟更低但每个空闲 worker 吃满一个核。</p>
 */
public class DisruptorExecutorGroup implements IExecutorGroup {

    private final static Logger logger = LoggerFactory.getLogger(DisruptorExecutorGroup.class);

    private final byte group;
    private final Disruptor<TaskEvent>[] disruptors;
    private final RingBuffer<TaskEvent>[] ringBuffers;
    private final AtomicLong droppedCount = new AtomicLong();
    private volatile boolean shuttingDown;

    /** 任务异常已在 runnable 内兜底；这里兜住残余异常并继续消费，避免默认 FatalExceptionHandler 停掉整条 ring */
    private static final ExceptionHandler<TaskEvent> EXCEPTION_HANDLER = new ExceptionHandler<>() {
        @Override
        public void handleEventException(Throwable ex, long sequence, TaskEvent event) {
            logger.error("uncaught exception in disruptor worker, sequence={}", sequence, ex);
        }

        @Override
        public void handleOnStartException(Throwable ex) {
            logger.error("disruptor worker start failed", ex);
        }

        @Override
        public void handleOnShutdownException(Throwable ex) {
            logger.error("disruptor worker shutdown failed", ex);
        }
    };

    private static final EventTranslatorOneArg<TaskEvent, IGameTaskRunnable> TRANSLATOR = (event, sequence, task) -> {
        event.task = task;
        event.enqueueNanos = System.nanoTime();
    };

    public static DisruptorExecutorGroup blockingWait(byte group, String name, int threads, int bufferSize) {
        return new DisruptorExecutorGroup(group, name, threads, bufferSize, BlockingWaitStrategy::new);
    }

    public static DisruptorExecutorGroup yieldingWait(byte group, String name, int threads, int bufferSize) {
        return new DisruptorExecutorGroup(group, name, threads, bufferSize, YieldingWaitStrategy::new);
    }

    /**
     * @param waitStrategyFactory 每条 ring 独立创建等待策略实例
     *                            （如 BlockingWaitStrategy 内含锁状态，不能跨 ring 共享）
     */
    @SuppressWarnings("unchecked")
    public DisruptorExecutorGroup(byte group, String name, int threads, int bufferSize,
                                  Supplier<WaitStrategy> waitStrategyFactory) {
        if (threads <= 0) {
            throw new IllegalArgumentException("threads must be positive: " + threads);
        }
        if (bufferSize <= 0 || Integer.bitCount(bufferSize) != 1) {
            throw new IllegalArgumentException("bufferSize must be a power of 2: " + bufferSize);
        }
        this.group = group;
        this.disruptors = new Disruptor[threads];
        this.ringBuffers = new RingBuffer[threads];
        for (int i = 0; i < threads; i++) {
            String threadName = name + "-group" + group + "-worker-" + i;
            ThreadFactory threadFactory = r -> {
                Thread t = new Thread(r, threadName);
                t.setDaemon(true);
                return t;
            };
            Disruptor<TaskEvent> disruptor = new Disruptor<>(TaskEvent::new, bufferSize, threadFactory,
                    ProducerType.MULTI, waitStrategyFactory.get());
            disruptor.handleEventsWith(new TaskEventHandler());
            disruptor.setDefaultExceptionHandler(EXCEPTION_HANDLER);
            disruptors[i] = disruptor;
            ringBuffers[i] = disruptor.start();
        }
    }

    @Override
    public byte group() {
        return group;
    }

    @Override
    public void execGameTask(IGameTaskRunnable gameTaskRunnable) {
        long routerKey = gameTaskRunnable.getGameTaskContext().getRouterKey();
        int index = (int) Math.floorMod(routerKey, ringBuffers.length);
        if (shuttingDown || !ringBuffers[index].tryPublishEvent(TRANSLATOR, gameTaskRunnable)) {
            droppedCount.incrementAndGet();
            GameTaskMonitors.taskDropped(gameTaskRunnable.getGameTaskContext());
        }
    }

    /**
     * 累计被丢弃（环满或已停机）的任务数。
     */
    public long droppedCount() {
        return droppedCount.get();
    }

    /**
     * 当前所有 ring 中等待执行的任务总数（瞬时值，仅用于监控采样，含正在执行的槽位）。
     */
    public int queuedTasks() {
        int sum = 0;
        for (RingBuffer<TaskEvent> ringBuffer : ringBuffers) {
            sum += (int) (ringBuffer.getBufferSize() - ringBuffer.remainingCapacity());
        }
        return sum;
    }

    /**
     * 优雅停机：不再接收新任务，等待环上任务消费完；超时后强制停止。
     */
    @Override
    public void shutdown(long timeoutMs) {
        shuttingDown = true;
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (Disruptor<TaskEvent> disruptor : disruptors) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                disruptor.halt();
                continue;
            }
            try {
                disruptor.shutdown(remain, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                disruptor.halt();
            }
        }
    }

    /**
     * 环上事件槽位，复用对象承载任务引用和入队时间。
     */
    private static final class TaskEvent {
        IGameTaskRunnable task;
        long enqueueNanos;
    }

    /**
     * 单消费者处理器：执行任务并回调 {@link GameTaskMonitors} 记录排队/执行耗时。
     */
    private static final class TaskEventHandler implements EventHandler<TaskEvent> {

        @Override
        public void onEvent(TaskEvent event, long sequence, boolean endOfBatch) {
            IGameTaskRunnable task = event.task;
            long enqueueNanos = event.enqueueNanos;
            event.task = null;
            long startNanos = System.nanoTime();
            try {
                task.run();
            } finally {
                GameTaskMonitors.taskComplete(task.getGameTaskContext(),
                        (startNanos - enqueueNanos) / 1_000_000,
                        (System.nanoTime() - startNanos) / 1_000_000);
            }
        }
    }
}
