package cn.managame.jpa.demo.executor;

import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.rdb.executor.RdbExecutor;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.rdb.metadata.RdbFieldMetadata;
import cn.managame.jpa.rdb.query.RdbQuerySpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InMemoryRdbExecutor implements RdbExecutor {

    private final Map<String, Map<Object, Object>> rows = new LinkedHashMap<>();
    private final List<ExecutorContext> contexts = new ArrayList<>();

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T findById(RdbEntityMetadata metadata, Object id, ExecutorContext context) {
        record(context);
        return (T) table(metadata, context).get(id);
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> List<T> findAll(RdbEntityMetadata metadata, ExecutorContext context) {
        record(context);
        return table(metadata, context).values().stream().map(v -> (T) v).toList();
    }

    @Override
    public synchronized void insert(RdbEntityMetadata metadata, Object entity, ExecutorContext context) {
        record(context);
        table(metadata, context).put(id(metadata, entity), entity);
    }

    @Override
    public synchronized void update(RdbEntityMetadata metadata, Object entity, ExecutorContext context) {
        record(context);
        table(metadata, context).put(id(metadata, entity), entity);
    }

    @Override
    public synchronized void upsert(RdbEntityMetadata metadata, Object entity, ExecutorContext context) {
        update(metadata, entity, context);
    }

    @Override
    public synchronized void deleteById(RdbEntityMetadata metadata, Object id, ExecutorContext context) {
        record(context);
        table(metadata, context).remove(id);
    }

    @Override
    public synchronized void batchDelete(RdbEntityMetadata metadata, List<?> ids, ExecutorContext context) {
        record(context);
        Map<Object, Object> table = table(metadata, context);
        ids.forEach(table::remove);
    }

    @Override
    public synchronized void batchInsert(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context) {
        record(context);
        for (Object entity : entities) {
            table(metadata, context).put(id(metadata, entity), entity);
        }
    }

    @Override
    public synchronized void batchUpdate(RdbEntityMetadata metadata, List<?> entities, ExecutorContext context) {
        batchInsert(metadata, entities, context);
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> List<T> query(RdbEntityMetadata metadata, RdbQuerySpec querySpec,
            ExecutorContext context) {
        record(context);
        List<Object> result = table(metadata, context).values().stream()
                .filter(entity -> matches(metadata, entity, querySpec))
                .sorted(comparator(metadata, querySpec))
                .toList();
        return page(result, querySpec.offset(), querySpec.limit()).stream().map(v -> (T) v).toList();
    }

    @Override
    public synchronized long count(RdbEntityMetadata metadata, RdbQuerySpec querySpec, ExecutorContext context) {
        return query(metadata, querySpec, context).size();
    }

    public synchronized List<ExecutorContext> contexts() {
        return List.copyOf(contexts);
    }

    public synchronized int rowCount() {
        return rows.values().stream().mapToInt(Map::size).sum();
    }

    private Map<Object, Object> table(RdbEntityMetadata metadata, ExecutorContext context) {
        return rows.computeIfAbsent(route(metadata.tableName(), context), ignored -> new LinkedHashMap<>());
    }

    private Object id(RdbEntityMetadata metadata, Object entity) {
        return metadata.idField().accessor().get(entity);
    }

    private boolean matches(RdbEntityMetadata metadata, Object entity, RdbQuerySpec querySpec) {
        for (RdbQuerySpec.Condition condition : querySpec.conditions()) {
            RdbFieldMetadata field = metadata.fieldByPropertyName(condition.property());
            Object actual = field.accessor().get(entity);
            if (!matches(actual, condition.operator(), condition.value())) {
                return false;
            }
        }
        return true;
    }

    private boolean matches(Object actual, RdbQuerySpec.Operator op, Object expected) {
        return switch (op) {
            case EQ -> java.util.Objects.equals(actual, expected);
            case NE -> !java.util.Objects.equals(actual, expected);
            case GT -> compare(actual, expected) > 0;
            case GTE -> compare(actual, expected) >= 0;
            case LT -> compare(actual, expected) < 0;
            case LTE -> compare(actual, expected) <= 0;
            case IN -> ((Collection<?>) expected).contains(actual);
        };
    }

    private Comparator<Object> comparator(RdbEntityMetadata metadata, RdbQuerySpec querySpec) {
        Comparator<Object> comparator = (left, right) -> 0;
        for (RdbQuerySpec.OrderBy orderBy : querySpec.orderBys()) {
            RdbFieldMetadata field = metadata.fieldByPropertyName(orderBy.property());
            Comparator<Object> next = Comparator.comparing(entity -> (Comparable<Object>) field.accessor().get(entity),
                    Comparator.nullsFirst(Comparator.naturalOrder()));
            if (!orderBy.ascending()) {
                next = next.reversed();
            }
            comparator = comparator.thenComparing(next);
        }
        return comparator;
    }

    private int compare(Object actual, Object expected) {
        if (actual instanceof Number left && expected instanceof Number right) {
            return Double.compare(left.doubleValue(), right.doubleValue());
        }
        return ((Comparable<Object>) actual).compareTo(expected);
    }

    private List<Object> page(List<Object> values, int offset, int limit) {
        int from = Math.max(offset, 0);
        if (from >= values.size()) {
            return List.of();
        }
        int to = limit > 0 ? Math.min(values.size(), from + limit) : values.size();
        return values.subList(from, to);
    }

    private String route(String logicalName, ExecutorContext context) {
        String physical = context.physicalTableName() != null ? context.physicalTableName() : logicalName;
        return context.dataSourceName() + "/" + physical;
    }

    private void record(ExecutorContext context) {
        contexts.add(context);
    }
}
