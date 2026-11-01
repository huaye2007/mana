package cn.managame;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Unique, time-ordered long ids in an {@link IdLayout}, from any thread without locks, for entities that need ids
 * unique across servers and their merges, such as items, mails and guilds:
 *
 * <pre>{@code
 * SnowflakeIdGenerator ids = new SnowflakeIdGenerator(IdLayout.DEFAULT, serverId);   // the server id fills the data
 * long mailId = ids.generateId();
 * long itemId = ids.generateId(serverId << 4 | ITEM_KIND);    // or data laid out per id, with a kind in it as well
 * }</pre>
 *
 * Time is the system clock, not a game clock, which may be shifted. Ids of one generator increase whatever data they
 * hold, as the sequence lies above the data. A generator never returns an id ahead of the clock: when a tick's
 * sequence is used up, the next id takes the next tick and {@code generateId} waits for the clock to reach it, a tick
 * at most unless the clock was set back, when ids go on from the last one's time and wait for the clock to catch up
 * once that tick is used up. So ids stay unique across a restart as long as the clock at the restart is past the last
 * id made. A busy event loop or other thread that must not wait should stay well under {@code 2^sequenceBits} ids a
 * tick.
 */
public final class SnowflakeIdGenerator implements IdGenerator {
    private final IdLayout layout;
    private final long data;
    private final long epochMillis, tickMillis;
    /** The time and sequence of the last id made, as {@code tick << sequenceBits | sequence}. */
    private final AtomicLong last;

    /**
     * A generator whose {@link #generateId()} ids hold {@code data}. Throws IllegalArgumentException for data the layout
     * cannot hold, an epoch in the future, or a layout whose time has run out.
     */
    public SnowflakeIdGenerator(IdLayout layout, long data) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.data = checkData(data);
        epochMillis = layout.epoch().toEpochMilli();
        tickMillis = layout.tick().toMillis();
        long now = currentTick();
        if (now < 0) throw new IllegalArgumentException("The layout's epoch is in the future: " + layout.epoch());
        if (now >>> layout.timeBits() != 0) throw new IllegalArgumentException("The layout's time ran out at " + layout.end());
        last = new AtomicLong((now << layout.sequenceBits()) - 1);   // the first id is this tick's first
    }

    public IdLayout layout() { return layout; }

    /** The next id, holding this generator's data. */
    @Override public long generateId() { return make(data); }

    /**
     * The next id, holding {@code data} instead; IllegalArgumentException for data the layout cannot hold. The data
     * must still tell this generator apart from every other, see {@link IdLayout}.
     */
    public long generateId(long data) { return make(checkData(data)); }

    private long make(long data) {
        int sequenceBits = layout.sequenceBits();
        long now = currentTick() << sequenceBits, state, made;
        do {
            state = last.get();
            made = Math.max(state + 1, now);   // a used-up sequence carries into the next tick
        } while (!last.compareAndSet(state, made));
        long tick = made >>> sequenceBits;
        if (tick >>> layout.timeBits() != 0) throw new IllegalStateException("The layout's time ran out at " + layout.end());
        long due = epochMillis + tick * tickMillis;   // when the clock reaches the id's tick
        for (long wait = due - System.currentTimeMillis(); wait > 0; wait = due - System.currentTimeMillis()) {
            if (wait > 1) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(wait - 1));   // parking may oversleep
            else Thread.yield();
        }
        return made << layout.dataBits() | data;
    }

    private long currentTick() { return Math.floorDiv(System.currentTimeMillis() - epochMillis, tickMillis); }

    private long checkData(long data) {
        if (data < 0 || data > layout.maxData())
            throw new IllegalArgumentException("data " + data + " is not from 0 to " + layout.maxData());
        return data;
    }
}
