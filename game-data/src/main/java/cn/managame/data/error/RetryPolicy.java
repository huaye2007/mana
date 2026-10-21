package cn.managame.data.error;

import java.sql.*;
import java.util.*;

/**
 * Decides whether a failed save keeps its changes pending for the next flush (true) or isolates and
 * drops them (false). Kept changes are retried every flush until saved or until GameData closes;
 * they are bounded by the number of distinct dirty entities, not by the number of updates.
 */
@FunctionalInterface
public interface RetryPolicy {
    boolean shouldRetry(DataFailure failure);

    /** Never keeps a failed change. */
    static RetryPolicy never() { return failure -> false; }

    /**
     * Default: keeps changes only for failures that a later attempt can fix without code or data changes:
     * lost/refused connections, pool timeouts, I/O errors, lock wait timeouts, deadlocks, and an entity read
     * while a Route changed it. Constraint violations, oversized values, SQL errors and values a codec cannot
     * encode are not retried.
     */
    static RetryPolicy transientFailures() { return failure -> isTransient(failure.cause()); }

    static boolean isTransient(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        if (error != null) pending.add(error);
        while (!pending.isEmpty()) {
            Throwable t = pending.poll();
            if (!seen.add(t)) continue;
            if (t instanceof SQLTransientException || t instanceof SQLRecoverableException
                    || t instanceof SQLNonTransientConnectionException
                    || t instanceof java.util.concurrent.TimeoutException) return true;
            // The save thread encoded a collection a Route was changing (D-OWN-01): the next pass reads it again.
            if (t instanceof java.util.ConcurrentModificationException) return true;
            // Jackson reports a value it cannot encode as an IOException. That is the data, which no retry fixes,
            // so only other I/O counts; its cause is still looked at, which is where such a race shows up.
            if (t instanceof java.io.IOException && !t.getClass().getName().startsWith("com.fasterxml.jackson.")) return true;
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                // 08xxx connection exception, 40xxx transaction rollback (deadlock/serialization).
                if (state != null && (state.startsWith("08") || state.startsWith("40"))) return true;
                // MySQL 1205 lock wait timeout, 1213 deadlock.
                if (sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213) return true;
                if (sql.getNextException() != null) pending.add(sql.getNextException());
            }
            String name = t.getClass().getName();
            if (name.startsWith("com.mongodb.") && (name.contains("Socket") || name.contains("Timeout")
                    || name.contains("NotPrimary") || name.contains("NodeIsRecovering") || name.contains("ConnectionPool")))
                return true;
            if (t.getCause() != null) pending.add(t.getCause());
            for (Throwable suppressed : t.getSuppressed()) pending.add(suppressed);
        }
        return false;
    }
}
