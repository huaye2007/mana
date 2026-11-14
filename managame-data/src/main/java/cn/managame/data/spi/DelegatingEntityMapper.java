package cn.managame.data.spi;

import cn.managame.data.DataOperation;
import cn.managame.data.GroupKeys;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * An EntityMapper that hands every call to another one, for an application to extend and override what it answers
 * itself. Every read of the database, by a SingleRepository or a GroupRepository alike, comes through {@link #load},
 * {@link #loadAll} or {@link #loadGroup} with its {@link EntityType}, so this is where an application steps in for all
 * of them at once: a mapper around the real one that returns nothing, without asking, for the data of a role created
 * minutes ago, which no table has yet. Whatever an override returns is what the repository takes the database to have.
 *
 * <p>{@link #prepare} is called once for each entity type while GameData is built, so what an override needs to know
 * of a type, such as whether its key is a role's id, is worked out there and kept, not on every load. Loads run on the
 * thread that asks, while others asking for the same key wait, so an override answers from memory or hands the call on.
 */
public abstract class DelegatingEntityMapper implements EntityMapper {
    private final EntityMapper mapper;

    /** @param mapper the mapper every call is handed to unless overridden */
    protected DelegatingEntityMapper(EntityMapper mapper) { this.mapper = Objects.requireNonNull(mapper, "mapper"); }

    @Override public void prepare(EntityType type) { mapper.prepare(type); }

    @Override public Object load(EntityType type, Object id) throws Exception { return mapper.load(type, id); }

    @Override public List<Object> loadAll(EntityType type, Collection<?> ids) throws Exception { return mapper.loadAll(type, ids); }

    @Override public List<Object> loadTable(EntityType type) throws Exception { return mapper.loadTable(type); }

    @Override public List<Object> loadGroup(EntityType type, GroupKeys key) throws Exception { return mapper.loadGroup(type, key); }

    @Override public void write(EntityType type, DataOperation operation, List<? extends Change> batch) throws Exception {
        mapper.write(type, operation, batch);
    }

    @Override public boolean isRetryable(Throwable failure) { return mapper.isRetryable(failure); }
}
