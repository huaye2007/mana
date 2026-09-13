package cn.managame.runtime.execution;


import java.util.ArrayDeque;

/**
 * Iterative delivery of internal completion notifications on the completing thread.
 * Business listeners still go through Route admission. This is not an executor.
 */
final class CompletionNotifications {
    private static final ThreadLocal<ArrayDeque<Runnable>> CURRENT = new ThreadLocal<>();
    private CompletionNotifications() {}

    static void deliver(Runnable notification) {
        ArrayDeque<Runnable> pending = CURRENT.get();
        if (pending != null) {
            pending.addLast(notification);
            return;
        }
        pending = new ArrayDeque<>();
        CURRENT.set(pending);
        try {
            pending.addLast(notification);
            Runnable next;
            while ((next = pending.pollFirst()) != null) next.run();
        } finally {
            CURRENT.remove();
        }
    }
}
