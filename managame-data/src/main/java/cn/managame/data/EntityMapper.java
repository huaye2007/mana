package cn.managame.data;

import java.util.List;

/**
 * Loads and writes entities in one database: {@code cn.managame.data.mysql.MysqlEntityMapper},
 * {@code cn.managame.data.mongo.MongoEntityMapper}, or the application's own. One instance serves every entity type
 * registered with it through {@link GameData.Builder#repositories(EntityMapper, java.util.Collection)}.
 *
 * <p>Loads run on whichever threads read repositories; writes run on the write threads, several entity types at once
 * but one type by one thread at a time, and read the entities at that moment.
 */
public interface EntityMapper {
    /**
     * Prepares to load and write entities of {@code type}, once per type while GameData is built, and throws when they
     * cannot be kept here, such as for a missing table or column.
     */
    void prepare(EntityType type);

    /** The entity with this id, or null when there is none. */
    Object load(EntityType type, Object id) throws Exception;

    /** Every entity whose {@code @GroupKey} fields equal the key's values, in order. */
    List<Object> loadGroup(EntityType type, GroupKeys key) throws Exception;

    /**
     * Writes one batch of changes with the same operation. An UPDATE that finds an entity missing fails. Writing a batch
     * again after it failed must do no harm, because a failure that {@link #isRetryable} allows is written again in the
     * next round: write a batch in one transaction, or write every entity idempotently.
     */
    void write(EntityType type, DataOperation operation, List<Change> batch) throws Exception;

    /**
     * Whether a failure of {@link #write} may succeed when tried again, in the next round, without a change to code or
     * data; otherwise the batch is split into single changes, and those that still fail are dropped. By default, a lost
     * or refused connection, a timeout, a deadlock or lock wait (JDBC, MongoDB and {@code java.net}), and a collection a
     * business thread changed while it was being read.
     */
    default boolean isRetryable(Throwable failure) { return RetryableFailures.isRetryable(failure); }
}
