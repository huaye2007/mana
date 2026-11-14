package cn.managame.data.spi;

import cn.managame.data.GameData;
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
     * Writes a batch of logs of one type, in the order they were inserted. A batch that failed is written again, whole
     * in the next round or one log at a time in this one, so a writer that may have written part of a batch writes logs
     * a reader can tell apart, or accepts duplicates.
     */
    void write(Class<?> logType, List<Object> logs) throws Exception;

    /** Prepares to write logs of {@code logType}, once per type while GameData is built; throws when it cannot. */
    default void prepare(Class<?> logType) {}

    /**
     * Whether a failure of {@link #write} says the destination cannot take logs now, so that the batch waits whole for
     * the next round. By default, a lost or refused connection, a timeout, a deadlock or lock wait (JDBC, MongoDB and
     * {@code java.net}). A writer over HTTP that wants answers such as 503 tried again without more ado throws its own
     * exception for them and recognizes it here.
     *
     * <p>Any other failure may be a log's or the destination's, so the batch is written again one log at a time: when
     * some are written, those that fail are handed to the DataErrorHandler and dropped; when none is, all are kept for
     * the next round.
     */
    default boolean isRetryable(Throwable failure) { return RetryableFailures.isRetryable(failure); }
}
