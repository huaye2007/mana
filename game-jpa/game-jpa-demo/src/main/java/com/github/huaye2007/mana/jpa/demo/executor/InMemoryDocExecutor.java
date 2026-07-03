package com.github.huaye2007.mana.jpa.demo.executor;

import com.github.huaye2007.mana.jpa.core.executor.ExecutorContext;
import com.github.huaye2007.mana.jpa.docdb.executor.DocExecutor;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocEntityMetadata;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocFieldMetadata;
import com.github.huaye2007.mana.jpa.docdb.query.DocQuerySpec;
import com.github.huaye2007.mana.jpa.docdb.query.DocUpdateSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InMemoryDocExecutor implements DocExecutor {

    private final Map<String, Map<Object, Object>> documents = new LinkedHashMap<>();
    private final List<ExecutorContext> contexts = new ArrayList<>();

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T findById(DocEntityMetadata metadata, Object id, ExecutorContext context) {
        record(context);
        return (T) collection(metadata, context).get(id);
    }

    @Override
    public synchronized void insert(DocEntityMetadata metadata, Object entity, ExecutorContext context) {
        save(metadata, entity, context);
    }

    @Override
    public synchronized void save(DocEntityMetadata metadata, Object entity, ExecutorContext context) {
        record(context);
        collection(metadata, context).put(id(metadata, entity), entity);
    }

    @Override
    public synchronized void deleteById(DocEntityMetadata metadata, Object id, ExecutorContext context) {
        record(context);
        collection(metadata, context).remove(id);
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> List<T> findAll(DocEntityMetadata metadata, ExecutorContext context) {
        record(context);
        return collection(metadata, context).values().stream().map(v -> (T) v).toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> List<T> find(DocEntityMetadata metadata, DocQuerySpec querySpec,
            ExecutorContext context) {
        record(context);
        List<Object> result = collection(metadata, context).values().stream()
                .filter(entity -> matches(metadata, entity, querySpec))
                .sorted(comparator(metadata, querySpec))
                .toList();
        return page(result, querySpec.skip(), querySpec.limit()).stream().map(v -> (T) v).toList();
    }

    @Override
    public synchronized void update(DocEntityMetadata metadata, Object id, DocUpdateSpec updateSpec,
            ExecutorContext context) {
        record(context);
        Object entity = collection(metadata, context).get(id);
        if (entity == null) {
            return;
        }
        for (DocUpdateSpec.UpdateOp op : updateSpec.operations()) {
            DocFieldMetadata field = field(metadata, op.field());
            Object current = field.accessor().get(entity);
            switch (op.type()) {
                case SET -> field.accessor().set(entity, op.value());
                case UNSET -> field.accessor().set(entity, null);
                case INC -> field.accessor().set(entity, increment(current, (Number) op.value()));
            }
        }
    }

    public synchronized List<ExecutorContext> contexts() {
        return List.copyOf(contexts);
    }

    private Map<Object, Object> collection(DocEntityMetadata metadata, ExecutorContext context) {
        return documents.computeIfAbsent(route(metadata.collectionName(), context), ignored -> new LinkedHashMap<>());
    }

    private Object id(DocEntityMetadata metadata, Object entity) {
        return metadata.idField().accessor().get(entity);
    }

    private boolean matches(DocEntityMetadata metadata, Object entity, DocQuerySpec querySpec) {
        for (DocQuerySpec.Filter filter : querySpec.filters()) {
            DocFieldMetadata field = field(metadata, filter.field());
            Object actual = field.accessor().get(entity);
            boolean matched = switch (filter.op()) {
                case EQ -> java.util.Objects.equals(actual, filter.value());
                case NE -> !java.util.Objects.equals(actual, filter.value());
                case IN -> ((Collection<?>) filter.value()).contains(actual);
                case EXISTS -> (actual != null) == (Boolean) filter.value();
            };
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private Comparator<Object> comparator(DocEntityMetadata metadata, DocQuerySpec querySpec) {
        Comparator<Object> comparator = (left, right) -> 0;
        for (DocQuerySpec.Sort sort : querySpec.sorts()) {
            DocFieldMetadata field = field(metadata, sort.field());
            Comparator<Object> next = Comparator.comparing(entity -> (Comparable<Object>) field.accessor().get(entity),
                    Comparator.nullsFirst(Comparator.naturalOrder()));
            if (!sort.ascending()) {
                next = next.reversed();
            }
            comparator = comparator.thenComparing(next);
        }
        return comparator;
    }

    private DocFieldMetadata field(DocEntityMetadata metadata, String fieldName) {
        DocFieldMetadata field = metadata.fieldByPropertyName(fieldName);
        if (field == null) {
            field = metadata.fieldByDocumentFieldName(fieldName);
        }
        if (field == null) {
            throw new IllegalArgumentException("Unknown document field: " + fieldName);
        }
        return field;
    }

    private Object increment(Object current, Number delta) {
        if (current instanceof Integer value) {
            return value + delta.intValue();
        }
        if (current instanceof Long value) {
            return value + delta.longValue();
        }
        if (current instanceof Double value) {
            return value + delta.doubleValue();
        }
        throw new IllegalArgumentException("Cannot increment non-numeric field: " + current);
    }

    private List<Object> page(List<Object> values, int skip, int limit) {
        int from = Math.max(skip, 0);
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
