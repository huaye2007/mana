package cn.managame.rpc;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntConsumer;

/**
 * The calls to one peer waiting for their answer, keyed by request id, safe from any thread without locks: a table
 * of a power-of-two number of slots, the call with id {@code i} in slot {@code i & mask}. Ids count up from 1 and
 * skip 0 and the ids whose slot is still taken, so an id names one call until it ends. Whoever takes a call out of
 * its slot ends it, so each call ends exactly once.
 */
final class PendingCalls {
    /** Hears the code of each call that fails on this node, before its callback does. */
    final IntConsumer failed;
    private final AtomicReferenceArray<Call> slots;
    /** The calls kept at most, and the number of slots less one: slots are the power of two no smaller than that. */
    private final int limit, mask;
    /** Ids are its low 32 bits; the whole of it counts the calls that joined, and so never falls. */
    private final AtomicLong lastId = new AtomicLong();
    private final AtomicInteger size = new AtomicInteger();

    /** At most {@code limit} calls at a time, at least one. */
    PendingCalls(int limit, IntConsumer failed) {
        this.failed = failed;
        this.limit = limit;
        int capacity = limit == 1 ? 1 : Integer.highestOneBit(limit - 1) << 1;
        mask = capacity - 1;
        slots = new AtomicReferenceArray<>(capacity);
    }

    int size() { return size.get(); }

    /** The ids handed out, which are the calls sent, but for the rare id skipped. */
    long sent() { return lastId.get(); }

    /** Gives {@code call} its id and keeps it; false when as many calls as the limit wait already. */
    boolean add(Call call) {
        if (size.incrementAndGet() > limit) {
            size.decrementAndGet();
            return false;
        }
        do {
            call.id = nextId();
        } while (!slots.compareAndSet(call.id & mask, null, call));
        return true;
    }

    private int nextId() {
        int id = (int) lastId.incrementAndGet();
        return id != 0 ? id : (int) lastId.incrementAndGet();
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
