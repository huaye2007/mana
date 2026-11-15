package cn.managame.runtime;

import cn.managame.Metadata;
import cn.managame.MetadataKey;

/**
 * What every piece of business logic runs with. {@link #domain} and {@link #routeKey} decide ordering: tasks
 * with the same pair run one at a time in submission order. The business fields identify whom the task is
 * for. Contexts of particular origins, such as a client request or a timer, extend this interface.
 */
public interface Context {
    /** The execution domain; it selects the executor and the ordering rule. */
    int domain();

    /** Tasks of one domain with the same route key run serially. */
    long routeKey();

    /** The kind of {@link #businessId}, for example player or guild; 0 when there is none. */
    byte businessType();

    long businessId();

    /** Application-defined fields: what the context was made with, and what was {@link #put} since. */
    Metadata metadata();

    /**
     * Sets {@code key} to {@code value} in this context's metadata, in place of the value it had if any. It is for what
     * runs around every task to add what the task and all that follows read, such as a {@link TaskInterceptor} giving
     * a task that came without a trace id one:
     *
     * <pre>{@code
     * public boolean before(Context context) {
     *     if (!context.metadata().contains(TRACE)) context.put(TRACE, newTraceId());
     *     return true;
     * }
     * }</pre>
     *
     * A {@link Metadata} never changes: the context takes a new one ({@link Metadata#with}), which {@link #metadata()}
     * returns from then on. So the value shows to the rest of the task, to the interceptors after this one, and to
     * what the task starts from then on with this context's metadata, an event, a timer, a call to another server;
     * not to what took the metadata before, such as the source a message was dispatched with or a copy made earlier.
     *
     * <p>For the task's own thread, its interceptors and the task itself: two threads putting at once may lose one of
     * the values. Another thread reads what was put once the task's work reaches it, as a callback or a later task
     * does; one that reads at the very moment of a put, with nothing handed to it since, sees the metadata of before
     * or of after, never a mix. A context that runs several tasks keeps for the next what was
     * put in one: the work a task continues on its route is the same work going on, but so does a context object an
     * application keeps and runs task after task with.
     */
    <T> void put(MetadataKey<T> key, T value);

    /** The context of the task running on this thread; IllegalStateException outside a game task. */
    static Context current() {
        if (!GameRuntime.CURRENT.isBound()) throw new IllegalStateException("No game task is running on this thread");
        return GameRuntime.CURRENT.get();
    }

    /** Whether a game task is running on this thread, so that {@link #current()} has a context. */
    static boolean hasCurrent() { return GameRuntime.CURRENT.isBound(); }
}
