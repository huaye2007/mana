package cn.managame.data;

import java.util.List;

/**
 * Writes logs wherever the application keeps them: a database through {@code cn.managame.data.mysql.MysqlLogWriter},
 * or a log service over HTTP, written as a lambda. One instance serves every log type registered with it through
 * {@link GameData.Builder#logRepositories(LogWriter, java.util.Collection)}.
 *
 * <pre>{@code
 * LogWriter reporter = (logType, logs) -> logService.post(logType.getSimpleName(), json.encode(logs));
 * }</pre>
 *
 * <p>Runs on the write threads, several log types at once but one type by one thread at a time.
 */
@FunctionalInterface
public interface LogWriter {
    /**
     * Writes a batch of logs of one type, in the order they were inserted. Writing a batch again after it failed is
     * how a failure that {@link #isRetryable} allows is handled, so a writer that may have written part of a batch
     * writes logs a reader can tell apart, or accepts duplicates.
     */
    void write(Class<?> logType, List<Object> logs) throws Exception;

    /** Prepares to write logs of {@code logType}, once per type while GameData is built; throws when it cannot. */
    default void prepare(Class<?> logType) {}

    /**
     * Whether a failure of {@link #write} may succeed when tried again, in the next round; otherwise the batch is split
     * into single logs, and those that still fail are dropped. By default, a lost or refused connection, a timeout, a
     * deadlock or lock wait (JDBC, MongoDB and {@code java.net}). A writer over HTTP that wants answers such as 503
     * tried again throws its own exception for them and recognizes it here.
     */
    default boolean isRetryable(Throwable failure) { return RetryableFailures.isRetryable(failure); }
}
