package cn.managame.data;

import static cn.managame.FrameworkErrorCodes.DATA_LOG_SAVE_FAILED;

import cn.managame.data.internal.ChangeWriter;
import cn.managame.data.internal.LogQueue;
import java.util.Objects;

/**
 * Logs that are never read back, such as item flows, handed to the {@link cn.managame.data.spi.LogWriter} registered
 * for them: a MySQL table, a log service over HTTP, wherever the application keeps them. Extend it with the log type
 * and nothing else, {@code class ItemLogs extends LogRepository<ItemLog> {}}, and get the instance from
 * {@link GameData#repository}. Logs wait in a queue of the builder's {@code logQueueCapacity} and are written in
 * batches in the next round, which reads them then: do not change a log once inserted.
 */
public abstract class LogRepository<E> {
    private LogQueue queue;
    private ChangeWriter writer;

    protected LogRepository() {}

    final void bind(LogQueue queue, ChangeWriter writer) {
        this.queue = queue;
        this.writer = writer;
    }

    /**
     * Queues a log for the next round and returns true. With the queue full, as while the database is down, the log
     * is handed to the DataErrorHandler on this thread instead and false is returned. IllegalStateException once
     * GameData is closed.
     */
    public final boolean insert(E log) {
        Objects.requireNonNull(log, "log");
        if (queue == null) throw new IllegalStateException(getClass().getName() + " was not built by GameData");
        if (queue.add(log)) return true;
        writer.reportFailure(null, DATA_LOG_SAVE_FAILED, queue.type(), DataOperation.LOG_INSERT, log,
                new IllegalStateException(queue.capacity() + " logs are already waiting to be written"));
        return false;
    }
}
