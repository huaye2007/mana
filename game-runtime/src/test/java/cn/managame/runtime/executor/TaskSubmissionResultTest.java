package cn.managame.runtime.executor;

import cn.managame.runtime.context.GameTaskContext;
import cn.managame.runtime.context.GameTaskType;
import cn.managame.runtime.runnable.IGameTaskRunnable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSubmissionResultTest {

    private static IGameTaskRunnable task(byte group, Runnable body) {
        GameTaskContext context = new GameTaskContext(GameTaskType.CALLBACK,
                group, 1L, (byte) 0, 0L, null);
        return new IGameTaskRunnable() {
            @Override
            public void run() {
                body.run();
            }

            @Override
            public GameTaskContext getGameTaskContext() {
                return context;
            }
        };
    }

    @Test
    void reportsOverloadShutdownAndMissingGroup() throws Exception {
        byte groupId = 71;
        DefaultExecutorGroup group = DefaultExecutorGroup.platformThreads(groupId, "result", 1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertEquals(TaskSubmissionResult.ACCEPTED, group.tryExecGameTask(task(groupId, () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals(TaskSubmissionResult.ACCEPTED,
                    group.tryExecGameTask(task(groupId, () -> { })));
            assertEquals(TaskSubmissionResult.REJECTED_OVERLOADED,
                    group.tryExecGameTask(task(groupId, () -> { })));
        } finally {
            release.countDown();
            group.shutdown(1000);
        }
        assertEquals(TaskSubmissionResult.REJECTED_SHUTDOWN,
                group.tryExecGameTask(task(groupId, () -> { })));

        ExecutorGroupRegistry registry = new ExecutorGroupRegistry();
        assertEquals(TaskSubmissionResult.REJECTED_NO_GROUP,
                registry.tryExecute(task((byte) 72, () -> { })));
    }
}
