package cn.managame.network.netty;

import java.time.Duration;

public final class NetworkOptions {
    private NetworkOptions() {}

    private static NetworkOption<Duration> duration(String n, Duration d, boolean zero) {
        return new NetworkOption<>(
                n,
                Duration.class,
                d,
                v ->
                        !v.isNegative()
                                && (zero || !v.isZero())
                                && v.compareTo(Duration.ofDays(365)) <= 0);
    }

    public static final NetworkOption<Duration> READ_IDLE =
            duration("READ_IDLE", Duration.ZERO, true);
    public static final NetworkOption<Duration> WRITE_IDLE =
            duration("WRITE_IDLE", Duration.ZERO, true);
    public static final NetworkOption<Duration> ALL_IDLE =
            duration("ALL_IDLE", Duration.ZERO, true);
    public static final NetworkOption<Duration> START_TIMEOUT =
            duration("START_TIMEOUT", Duration.ofSeconds(30), false);
    public static final NetworkOption<Duration> STOP_TIMEOUT =
            duration("STOP_TIMEOUT", Duration.ofSeconds(10), false);
    public static final NetworkOption<Duration> DESTROY_TIMEOUT =
            duration("DESTROY_TIMEOUT", Duration.ofSeconds(10), false);

    /** Protocol aggregation limits, not connection queue budgets. */
    public static final NetworkOption<Integer> WS_AGGREGATION =
            new NetworkOption<>("WS_AGGREGATION", Integer.class, 0, v -> v >= 0);

    public static final NetworkOption<Integer> WS_UPGRADE_AGGREGATION =
            new NetworkOption<>("WS_UPGRADE_AGGREGATION", Integer.class, 1024 * 1024, v -> v > 0);
}
