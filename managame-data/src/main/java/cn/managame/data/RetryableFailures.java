package cn.managame.data;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** The default of {@link EntityMapper#isRetryable} and {@link LogWriter#isRetryable}. */
final class RetryableFailures {
    private RetryableFailures() {}

    /**
     * Whether {@code failure}, or anything in its causes, is a lost or refused connection, a timeout, a deadlock or
     * lock wait, or a collection changed while it was read. Constraint violations, values too large, SQL errors and
     * values that cannot be encoded are the data's, which writing again does not fix.
     */
    static boolean isRetryable(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> causes = new ArrayDeque<>();
        causes.add(failure);
        while (!causes.isEmpty()) {
            Throwable cause = causes.poll();
            if (!seen.add(cause)) continue;
            if (cause instanceof ConcurrentModificationException || cause instanceof TimeoutException
                    || cause instanceof SocketException || cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException || cause instanceof SQLTransientException
                    || cause instanceof SQLRecoverableException || cause instanceof SQLNonTransientConnectionException)
                return true;
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                // 08: connection exception; 40: transaction rollback (deadlock, serialization)
                if (state != null && (state.startsWith("08") || state.startsWith("40"))) return true;
                if (sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213) return true;   // MySQL lock wait, deadlock
                if (sql.getNextException() != null) causes.add(sql.getNextException());
            }
            String name = cause.getClass().getName();
            if (name.startsWith("com.mongodb.") && (name.contains("Socket") || name.contains("Timeout")
                    || name.contains("NotPrimary") || name.contains("NodeIsRecovering") || name.contains("ConnectionPool")
                    || name.contains("WriteConcern")))
                return true;
            if (cause.getCause() != null) causes.add(cause.getCause());
            for (Throwable suppressed : cause.getSuppressed()) causes.add(suppressed);
        }
        return false;
    }
}
