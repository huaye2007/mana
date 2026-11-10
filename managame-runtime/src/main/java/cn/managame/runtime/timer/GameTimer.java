package cn.managame.runtime.timer;

import static cn.managame.FrameworkErrorCodes.RUNTIME_CLOSED;

import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.GameRuntimeException;
import cn.managame.runtime.GameTask;
import cn.managame.runtime.TaskKind;
import cn.managame.runtime.annotation.AnnotatedMethod;
import cn.managame.runtime.time.GameTime;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs tasks later, periodically or at calendar times on a route, through the runtime:
 *
 * <pre>{@code
 * timer.after(Duration.ofSeconds(30), context -> buffs.expire(context));      // the current task's route
 * timer.every(GameContext.of(WORLD, 0), Duration.ofMinutes(1), context -> world.tick());
 * timer.cron(GameContext.of(WORLD, 0), "0 0 5 * * *", context -> world.resetDaily());  // at five every morning
 * timer.register(List.of(new DailyJobs()));                                           // its {@link Cron} methods
 * }</pre>
 *
 * Timers live on a hashed timing wheel, so adding and cancelling one costs the same however many there are,
 * which suits a timer per player. A timer fires within one tick after it is due; the tick is 10 ms by default.
 * The wheel's single thread only hands due tasks to the runtime, so business logic never runs on it and
 * ordering with the route's other tasks holds. Delays use the monotonic clock, unaffected by changes to
 * GameTime; cron times are read on GameTime. A periodic timer keeps a fixed rate; a firing whose last one has not
 * finished is logged and skipped, so a task slower than its period lags instead of filling its route, a firing the
 * runtime refuses, for example because the route is full, is too, and once the runtime is shut down the timer stops.
 * A timer due once has no later firing, so it runs however full its route is, and is lost only to a runtime shut down.
 * A timer cancelled on its own route does not run afterwards, even when a firing already waits there.
 */
public final class GameTimer implements AutoCloseable {
    public static final Duration DEFAULT_TICK = Duration.ofMillis(10);
    private static final int WHEEL_SIZE = 512;
    /** A cron timer looks at GameTime at least this often, so a new clock or zone reschedules it within this long. */
    private static final long CRON_WAKE_MILLIS = 1000;
    private static final System.Logger LOG = System.getLogger("cn.managame.runtime.timer");
    private final GameRuntime runtime;
    private final HashedWheelTimer wheel;

    public GameTimer(GameRuntime runtime) { this(runtime, DEFAULT_TICK); }

    /** {@code tick}, at least one millisecond, is the precision: a timer fires within one tick after it is due. */
    public GameTimer(GameRuntime runtime, Duration tick) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (tick.toMillis() < 1) throw new IllegalArgumentException("tick must be at least one millisecond");
        wheel = new HashedWheelTimer(Thread.ofPlatform().name("game-timer").daemon().factory(),
                tick.toNanos(), TimeUnit.NANOSECONDS, WHEEL_SIZE, false, -1);
    }

    /**
     * Runs {@code task} once after {@code delay} on the route of the task calling this, with a plain copy of its
     * context: same route and metadata, but a request is not answered from the timer. Must be called from a game
     * task.
     */
    public Scheduled after(Duration delay, GameTask<? super GameContext> task) {
        return after(GameContext.copyOf(Context.current()), delay, task);
    }

    /** Runs {@code task} once after {@code delay} with {@code context}. */
    public <C extends Context> Scheduled after(C context, Duration delay, GameTask<? super C> task) { return after(context, delay, task, task); }

    /** {@link #after(Context, Duration, GameTask)}, counted as {@code written}: the task as the application wrote it, which a group wraps. */
    <C extends Context> Scheduled after(C context, Duration delay, GameTask<? super C> task, GameTask<?> written) {
        Firing<C> firing = new Firing<>(context, task, 0, null, runtime.taskKind(written.getClass()));
        firing.arm(nanos(delay));
        return firing;
    }

    /** Runs {@code task} every {@code period}, first after one period, on the calling task's route; see {@link #after(Duration, GameTask)}. */
    public Scheduled every(Duration period, GameTask<? super GameContext> task) {
        return every(GameContext.copyOf(Context.current()), period, task);
    }

    /** Runs {@code task} every {@code period}, first after one period, with {@code context}. */
    public <C extends Context> Scheduled every(C context, Duration period, GameTask<? super C> task) { return every(context, period, task, task); }

    <C extends Context> Scheduled every(C context, Duration period, GameTask<? super C> task, GameTask<?> written) {
        long nanos = nanos(period);
        if (nanos <= 0) throw new IllegalArgumentException("period must be positive");
        Firing<C> firing = new Firing<>(context, task, nanos, null, runtime.taskKind(written.getClass()));
        firing.nextDue = System.nanoTime() + nanos;
        firing.arm(nanos);
        return firing;
    }

    /**
     * Runs {@code task} with {@code context} whenever {@code expression} matches on {@link GameTime}, in its zone. The
     * expression has six fields, second, minute, hour, day of month, month and day of week, each {@code *}, a value, a
     * range {@code a-b}, a list {@code a,b}, or a step, a value, range or {@code *} followed by {@code /n}; months may be
     * named JAN to DEC and days of week SUN to SAT, or 0 to 7 with both 0 and 7 Sunday, and {@code ?} is the same as
     * {@code *}. When both the day of month and the day of week name days, a day matching either fires, as in Unix cron.
     * {@code L}, {@code W} and {@code #} are not supported. A malformed expression, one naming no day that exists, or an
     * unregistered domain throws IllegalArgumentException.
     * <p>
     * A cron timer looks at GameTime at least once a second. It fires when its time has come and then finds the next
     * one, after the later of now and the time just due; when GameTime's clock or zone has changed since, it finds its
     * next time again on the new one, so {@code setClock}, {@code setZone} and {@code resetClock} reschedule it within a
     * second. A local time daylight saving skips does not fire, one it repeats fires once, a firing late for a pause runs
     * once, times while the process was down are not made up, and a firing whose last one has not finished, or that the
     * runtime refuses, is logged and skipped.
     */
    public <C extends Context> Scheduled cron(C context, String expression, GameTask<? super C> task) { return cron(context, expression, task, task); }

    <C extends Context> Scheduled cron(C context, String expression, GameTask<? super C> task, GameTask<?> written) {
        runtime.checkDomain(context.domain());
        Firing<C> firing = new Firing<>(context, task, 0, CronExpression.parse(expression), runtime.taskKind(written.getClass()));
        firing.wakeCron();
        LOG.log(Level.DEBUG, () -> "Scheduled a task at '" + expression + "' in domain " + context.domain() + ", route key " + context.routeKey());
        return firing;
    }

    /**
     * Schedules every {@link Cron} method of the given objects, each with {@code GameContext.of(domain, routeKey)}, as
     * {@link #cron(Context, String, GameTask)} does, and returns their timers. A static method, one taking anything but
     * nothing or one GameContext, a bad expression or an unregistered domain throws IllegalArgumentException, and then
     * nothing is scheduled.
     */
    public List<Scheduled> register(Collection<?> targets) {
        List<CronJob> jobs = new ArrayList<>();
        for (Object target : targets)
            for (Method method : AnnotatedMethod.methods(target, Cron.class)) jobs.add(CronJob.of(runtime, target, method));
        List<Scheduled> scheduled = new ArrayList<>(jobs.size());
        for (CronJob job : jobs) {
            Firing<GameContext> firing = new Firing<>(job.context(), job.task(), 0, job.cron(), runtime.taskKind(job.name()));
            firing.wakeCron();
            scheduled.add(firing);
            LOG.log(Level.INFO, () -> "Scheduled " + job.name() + " at '" + job.cron() + "' in domain " + job.context().domain()
                    + ", route key " + job.context().routeKey());
        }
        return scheduled;
    }

    /**
     * The timers of the route of {@code context} by key, which run with a plain copy of it: for the application to keep
     * with that route's state in memory, such as a player online, and cancel by key or all at once; see
     * {@link TimerGroup}. The timer keeps no groups. An unregistered domain throws IllegalArgumentException.
     */
    public TimerGroup group(Context context) {
        runtime.checkDomain(context.domain());
        return new TimerGroup(this, GameContext.copyOf(context));
    }

    /**
     * Cancels every timer; a firing already handed to the runtime may still run. A timer added afterwards is cancelled
     * as it is made and never fires, so a task still finishing while the application shuts down sets its timers as ever.
     */
    @Override public void close() {
        for (Timeout pending : wheel.stop()) ((Firing<?>) pending.task()).state.set(Firing.CANCELLED);
    }

    /** A checked {@link Cron} method, ready to schedule. */
    private record CronJob(String name, GameContext context, CronExpression cron, GameTask<GameContext> task) {
        static CronJob of(GameRuntime runtime, Object target, Method method) {
            String name = method.getDeclaringClass().getName() + "#" + method.getName();
            Cron cron = method.getAnnotation(Cron.class);
            Class<?>[] parameters = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers())) throw new IllegalArgumentException(name + " must not be static");
            if (parameters.length > 1 || parameters.length == 1 && !parameters[0].isAssignableFrom(GameContext.class))
                throw new IllegalArgumentException(name + " must take nothing or a GameContext");
            runtime.checkDomain(cron.domain());
            MethodHandle call;
            try {
                method.setAccessible(true);
                call = MethodHandles.lookup().unreflect(method).bindTo(target);
                if (parameters.length == 0) call = MethodHandles.dropArguments(call, 0, GameContext.class);
                call = call.asType(MethodType.methodType(void.class, GameContext.class));
            } catch (IllegalAccessException | RuntimeException e) {
                throw new IllegalArgumentException("Cannot use " + name, e);
            }
            MethodHandle invoker = call;
            GameTask<GameContext> task = context -> {
                try {
                    invoker.invokeExact(context);
                } catch (Exception | Error e) {
                    throw e;
                } catch (Throwable e) {
                    throw new UndeclaredThrowableException(e);
                }
            };
            return new CronJob(name, GameContext.of(cron.domain(), cron.routeKey()), CronExpression.parse(cron.value()), task);
        }
    }

    private static long nanos(Duration duration) {
        if (duration.isNegative()) throw new IllegalArgumentException("Duration must not be negative: " + duration);
        return duration.toNanos();
    }

    private final class Firing<C extends Context> implements TimerTask, Scheduled, GameTask<C> {
        static final int ACTIVE = 0, FIRED = 1, CANCELLED = 2;
        final AtomicInteger state = new AtomicInteger(ACTIVE);
        private final C context;
        private final GameTask<? super C> task;
        /** Zero for a one-shot or cron timer. */
        private final long periodNanos;
        /** Null unless a cron timer. */
        private final CronExpression cron;
        private final TaskKind kind;
        /** When the next periodic firing is due on the monotonic clock; used by the wheel's thread only. */
        long nextDue;
        /** The GameTime clock a cron timer's next time was found on; null before its first wake-up. */
        private Clock clock;
        /** When the next cron firing is due on that clock, in epoch milliseconds. */
        private long dueMillis;
        private volatile Timeout timeout;
        /** Whether the last firing was handed to the runtime and has not finished: set on the wheel's thread, cleared on the route. */
        private volatile boolean handed;

        Firing(C context, GameTask<? super C> task, long periodNanos, CronExpression cron, TaskKind kind) {
            this.context = Objects.requireNonNull(context, "context");
            this.task = Objects.requireNonNull(task, "task");
            this.periodNanos = periodNanos;
            this.cron = cron;
            this.kind = kind;
        }

        void arm(long delayNanos) {
            try {
                timeout = wheel.newTimeout(this, delayNanos, TimeUnit.NANOSECONDS);
            } catch (IllegalStateException closed) { // the wheel takes none once stopped
                state.set(CANCELLED);
                return;
            }
            // A cancel racing with re-arming may have missed the new timeout.
            if (state.get() == CANCELLED) timeout.cancel();
        }

        /**
         * A cron timer's wake-up: on a clock or zone it has not seen, finds its next time; else fires when that time has
         * come and finds the one after. Then sleeps until that time, a second at most, to look at GameTime again.
         */
        void wakeCron() {
            Clock now = GameTime.clock();
            long nowMillis = now.millis();
            if (now != clock) {
                clock = now;
                dueMillis = cron.next(nowMillis, now.getZone());
            } else if (nowMillis >= dueMillis) {
                fire();
                if (state.get() != ACTIVE) return;
                dueMillis = cron.next(Math.max(nowMillis, dueMillis), now.getZone());
            }
            arm(TimeUnit.MILLISECONDS.toNanos(Math.min(CRON_WAKE_MILLIS, dueMillis - nowMillis)));
        }

        @Override public void run(Timeout expired) {
            if (state.get() != ACTIVE) return;
            if (cron != null) {
                wakeCron();
                return;
            }
            fire();
            if (once() || state.get() != ACTIVE) return;
            nextDue += periodNanos; // a fixed rate: late firings do not shift later ones
            arm(Math.max(0, nextDue - System.nanoTime()));
        }

        private boolean once() { return periodNanos == 0 && cron == null; }

        /**
         * Hands the task to the runtime, unless the last firing has not finished; a refusal is logged, and one because
         * the runtime is closed stops the timer. A one-shot timer goes past the limits on load, as it has no later firing
         * to make up for one refused; a periodic or cron timer keeps to them and skips.
         */
        private void fire() {
            if (handed) {
                LOG.log(Level.WARNING, "Timer firing skipped, the last one has not finished; domain=" + context.domain()
                        + ", routeKey=" + context.routeKey());
                return;
            }
            handed = true;
            try {
                if (once()) runtime.handOn(kind, context, this);
                else runtime.execute(kind, context, this);
            } catch (GameRuntimeException refused) {
                if (refused.errorCode() == RUNTIME_CLOSED) {
                    cancel();
                    return;
                }
                LOG.log(Level.WARNING, "Timer firing refused; domain=" + context.domain()
                        + ", routeKey=" + context.routeKey() + (once() ? "" : "; skipped once"), refused);
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Timer firing failed; domain=" + context.domain(), e);
            }
        }

        /** The firing on its route, where a cancel made by an earlier task of the route has the last word. */
        @Override public void run(C context) throws Exception {
            try {
                if (once() ? state.compareAndSet(ACTIVE, FIRED) : state.get() == ACTIVE) task.run(context);
            } finally {
                handed = false;
            }
        }

        /** A firing that will not run: a later one may, and a one-shot has none. */
        @Override public void refused(C context) {
            handed = false;
            if (once()) state.compareAndSet(ACTIVE, FIRED);
        }

        @Override public boolean cancel() {
            if (!state.compareAndSet(ACTIVE, CANCELLED)) return false;
            Timeout current = timeout;
            if (current != null) current.cancel();
            return true;
        }

        @Override public boolean isDone() { return state.get() != ACTIVE; }
    }
}
