package cn.managame.metrics;

import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Where the framework and the application report metrics, without depending on a metrics library: the application
 * implements it over its own backend (Micrometer, Prometheus, OpenTelemetry), as {@link System.Logger} is bridged to
 * its logging. A component takes one as a builder setting, {@link #noop()} by default.
 *
 * <pre>{@code
 * Counter refused = metrics.counter("managame.runtime.tasks.refused", "domain", "1");   // once, at build
 * metrics.gauge("game.players.online", sessions::size);
 *
 * refused.increment();                                                                   // per event
 * }</pre>
 *
 * <p>Meters are created once, when a component is built or a domain, peer or the like is added, and kept in fields;
 * reporting an event is then one call, building no tags and allocating nothing. Create each name and tag set once.
 * Counters last as long as the backend; a peer that leaves and comes back under the same node id reports to the same
 * series again. Every method and meter is safe to call from any thread.
 *
 * <p>Names are lowercase and dotted, segments of a letter followed by letters, digits or underscores:
 * {@code managame.<component>.<area>.<what>} for the framework's, as its loggers are named {@code cn.managame.<component>.<area>},
 * and a prefix of the application's own for its metrics ({@code game.shop.purchases}). Tags are key, value, key,
 * value: keys follow the rule of names, and values must come from a small set (a domain, an error code's name, a
 * node id), never a player id or route key, since every value makes a series of its own.
 *
 * <p>There is no timer. Reading the clock around every task or call costs a hot path most of its time, for an average
 * that finds nothing: a component reports what it refuses, what fails and what it holds, and an application times the
 * calls it cares about with its own backend.
 */
public interface Metrics {
    /** A counter of events, which the caller increments. */
    Counter counter(String name, String... tags);

    /**
     * A counter kept elsewhere, such as a field a component counts anyway: {@code count} is read whenever the
     * backend collects, on its thread, and must never fall. It is held until the meter is closed.
     */
    Meter counter(String name, LongSupplier count, String... tags);

    /**
     * A value that rises and falls, such as players online or tasks waiting: {@code value} is read whenever the
     * backend collects, on its thread, so the value costs nothing until then. It is held until the meter is closed.
     */
    Meter gauge(String name, DoubleSupplier value, String... tags);

    /** Metrics that report nothing, the default of every component: each meter is an empty method. */
    static Metrics noop() { return NoopMetrics.INSTANCE; }
}
