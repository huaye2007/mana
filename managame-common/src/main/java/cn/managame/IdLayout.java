package cn.managame;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * How a {@link SnowflakeIdGenerator} lays out a positive long id, from the high bits to the low: the time it was made,
 * in ticks since {@code epoch}; then a sequence counting the ids of one tick; then {@code dataBits} of the
 * application's own data. Ids so sort by time, and the data part, laid out as the application likes, can be read back
 * from any id:
 *
 * <pre>{@code
 * // 41 bits of milliseconds from 2026 (until 2095), 4096 ids a millisecond per generator, 10 bits of data
 * IdLayout layout = IdLayout.DEFAULT;
 * // ids a JavaScript number holds exactly, under 2^53: seconds until 2094, 1024 ids a second, 12 bits of data
 * IdLayout small = new IdLayout(Instant.parse("2026-01-01T00:00:00Z"), Duration.ofSeconds(1), 31, 10, 12);
 * }</pre>
 *
 * The data part must tell apart every generator that makes ids at the same time and every one whose ids may meet
 * later, as when servers merge: a server id there, possibly with more bits the application chooses (an id kind, a
 * shard). A cluster shares one layout; it cannot change once ids exist. Immutable.
 *
 * @param epoch        where time starts; not in the future
 * @param tick         the unit of time, whole milliseconds, at least one
 * @param timeBits     bits of time: the layout lasts {@code tick * 2^timeBits} from the epoch
 * @param sequenceBits bits of sequence: a generator makes at most {@code 2^sequenceBits} ids a tick
 * @param dataBits     bits of the application's data, possibly none
 */
public record IdLayout(Instant epoch, Duration tick, int timeBits, int sequenceBits, int dataBits) {
    /** Milliseconds from 2026-01-01 UTC in 41 bits, lasting until 2095; 12 bits of sequence; 10 bits of data. */
    public static final IdLayout DEFAULT = new IdLayout(Instant.parse("2026-01-01T00:00:00Z"), Duration.ofMillis(1), 41, 12, 10);

    /** Throws IllegalArgumentException for a tick that is not whole milliseconds, or bits beyond the 63 of a positive long. */
    public IdLayout {
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(tick, "tick");
        if (tick.toMillis() < 1 || !tick.equals(Duration.ofMillis(tick.toMillis())))
            throw new IllegalArgumentException("tick must be whole milliseconds, at least one: " + tick);
        if (timeBits < 1 || sequenceBits < 0 || dataBits < 0 || timeBits + sequenceBits + dataBits > 63)
            throw new IllegalArgumentException("bits must be at least one of time, none or more of sequence and data, and 63 at most in all: "
                    + timeBits + " + " + sequenceBits + " + " + dataBits);
    }

    /** The largest data value an id holds. */
    public long maxData() { return (1L << dataBits) - 1; }

    /** When the time part runs out, and generators of this layout with it. */
    public Instant end() { return epoch.plusMillis(tick.toMillis() << timeBits); }

    /** When {@code id} was made, to the tick. */
    public Instant time(long id) { return epoch.plusMillis((id >>> (sequenceBits + dataBits)) * tick.toMillis()); }

    /** The place of {@code id} among the ids its generator made in the same tick. */
    public long sequence(long id) { return (id >>> dataBits) & ((1L << sequenceBits) - 1); }

    /** The data {@code id} holds. */
    public long data(long id) { return id & maxData(); }
}
