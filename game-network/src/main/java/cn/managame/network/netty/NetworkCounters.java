package cn.managame.network.netty;

import cn.managame.network.connection.NetworkStats;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * The counters behind {@link NetworkStats}, shared by a listener or client and every connection it owns.
 * Every one of them is incremented either once per connection or on a branch that was already exceptional —
 * a refused write, a failed establishment, a reported throwable — so a connection that reads and writes
 * normally never touches this class. That is the point: the steady-state cost of these readings is zero,
 * not merely small, which is why there is nothing to configure or turn off.
 *
 * <p>LongAdder for the monotonic counts, because a refusal or an exception can arrive from any thread; a
 * plain AtomicLong for the live count, which has to go down as well.
 */
final class NetworkCounters {
    private final LongAdder accepted = new LongAdder(), established = new LongAdder(), closed = new LongAdder(),
            establishmentFailures = new LongAdder(), writesInactive = new LongAdder(),
            writesNotWritable = new LongAdder(), handlerExceptions = new LongAdder();
    private final AtomicLong live = new AtomicLong();

    void accepted() { accepted.increment(); }
    void established() { established.increment(); live.incrementAndGet(); }
    void closed() { closed.increment(); live.decrementAndGet(); }
    void establishmentFailed() { establishmentFailures.increment(); }
    void writeInactive() { writesInactive.increment(); }
    void writeNotWritable() { writesNotWritable.increment(); }
    void handlerException() { handlerExceptions.increment(); }

    NetworkStats snapshot() {
        return new NetworkStats(live.get(), accepted.sum(), established.sum(), closed.sum(),
                establishmentFailures.sum(), writesInactive.sum(), writesNotWritable.sum(), handlerExceptions.sum());
    }
}
