package cn.managame.runtime.timer;

import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameTask;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The timers of one route by key, so that business code cancels a timer by what it is for and all of them at once,
 * keeping no {@link Scheduled} itself:
 *
 * <pre>{@code
 * TimerGroup timers = timer.group(GameContext.of(PLAYER, playerId, PLAYER_ID, playerId));   // kept with the player online
 * timers.after(new BuffExpiry(buffId), buff.duration(), context -> buffs.expire(buffId));     // set again, it starts anew
 * timers.cancel(new BuffExpiry(buffId));                                                      // the buff was dispelled
 * timers.cancelAll();                                                                         // the player logs out
 * }</pre>
 *
 * The application keeps the group where it keeps the route's state in memory, such as its object for a player online or
 * an open dungeon; the timer keeps no groups. Every timer of a group runs on the group's route with its context. A key
 * is anything with {@code equals} and {@code hashCode}, a record or a string, and names one timer: setting it again
 * cancels the one it named. A timer due once leaves the group as its task begins, so the task may set its key again.
 * <p>
 * A group is used from tasks of its route only, which is what keeps it in order with nothing locked: any other caller
 * gets IllegalStateException. Since its timers are cancelled on their route, one cancelled or replaced never runs
 * afterwards, even when a firing already waits there. A timer due once that never runs, refused by an interceptor or
 * due after the runtime shut down, stays under its key until that is set again or cancelled.
 */
public final class TimerGroup {
    private final GameTimer timer;
    private final GameContext context;
    private final Map<Object, Scheduled> timers = new HashMap<>();

    TimerGroup(GameTimer timer, GameContext context) {
        this.timer = timer;
        this.context = context;
    }

    /** Runs {@code task} once after {@code delay}, as {@code key}'s timer in place of the one it had. */
    public Scheduled after(Object key, Duration delay, GameTask<? super GameContext> task) {
        check(key);
        Objects.requireNonNull(task, "task");
        return replace(key, timer.after(context, delay, fired -> {
            timers.remove(key);   // before the task, which may set the key again
            task.run(fired);
        }, task));
    }

    /** Runs {@code task} every {@code period}, first after one period, as {@code key}'s timer in place of the one it had. */
    public Scheduled every(Object key, Duration period, GameTask<? super GameContext> task) {
        check(key);
        return replace(key, timer.every(context, period, task, task));
    }

    /**
     * Runs {@code task} whenever {@code expression} matches, as {@code key}'s timer in place of the one it had; see
     * {@link GameTimer#cron(Context, String, GameTask)}.
     */
    public Scheduled cron(Object key, String expression, GameTask<? super GameContext> task) {
        check(key);
        return replace(key, timer.cron(context, expression, task, task));
    }

    /** Cancels {@code key}'s timer; false when it has none, or that one is done. */
    public boolean cancel(Object key) {
        check(key);
        Scheduled scheduled = timers.remove(key);
        return scheduled != null && scheduled.cancel();
    }

    /** Cancels every timer of the group, which can be used again afterwards. */
    public void cancelAll() {
        checkOnRoute();
        timers.values().forEach(Scheduled::cancel);
        timers.clear();
    }

    /** The new timer is made first, so a bad delay or expression leaves the key's timer as it was. */
    private Scheduled replace(Object key, Scheduled scheduled) {
        Scheduled replaced = timers.put(key, scheduled);
        if (replaced != null) replaced.cancel();
        return scheduled;
    }

    private void check(Object key) {
        Objects.requireNonNull(key, "key");
        checkOnRoute();
    }

    private void checkOnRoute() {
        if (Context.hasCurrent()) {
            Context current = Context.current();
            if (current.domain() == context.domain() && current.routeKey() == context.routeKey()) return;
        }
        throw new IllegalStateException("A timer group is used from tasks of its route only: domain " + context.domain()
                + ", route key " + context.routeKey());
    }
}
