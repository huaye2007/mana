package cn.managame.runtime;

import cn.managame.Metadata;

/**
 * What every piece of business logic runs with. {@link #domain} and {@link #routingKey} decide ordering: tasks
 * with the same pair run one at a time in submission order. The business fields identify whom the task is
 * for. Contexts of particular origins, such as a client request or a timer, extend this interface.
 */
public interface Context {
    /** The execution domain; it selects the executor and the ordering rule. */
    int domain();

    /** Tasks of one domain with the same routing key run serially. */
    long routingKey();

    /** The kind of {@link #businessId}, for example player or guild; 0 when there is none. */
    byte businessType();

    long businessId();

    /** Application-defined fields. */
    Metadata metadata();

    /** The context of the task running on this thread; IllegalStateException outside a game task. */
    static Context current() {
        if (!GameRuntime.CURRENT.isBound()) throw new IllegalStateException("No game task is running on this thread");
        return GameRuntime.CURRENT.get();
    }

    /** Whether a game task is running on this thread, so that {@link #current()} has a context. */
    static boolean hasCurrent() { return GameRuntime.CURRENT.isBound(); }
}
