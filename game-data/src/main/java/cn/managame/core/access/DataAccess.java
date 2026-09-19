package cn.managame.core.access;

import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.write.BatchResult;
import cn.managame.core.write.WriteFailureAction;
import cn.managame.core.write.WriteOperation;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Pure database/document-database access. No cache or concurrent-load coordination belongs here. */
public interface DataAccess extends AutoCloseable {
    <T, ID> Optional<T> findById(EntityMetadata<T> metadata, ID id);

    <T> List<T> find(EntityMetadata<T> metadata, Query query);

    /** Streams a complete table/collection without building an intermediate List. */
    <T> void scan(EntityMetadata<T> metadata, Consumer<T> consumer);

    /** Applies one homogeneous physical table/collection batch. */
    BatchResult applyBatch(List<WriteOperation<?>> operations);

    /** Validates mapping configuration only: no database connections or schema changes. */
    default void validateMapping(EntityMetadata<?> metadata) { }

    /** Default table/collection name used as the prefix for automatic log partitions. */
    default String defaultPhysicalName(EntityMetadata<?> metadata) { return metadata.rdbTable(); }

    /** Backend-specific upper bound used by the table writer when forming a batch. */
    default int maxBatchSize() { return Integer.MAX_VALUE; }

    /**
     * Stable key used by WriteBehindEngine. All operations resolving to the same physical table/
     * collection must return the same key so they are executed by one writer thread.
     */
    default String writerKey(EntityMetadata<?> metadata, String physicalName) {
        return metadata.type().getName() + "@" + (physicalName == null ? "default" : physicalName);
    }

    default WriteFailureAction classifyWriteFailure(Throwable error) {
        return WriteFailureAction.FAIL;
    }

    /** Safe schema alignment for the entity's default table/collection. */
    void ensureSchema(EntityMetadata<?> metadata);

    /** Safe schema alignment for a routed physical log table/collection. */
    default void ensureSchema(EntityMetadata<?> metadata, String physicalName) {
        if (physicalName == null || physicalName.isBlank()) ensureSchema(metadata);
        else throw new UnsupportedOperationException("Physical schema routing is not supported by " + getClass().getName());
    }

    @Override
    default void close() { }
}
