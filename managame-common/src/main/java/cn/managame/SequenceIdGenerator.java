package cn.managame;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Ids counting up by one in this process, from any thread without locks: unique only until the process ends, so for
 * what lives no longer than it, such as rooms, battles or drops on a map. Ids meant to be stored, or seen by other
 * servers, come from a {@link SnowflakeIdGenerator} instead.
 */
public final class SequenceIdGenerator implements IdGenerator {
    private final AtomicLong last;

    /** Ids from 1. */
    public SequenceIdGenerator() { this(1); }

    /** Ids from {@code first}. */
    public SequenceIdGenerator(long first) { last = new AtomicLong(first - 1); }

    @Override public long generateId() { return last.incrementAndGet(); }
}
