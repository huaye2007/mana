package cn.managame.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The table of waiting calls on its own: ids, the limit, and ending each call once. */
class PendingCallsTest {
    private static Call call(PendingCalls calls, RpcCallback callback) {
        return new Call(calls, RpcRequest.of(1, Unpooled.EMPTY_BUFFER), callback, Long.MAX_VALUE);
    }

    private static Call call(PendingCalls calls) { return call(calls, (request, response) -> {}); }

    @Test void callsGetIdsOfTheirOwnAndAreTakenOutOnce() {
        PendingCalls calls = new PendingCalls(4);
        Call first = call(calls), second = call(calls);
        assertTrue(calls.add(first));
        assertTrue(calls.add(second));
        assertEquals(1, first.id);
        assertEquals(2, second.id);
        assertEquals(2, calls.size());
        assertSame(first, calls.remove(1));
        assertNull(calls.remove(1), "ended already");
        assertNull(calls.remove(7), "an id never handed out");
        assertFalse(calls.remove(first));
        assertTrue(calls.remove(second));
        assertEquals(0, calls.size());
    }

    @Test void theLimitHoldsAndIdsSkipTakenSlots() {
        PendingCalls calls = new PendingCalls(3); // 4 slots
        Call stuck = call(calls);
        calls.add(stuck);
        for (int i = 0; i < 2; i++) assertTrue(calls.add(call(calls)));
        assertFalse(calls.add(call(calls)), "three wait already");
        calls.remove(2);
        calls.remove(3);
        List<Call> later = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Call call = call(calls);
            assertTrue(calls.add(call));
            later.add(call);
        }
        assertEquals(List.of(4, 6), later.stream().map(call -> call.id).toList(), "id 5 shares its slot with the stuck call 1");
        assertSame(stuck, calls.remove(1));
    }

    @Test void failAllEndsEveryCallWithTheCode() {
        PendingCalls calls = new PendingCalls(8);
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i < 5; i++) calls.add(call(calls, (request, response) -> codes.add(response.code())));
        calls.failAll(77);
        assertEquals(List.of(77, 77, 77, 77, 77), codes);
        assertEquals(0, calls.size());
    }

    /** Callers add and take out their calls while answers take them out by id and failAll empties the table: each call ends once. */
    @Test void everyCallEndsOnceUnderContention() throws Exception {
        PendingCalls calls = new PendingCalls(64);
        Set<Integer> ended = ConcurrentHashMap.newKeySet();
        AtomicInteger twice = new AtomicInteger(), made = new AtomicInteger();
        RpcCallback record = (request, response) -> { if (!ended.add(response.requestId())) twice.incrementAndGet(); };
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            threads.add(Thread.ofPlatform().start(() -> {
                for (int i = 0; i < 50_000; i++) {
                    Call call = call(calls, record);
                    if (!calls.add(call)) continue;
                    made.incrementAndGet();
                    if (i % 2 == 0 && calls.remove(call)) call.fail(1);
                }
            }));
        }
        threads.add(Thread.ofPlatform().start(() -> { // answers, by id
            for (int id = 1; id < 200_000; id++) {
                Call call = calls.remove(id);
                if (call != null) call.fail(2);
            }
        }));
        for (Thread thread : threads) thread.join();
        calls.failAll(3);
        assertEquals(0, twice.get());
        assertEquals(0, calls.size());
        assertEquals(made.get(), ended.size());
    }
}
