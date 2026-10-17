package cn.managame.network.netty;

import cn.managame.network.connection.NetworkStats;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * The counters behind {@link NetworkStats}, shared by a listener or client and every connection it owns.
 * LongAdder for the monotonic counts, because they are incremented from every EventLoop; a plain AtomicLong
 * for the live count, which has to go down as well.
 */
final class NetworkCounters {
    private final LongAdder accepted = new LongAdder(), established = new LongAdder(), closed = new LongAdder(),
            establishmentFailures = new LongAdder(), messagesRead = new LongAdder(), writesAccepted = new LongAdder(),
            writesInactive = new LongAdder(), writesNotWritable = new LongAdder(), handlerExceptions = new LongAdder();
    private final AtomicLong live = new AtomicLong();

    void accepted() { accepted.increment(); }
    void established() { established.increment(); live.incrementAndGet(); }
    void closed() { closed.increment(); live.decrementAndGet(); }
    void establishmentFailed() { establishmentFailures.increment(); }
    void messageRead() { messagesRead.increment(); }
    void writeAccepted() { writesAccepted.increment(); }
    void writeInactive() { writesInactive.increment(); }
    void writeNotWritable() { writesNotWritable.increment(); }
    void handlerException() { handlerExceptions.increment(); }

    NetworkStats snapshot() {
        return new NetworkStats(live.get(), accepted.sum(), established.sum(), closed.sum(),
                establishmentFailures.sum(), messagesRead.sum(), writesAccepted.sum(),
                writesInactive.sum(), writesNotWritable.sum(), handlerExceptions.sum());
    }
}
