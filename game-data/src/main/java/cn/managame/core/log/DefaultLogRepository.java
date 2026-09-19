package cn.managame.core.log;

import cn.managame.core.DataException;
import cn.managame.core.access.DataAccess;
import cn.managame.core.metadata.EntityMetadata;
import cn.managame.core.metadata.MetadataRegistry;
import cn.managame.core.write.WriteBehindEngine;
import cn.managame.core.write.WriteOperation;
import java.util.Objects;

/** Binds entity mapping, partition rules and source writer without retaining historical table bindings. */
public final class DefaultLogRepository<T> implements LogRepository<T> {
    private final EntityMetadata<T> metadata;
    private final WriteBehindEngine writer;
    private final LogPartitioner partitioner;

    public DefaultLogRepository(Class<T> type, MetadataRegistry registry, DataAccess dataAccess, WriteBehindEngine writer) {
        this.metadata = registry.get(type);
        dataAccess.validateMapping(metadata);
        this.writer = Objects.requireNonNull(writer);
        this.partitioner = LogPartitioner.inspect(metadata, dataAccess.defaultPhysicalName(metadata));
    }

    @Override public void append(T log) {
        Objects.requireNonNull(log, "log");
        String physicalName = partitioner == null ? null : partitioner.table(log);
        Object id = metadata.idProperty().get(log);
        if (id == null) throw new DataException("@Id value must not be null on log append: " + metadata.type().getName());
        writer.submit(WriteOperation.insert(metadata, log, id, null, physicalName));
    }

    @Override public void flush() { writer.flush(metadata.type()); }
}
