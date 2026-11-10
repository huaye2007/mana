package cn.managame.runtime.timer;

import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameTask;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Timers by key, so that business code cancels a timer by what it is for and all of them at once, keeping no
 * {@link Scheduled} itself:
 *
 * <pre>{@code
 * TimerGroup timers = timer.group();                                                       // kept with the player online
 * timers.after(new BuffExpiry(buffId), buff.duration(), context -> buffs.expire(buffId));   // set again, it starts anew
 * timers.cancel(new BuffExpiry(buffId));                                                    // the buff was dispelled
 * timers.cancelAll();                                                                       // the player logs out
 * }</pre>
 *
 * A group is {@link GameTimer}'s {@code after}, {@code every} and {@code cron} with a key before them, and like them
 * it keeps no context: each timer is given its context as it is set, the calling task's or one passed with it, and that
 * is what the runtime runs its task with, as with every task. A key is anything with {@code equals} and
 * {@code hashCode}, a record or a string, and names one timer: setting it again cancels the one it named. A timer due
 * once leaves the group as its task begins, so the task may set its key again.
 * <p>
 * The application keeps the group where it keeps the state of one route in memory, such as its object for a player
 * online or an open dungeon, and uses it from the tasks of that route only, which is what keeps it in order with nothing
 * locked; the timer keeps no groups. So a group's timers run on the route of the task that sets them: outside a task its
 * methods throw IllegalStateException, and a context of another route IllegalArgumentException. Cancelled on their
 * route, a timer cancelled or replaced never runs afterwards, even when a firing already waits there. A timer due once
 * that never runs, refused by an interceptor or due after the runtime shut down, stays under its key until that is set
 * again or cancelled.
 */
public final class TimerGroup {
    private final GameTimer timer;
    private final Map<Object, Scheduled> timers = new HashMap<>();

    TimerGroup(GameTimer timer) { this.timer = timer; }

    /**
     * Runs {@code task} once after {@code delay} as {@code key}'s timer, in place of the one it had, with a plain copy
     * of the calling task's context: same route and metadata, but a request is not answered from the timer.
     */
    public Scheduled after(Object key, Duration delay, GameTask<? super GameContext> task) {
        return after(GameContext.copyOf(Context.current()), key, delay, task);
    }

    /** Runs {@code task} once after {@code delay} with {@code context}, as {@code key}'s timer in place of the one it had. */
    public <C extends Context> Scheduled after(C context, Object key, Duration delay, GameTask<? super C> task) {
        check(context, key);
        Objects.requireNonNull(task, "task");
        return replace(key, timer.after(context, delay, fired -> {
            timers.remove(key);   // before the task, which may set the key again
            task.run(fired);
        }, task));
    }

    /**
     * Runs {@code task} every {@code period}, first after one period, as {@code key}'s timer in place of the one it had,
     * with a plain copy of the calling task's context; see {@link #after(Object, Duration, GameTask)}.
     */
    public Scheduled every(Object key, Duration period, GameTask<? super GameContext> task) {
        return every(GameContext.copyOf(Context.current()), key, period, task);
    }

    /** Runs {@code task} every {@code period} with {@code context}, as {@code key}'s timer in place of the one it had. */
    public <C extends Context> Scheduled every(C context, Object key, Duration period, GameTask<? super C> task) {
        check(context, key);
        return replace(key, timer.every(context, period, task, task));
    }

    /**
     * Runs {@code task} whenever {@code expression} matches, as {@code key}'s timer in place of the one it had, with a
     * plain copy of the calling task's context; see {@link GameTimer#cron(Context, String, GameTask)}.
     */
    public Scheduled cron(Object key, String expression, GameTask<? super GameContext> task) {
        return cron(GameContext.copyOf(Context.current()), key, expression, task);
    }

    /** Runs {@code task} with {@code context} whenever {@code expression} matches, as {@code key}'s timer in place of the one it had. */
    public <C extends Context> Scheduled cron(C context, Object key, String expression, GameTask<? super C> task) {
        check(context, key);
        return replace(key, timer.cron(context, expression, task, task));
    }

    /** Cancels {@code key}'s timer; false when it has none, or that one is done. */
    public boolean cancel(Object key) {
        Objects.requireNonNull(key, "key");
        checkInTask();
        Scheduled scheduled = timers.remove(key);
        return scheduled != null && scheduled.cancel();
    }

    /** Cancels every timer of the group, which can be used again afterwards. */
    public void cancelAll() {
        checkInTask();
        timers.values().forEach(Scheduled::cancel);
        timers.clear();
    }

    /** The new timer is made first, so a bad delay or expression leaves the key's timer as it was. */
    private Scheduled replace(Object key, Scheduled scheduled) {
        Scheduled replaced = timers.put(key, scheduled);
        if (replaced != null) replaced.cancel();
        return scheduled;
    }

    /** The timer's task takes it out of the group on the route of {@code context}, which must be the one using the group. */
    private static void check(Context context, Object key) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(key, "key");
        checkInTask();
        Context current = Context.current();
        if (context.domain() != current.domain() || context.routeKey() != current.routeKey())
            throw new IllegalArgumentException("A group's timer runs on the route of the task that sets it, domain " + current.domain()
                    + ", route key " + current.routeKey() + ", not domain " + context.domain() + ", route key " + context.routeKey());
    }

    private static void checkInTask() {
        if (!Context.hasCurrent()) throw new IllegalStateException("A timer group is used from the tasks of its route only");
    }
}
