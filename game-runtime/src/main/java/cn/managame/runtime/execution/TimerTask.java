package cn.managame.runtime.execution;


import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class TimerTask {
    public enum State { SCHEDULED, CANCELLED, DISPATCHING, DISPATCHED, REJECTED }
    private final AtomicReference<State> state = new AtomicReference<>(State.SCHEDULED);
    private final Instant deadline;
    private final Consumer<TimerTask> removal;
    private final RouteTask completion;
    final Instant due;
    final boolean relative;
    final long sequence;
    final Runnable dispatch;
    final HandlerBindings.CronBinding cron;

    TimerTask(Instant deadline, Instant due, boolean relative, long sequence, Runnable dispatch,
              Consumer<TimerTask> removal, HandlerBindings.CronBinding cron, RouteTask completion) {
        this.deadline = deadline; this.due = due; this.relative = relative;
        this.sequence = sequence; this.dispatch = dispatch; this.removal = removal;
        this.cron = cron; this.completion = completion;
    }
    public boolean cancel() {
        if (!state.compareAndSet(State.SCHEDULED, State.CANCELLED)) return false;
        removal.accept(this);
        if (completion != null) completion.fail(new CancellationException("Timer cancelled before dispatch"));
        return true;
    }
    public boolean isCancelled() { return state.get() == State.CANCELLED; }
    public State state() { return state.get(); }
    /** Calendar target, or the original calendar estimate for a relative timer. */
    public Instant deadline() { return deadline; }
    public boolean isRelative() { return relative; }
    /** Business completion, dispatch failure, or cancellation; listeners still execute on a Route. */
    public RouteTask completion() {
        if (completion == null) throw new IllegalStateException("Internal scheduler entry has no business completion");
        return completion;
    }
    boolean claim() { return state.compareAndSet(State.SCHEDULED, State.DISPATCHING); }
    void dispatched() { state.set(State.DISPATCHED); }
    void rejected(Throwable failure) {
        state.set(State.REJECTED);
        if (completion != null) completion.fail(failure);
    }
}
