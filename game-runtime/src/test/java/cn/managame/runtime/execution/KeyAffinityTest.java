package cn.managame.runtime.execution;

import cn.managame.runtime.route.RouteType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.managame.runtime.execution.RouteRuntimeTest.*;

@Timeout(20)
class KeyAffinityTest {
    interface Room extends RouteType {}
    interface RoomMaintenance extends RouteType {}

    static ExecutionDomain rooms(int threads) {
        return ExecutionDomain.platform("rooms").threads(threads)
                .scheduling(ExecutionDomain.Scheduling.KEY_AFFINITY).tasksPerTurn(1).build();
    }

    @Test void affinitySurvivesIdleQueueRecreationAndUsesAllLongKeyBits() throws Exception {
        try (GameRuntime runtime = builder().executionDomain(Room.class, rooms(4)).build()) {
            Map<Long, Thread> assigned = new HashMap<>();
            long[] keys = {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 40, 2L << 40, 3L << 40};
            for (long key : keys) {
                done(runtime.dispatch(Room.class, key, () -> {
                    assertFalse(Thread.currentThread().isVirtual());
                    assigned.put(key, Thread.currentThread());
                }));
            }
            // Drain removes idle Lane objects; retain the same worker mapping on recreation.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (runtime.activeRoutes() != 0 && System.nanoTime() < deadline) Thread.yield();
            assertEquals(0, runtime.activeRoutes());
            for (int round = 0; round < 20; round++) {
                for (long key : keys) done(runtime.dispatch(Room.class, key,
                        () -> assertSame(assigned.get(key), Thread.currentThread())));
            }
            assertTrue(new HashSet<>(assigned.values()).size() > 1);
            assertEquals(ExecutionDomain.Scheduling.KEY_AFFINITY,
                    runtime.executionDomains().get("rooms").scheduling());
        }
    }

    @Test void blockedPartitionDoesNotBlockOtherRoomPartitionsOrBalancedPlayerDomain() throws Exception {
        var domain = rooms(2);
        try (GameRuntime runtime = builder().executionDomain(Room.class, domain)
                .executionDomain(RoomMaintenance.class, domain)
                .executionDomain(TestRoutes.Player.class, ExecutionDomain.platform("players").threads(1).build()).build()) {
            Map<Thread, List<Long>> keys = new LinkedHashMap<>();
            for (long i = 0; i < 32; i++) {
                long key = i;
                done(runtime.dispatch(Room.class, key, () ->
                        keys.computeIfAbsent(Thread.currentThread(), ignored -> new ArrayList<>()).add(key)));
            }
            assertEquals(2, keys.size());
            List<List<Long>> partitions = new ArrayList<>(keys.values());
            long firstKey = partitions.get(0).get(0), collision = partitions.get(0).get(1);
            long otherKey = partitions.get(1).get(0);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var owner = new AtomicReference<Thread>(); var collisionRan = new AtomicBoolean();
            RouteTask first = runtime.dispatch(Room.class, firstKey, () -> {
                owner.set(Thread.currentThread()); entered.countDown(); await(release);
            });
            try {
                await(entered);
                RouteTask samePartition = runtime.dispatch(Room.class, collision, () -> collisionRan.set(true));
                RouteTask sameKeyOtherType = runtime.dispatch(RoomMaintenance.class, firstKey,
                        () -> assertSame(owner.get(), Thread.currentThread()));
                done(runtime.dispatch(Room.class, otherKey, () -> assertNotSame(owner.get(), Thread.currentThread())));
                done(runtime.dispatch(TestRoutes.Player.class, firstKey, () -> {
                    assertTrue(Thread.currentThread().getName().startsWith("game-players-"));
                    assertNotSame(owner.get(), Thread.currentThread());
                }));
                assertFalse(collisionRan.get()); assertFalse(sameKeyOtherType.isDone());
                release.countDown(); done(first); done(samePartition); done(sameKeyOtherType);
            } finally { release.countDown(); }
        }
    }

    @Test void partitionQueuesPreserveEntityFifoAndDrainAcrossClose() throws Exception {
        GameRuntime runtime = builder().executionDomain(Room.class, rooms(3)).build();
        List<RouteTask> tasks = new ArrayList<>();
        try (ExecutorService senders = Executors.newFixedThreadPool(8)) {
            List<Future<List<RouteTask>>> submissions = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                long key = i;
                submissions.add(senders.submit(() -> {
                    var sequence = new AtomicInteger();
                    var thread = new AtomicReference<Thread>();
                    List<RouteTask> submitted = new ArrayList<>();
                    for (int j = 0; j < 100; j++) {
                        int expected = j;
                        submitted.add(runtime.dispatch(Room.class, key, () -> {
                            assertEquals(expected, sequence.getAndIncrement());
                            thread.compareAndSet(null, Thread.currentThread());
                            assertSame(thread.get(), Thread.currentThread());
                        }));
                    }
                    return submitted;
                }));
            }
            for (var submission : submissions) tasks.addAll(submission.get(5, TimeUnit.SECONDS));
            runtime.close();
            for (var task : tasks) done(task);
            assertEquals(2400, runtime.metrics().completedTasks());
            assertEquals(0, runtime.activeRoutes());
            assertEquals(0, runtime.executionDomains().get("rooms").readyRoutes());
        } finally { runtime.close(); }
    }

    @Test void virtualThreadsCannotClaimPhysicalThreadAffinity() {
        assertThrows(IllegalArgumentException.class, () -> ExecutionDomain.virtual("login")
                .scheduling(ExecutionDomain.Scheduling.KEY_AFFINITY));
        assertThrows(NullPointerException.class, () -> ExecutionDomain.platform("rooms").scheduling(null));
        assertEquals(ExecutionDomain.Scheduling.BALANCED, ExecutionDomain.platform("players").build().scheduling());
    }
}
