package cn.managame.runtime.event;

import cn.managame.runtime.annotation.EventHandler;
import cn.managame.runtime.annotation.EventMethod;
import cn.managame.runtime.context.GameEventTaskContext;
import cn.managame.runtime.context.GameTaskContext;
import cn.managame.runtime.context.GameTaskContextHolder;
import cn.managame.runtime.context.GameTaskType;
import cn.managame.runtime.executor.DefaultExecutorGroup;
import cn.managame.runtime.executor.ExecutorGroupRegistry;
import cn.managame.runtime.runnable.GameCallbackTaskRunnable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBusTest {

    private static final byte GROUP_A = 31;
    private static final byte GROUP_B = 32;
    private static final byte GROUP_C = 33;
    private static final long KEY = 7L;

    private static final DefaultExecutorGroup A = DefaultExecutorGroup.platformThreads(GROUP_A, "bus-a", 1, 256);
    private static final DefaultExecutorGroup B = DefaultExecutorGroup.platformThreads(GROUP_B, "bus-b", 1, 256);
    private static final DefaultExecutorGroup C = DefaultExecutorGroup.platformThreads(GROUP_C, "bus-c", 2, 256);

    private static final AtomicReference<Thread> INLINE_THREAD = new AtomicReference<>();
    private static final AtomicReference<Thread> CROSS_THREAD = new AtomicReference<>();
    private static final AtomicReference<Thread> UNBOUND_THREAD = new AtomicReference<>();
    private static final AtomicReference<Thread> KEY_MISMATCH_THREAD = new AtomicReference<>();
    private static final AtomicReference<GameTaskType> INLINE_CTX_TYPE = new AtomicReference<>();
    private static final List<Integer> ORDER = new CopyOnWriteArrayList<>();
    private static final CountDownLatch CROSS_DONE = new CountDownLatch(1);
    private static final CountDownLatch UNBOUND_DONE = new CountDownLatch(1);
    private static final CountDownLatch KEY_MISMATCH_DONE = new CountDownLatch(1);
    private static final CountDownLatch INLINE_CTX_DONE = new CountDownLatch(1);

    static {
        ExecutorGroupRegistry.getInstance().register(A);
        ExecutorGroupRegistry.getInstance().register(B);
        ExecutorGroupRegistry.getInstance().register(C);
        EventBus.getInstance().register(new TestListeners());
    }

    @AfterAll
    static void tearDown() {
        A.shutdown(1000);
        B.shutdown(1000);
        C.shutdown(1000);
    }

    public static class InlineCrossEvent implements IGameEvent {
        @Override
        public long routerKey() {
            return KEY;
        }
    }

    public static class OrderedEvent implements IGameEvent {
        @Override
        public long routerKey() {
            return KEY;
        }
    }

    public static class UnboundEvent implements IGameEvent {
        @Override
        public long routerKey() {
            return KEY;
        }
    }

    public static class KeyMismatchEvent implements IGameEvent {
        @Override
        public long routerKey() {
            return 1L; // GROUP_C 2 个 worker，key=1 落 worker-1
        }
    }

    /** 用于验证内联监听者复用发布方上下文（taskType 不被改写成 EVENT） */
    public static class InlineCtxEvent implements IGameEvent {
        @Override
        public long routerKey() {
            return KEY;
        }
    }

    @EventHandler(group = GROUP_A)
    public static class TestListeners {

        @EventMethod
        public void inlineOnA(InlineCrossEvent e) {
            INLINE_THREAD.set(Thread.currentThread());
        }

        @EventMethod(group = GROUP_B)
        public void crossToB(InlineCrossEvent e) {
            CROSS_THREAD.set(Thread.currentThread());
            CROSS_DONE.countDown();
        }

        @EventMethod(order = 2)
        public void runsSecond(OrderedEvent e) {
            ORDER.add(2);
        }

        @EventMethod(order = 1)
        public void runsFirst(OrderedEvent e) {
            ORDER.add(1);
        }

        @EventMethod
        public void onUnbound(UnboundEvent e) {
            UNBOUND_THREAD.set(Thread.currentThread());
            UNBOUND_DONE.countDown();
        }

        @EventMethod(group = GROUP_C)
        public void onKeyMismatch(KeyMismatchEvent e) {
            KEY_MISMATCH_THREAD.set(Thread.currentThread());
            KEY_MISMATCH_DONE.countDown();
        }

        @EventMethod
        public void inlineCtx(InlineCtxEvent e) {
            INLINE_CTX_TYPE.set(GameTaskContextHolder.current().getTaskType());
            INLINE_CTX_DONE.countDown();
        }
    }

    /**
     * 在 GROUP_A、routerKey=KEY 的任务里执行 body（callback 任务会隐式绑定上下文）。
     */
    private static void runOnGroupA(Runnable body, CountDownLatch done) {
        ExecutorGroupRegistry.getInstance().execute(
                new GameCallbackTaskRunnable(GROUP_A, KEY, (byte) 0, 0L, null, () -> {
                    body.run();
                    done.countDown();
                }));
    }

    @Test
    void sameGroupAndRouterKeyRunsInlineOthersDispatched() throws Exception {
        CountDownLatch published = new CountDownLatch(1);
        AtomicReference<Thread> publisherThread = new AtomicReference<>();
        AtomicReference<Boolean> inlineRanSynchronously = new AtomicReference<>();

        runOnGroupA(() -> {
            publisherThread.set(Thread.currentThread());
            EventBus.getInstance().publishEvent(new InlineCrossEvent());
            // 内联监听者必须在 publishEvent 返回前已执行完
            inlineRanSynchronously.set(INLINE_THREAD.get() == Thread.currentThread());
        }, published);

        assertTrue(published.await(5, TimeUnit.SECONDS));
        assertTrue(inlineRanSynchronously.get(), "同 group+routerKey 的监听者应内联同步执行");
        assertTrue(CROSS_DONE.await(5, TimeUnit.SECONDS));
        assertTrue(CROSS_THREAD.get().getName().startsWith("bus-b"), "异组监听者应派发到目标组线程");
        assertNotEquals(publisherThread.get(), CROSS_THREAD.get());
    }

    @Test
    void listenersRunByOrderWithinSameGroup() throws Exception {
        CountDownLatch published = new CountDownLatch(1);
        runOnGroupA(() -> EventBus.getInstance().publishEvent(new OrderedEvent()), published);

        assertTrue(published.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(1, 2), ORDER, "order 小的先执行");
    }

    @Test
    void explicitContextWithDifferentRouterKeyMustDispatchNotInline() throws Exception {
        // 上下文 group 与监听者相同但 routerKey(2) != 事件 routerKey(1)：
        // 不能内联，必须按事件 routerKey 投递到 GROUP_C 的 worker-1
        GameEventTaskContext context = new GameEventTaskContext(GameTaskType.EVENT,
                GROUP_C, 2L, (byte) 0, 0L, null);
        EventBus.getInstance().publishEvent(context, new KeyMismatchEvent());

        assertTrue(KEY_MISMATCH_DONE.await(5, TimeUnit.SECONDS));
        assertNotEquals(Thread.currentThread(), KEY_MISMATCH_THREAD.get(), "routerKey 不同不允许内联");
        assertTrue(KEY_MISMATCH_THREAD.get().getName().endsWith("worker-1"),
                "必须按事件 routerKey 路由: " + KEY_MISMATCH_THREAD.get().getName());
    }

    @Test
    void publishWithoutBoundContextDispatchesToListenerGroup() throws Exception {
        // 测试主线程无绑定上下文 → 即使监听者在 GROUP_A 也必须走派发
        EventBus.getInstance().publishEvent(new UnboundEvent());

        assertTrue(UNBOUND_DONE.await(5, TimeUnit.SECONDS));
        assertTrue(UNBOUND_THREAD.get().getName().startsWith("bus-a"));
        assertNotEquals(Thread.currentThread(), UNBOUND_THREAD.get());
    }

    @Test
    void inlineListenerReusesPublisherContextTaskType() throws Exception {
        // 发布方是 CALLBACK 任务（runOnGroupA 用 GameCallbackTaskRunnable 隐式绑定），
        // 同组同 routerKey 的监听者内联执行时应复用发布方上下文，taskType 保持 CALLBACK，
        // 而不是被改写成 EVENT。
        CountDownLatch published = new CountDownLatch(1);
        runOnGroupA(() -> EventBus.getInstance().publishEvent(new InlineCtxEvent()), published);

        assertTrue(published.await(5, TimeUnit.SECONDS));
        assertTrue(INLINE_CTX_DONE.await(5, TimeUnit.SECONDS));
        assertEquals(GameTaskType.CALLBACK, INLINE_CTX_TYPE.get(),
                "内联监听者应复用发布方上下文，taskType 保持 CALLBACK");
    }
}
