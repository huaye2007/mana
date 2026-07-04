package cn.managame.network.connection;

import java.util.concurrent.atomic.AtomicLong;

public class DefaultConnectionIdGenerator implements IConnectionIdGenerator {

    private final AtomicLong nextId;

    public DefaultConnectionIdGenerator() {
        this(1L);
    }

    public DefaultConnectionIdGenerator(long initialValue) {
        if (initialValue <= 0) {
            throw new IllegalArgumentException("initialValue must be positive");
        }
        this.nextId = new AtomicLong(initialValue);
    }

    @Override
    public long nextId() {
        return nextId.getAndUpdate(current -> current == Long.MAX_VALUE ? 1L : current + 1L);
    }
}
