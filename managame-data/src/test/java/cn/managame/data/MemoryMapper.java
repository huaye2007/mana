package cn.managame.data;

import cn.managame.data.spi.Change;
import cn.managame.data.spi.EntityField;
import cn.managame.data.spi.EntityMapper;
import cn.managame.data.spi.EntityType;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * An EntityMapper over maps in memory that behaves as a database does: it keeps copies of the rows it is given, loads
 * new instances, writes a batch all or nothing, refuses to insert an id twice or update a missing row, and can be told
 * to be unreachable, to lose the acknowledgement of its next write, or to refuse rows as bad data.
 */
class MemoryMapper implements EntityMapper {
    private final Map<EntityType, Map<Object, Map<String, Object>>> tables = new HashMap<>();
    /** Writes of the next batch fail as from a database that cannot be reached. */
    volatile boolean unreachable;
    /** The next batch is written, but its caller sees the connection fail, as when an acknowledgement is lost. */
    volatile boolean failAfterNextWrite;
    /** Rows this predicate accepts are refused as bad data. */
    volatile Predicate<Object> badRows = entity -> false;
    /** Loads so far, of single entities and of groups. */
    final AtomicInteger loads = new AtomicInteger();
    /** When set, every load waits for it to open, outside this mapper's lock. */
    volatile CountDownLatch loadGate;
    /** Loads of several ids at once so far, not counted among {@code loads}, and the ids the last one was asked for. */
    final AtomicInteger bulkLoads = new AtomicInteger();
    volatile List<Object> lastBulkIds = List.of();

    @Override public synchronized void prepare(EntityType type) { tables.put(type, new LinkedHashMap<>()); }

    @Override public Object load(EntityType type, Object id) throws ConnectException, InterruptedException {
        waitAtGate();
        return loadNow(type, id);
    }

    private synchronized Object loadNow(EntityType type, Object id) throws ConnectException {
        if (unreachable) throw new ConnectException("the database cannot be reached");
        Map<String, Object> row = tables.get(type).get(id);
        return row == null ? null : entityOf(type, row);
    }

    @Override public List<Object> loadAll(EntityType type, Collection<?> ids) throws ConnectException, InterruptedException {
        bulkLoads.incrementAndGet();
        lastBulkIds = List.copyOf(ids);
        CountDownLatch gate = loadGate;
        if (gate != null) gate.await();
        return loadAllNow(type, ids);
    }

    private synchronized List<Object> loadAllNow(EntityType type, Collection<?> ids) throws ConnectException {
        if (unreachable) throw new ConnectException("the database cannot be reached");
        List<Object> entities = new ArrayList<>();
        for (Object id : ids) {
            Map<String, Object> row = tables.get(type).get(id);
            if (row != null) entities.add(entityOf(type, row));
        }
        return entities;
    }

    @Override public List<Object> loadGroup(EntityType type, GroupKeys key) throws ConnectException, InterruptedException {
        waitAtGate();
        return loadGroupNow(type, key);
    }

    private void waitAtGate() throws InterruptedException {
        loads.incrementAndGet();
        CountDownLatch gate = loadGate;
        if (gate != null) gate.await();
    }

    private synchronized List<Object> loadGroupNow(EntityType type, GroupKeys key) throws ConnectException {
        if (unreachable) throw new ConnectException("the database cannot be reached");
        List<Object> entities = new ArrayList<>();
        for (Map<String, Object> row : tables.get(type).values()) {
            boolean inGroup = true;
            for (int i = 0; i < key.size(); i++) inGroup &= key.valueAt(i).equals(row.get(type.groupKeys().get(i).name()));
            if (inGroup) entities.add(entityOf(type, row));
        }
        return entities;
    }

    @Override public synchronized void write(EntityType type, DataOperation operation, List<? extends Change> batch) throws ConnectException {
        if (unreachable) throw new ConnectException("the database cannot be reached");
        Map<Object, Map<String, Object>> rows = tables.get(type);
        for (Change change : batch) {
            if (change.entity() != null && operation != DataOperation.DELETE && badRows.test(change.entity()))
                throw new IllegalArgumentException("bad row " + change.id());
            if (operation == DataOperation.INSERT && rows.containsKey(change.id())) throw new IllegalStateException("duplicate id " + change.id());
            if (operation == DataOperation.UPDATE && !rows.containsKey(change.id())) throw new IllegalStateException("no row " + change.id());
        }
        for (Change change : batch) {
            if (operation == DataOperation.DELETE) rows.remove(change.id());
            else rows.put(change.id(), rowOf(type, change.entity()));
        }
        if (failAfterNextWrite) {
            failAfterNextWrite = false;
            throw new ConnectException("connection lost before the write was acknowledged");
        }
    }

    /** The stored rows of a type, oldest first, each by field name. */
    synchronized List<Map<String, Object>> rows(Class<?> entityClass) {
        for (Map.Entry<EntityType, Map<Object, Map<String, Object>>> table : tables.entrySet())
            if (table.getKey().entityClass() == entityClass) return List.copyOf(table.getValue().values());
        throw new IllegalArgumentException("no table for " + entityClass);
    }

    /** Stores a row as the application would have written it before this process started. */
    synchronized void put(Class<?> entityClass, Object entity) {
        for (Map.Entry<EntityType, Map<Object, Map<String, Object>>> table : tables.entrySet())
            if (table.getKey().entityClass() == entityClass) table.getValue().put(table.getKey().id().get(entity), rowOf(table.getKey(), entity));
    }

    /** Removes a row as another process would. */
    synchronized void remove(Class<?> entityClass, Object id) {
        for (Map.Entry<EntityType, Map<Object, Map<String, Object>>> table : tables.entrySet())
            if (table.getKey().entityClass() == entityClass) table.getValue().remove(id);
    }

    private static Map<String, Object> rowOf(EntityType type, Object entity) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (EntityField field : type.fields()) row.put(field.name(), copy(field.get(entity)));
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Object entityOf(EntityType type, Map<String, Object> row) {
        Object entity = type.newInstance();
        for (EntityField field : type.fields()) {
            Object value = copy(row.get(field.name())), initial = field.get(entity);
            if (value instanceof Map<?, ?> map && initial instanceof Map<?, ?> target) ((Map<Object, Object>) target).putAll(map);
            else if (value instanceof Collection<?> values && initial instanceof Collection<?> target) ((Collection<Object>) target).addAll(values);
            else field.set(entity, value);
        }
        return entity;
    }

    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) return new LinkedHashMap<>(map);
        if (value instanceof Collection<?> values) return new ArrayList<>(values);
        return value;
    }
}
