package cn.managame.rpc;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The calls to one peer waiting for their answer, keyed by request id, safe from any thread without locks: a table
 * of a power-of-two number of slots, the call with id {@code i} in slot {@code i & mask}. Ids count up from 1 and
 * skip 0 and the ids whose slot is still taken, so an id names one call until it ends. Whoever takes a call out of
 * its slot ends it, so each call ends exactly once.
 */
final class PendingCalls {
    private final AtomicReferenceArray<Call> slots;
    /** The calls kept at most, and the number of slots less one: slots are the power of two no smaller than that. */
    private final int limit, mask;
    private final AtomicInteger lastId = new AtomicInteger();
    private final AtomicInteger size = new AtomicInteger();

    /** At most {@code limit} calls at a time, at least one. */
    PendingCalls(int limit) {
        this.limit = limit;
        int capacity = limit == 1 ? 1 : Integer.highestOneBit(limit - 1) << 1;
        mask = capacity - 1;
        slots = new AtomicReferenceArray<>(capacity);
    }

    int size() { return size.get(); }

    /** Gives {@code call} its id and keeps it; false when as many calls as the limit wait already. */
    boolean add(Call call) {
        if (size.incrementAndGet() > limit) {
            size.decrementAndGet();
            return false;
        }
        for (;;) {
            int id = lastId.incrementAndGet();
            if (id == 0) continue;
            call.id = id;
            if (slots.compareAndSet(id & mask, null, call)) return true;
        }
    }

    /** Takes out the call waiting under {@code id}; null when none does, as it ended or the id is unknown. */
    Call remove(int id) {
        int slot = id & mask;
        Call call = slots.get(slot);
        if (call == null || call.id != id || !slots.compareAndSet(slot, call, null)) return null;
        size.decrementAndGet();
        return call;
    }

    /** Takes out {@code call}; false when it ended already. */
    boolean remove(Call call) {
        if (!slots.compareAndSet(call.id & mask, call, null)) return false;
        size.decrementAndGet();
        return true;
    }

    /** Takes out every call and fails it with {@code code}, on this thread. */
    void failAll(int code) {
        for (int slot = 0; slot <= mask && size.get() > 0; slot++) {
            Call call = slots.get(slot);
            if (call != null && slots.compareAndSet(slot, call, null)) {
                size.decrementAndGet();
                call.fail(code);
            }
        }
    }
}
