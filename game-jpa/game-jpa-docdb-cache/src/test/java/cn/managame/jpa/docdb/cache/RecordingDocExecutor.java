package cn.managame.jpa.docdb.cache;

import cn.managame.jpa.core.executor.ExecutorContext;
import cn.managame.jpa.docdb.executor.DocExecutor;
import cn.managame.jpa.docdb.metadata.DocEntityMetadata;
import cn.managame.jpa.docdb.metadata.DocFieldMetadata;
import cn.managame.jpa.docdb.query.DocQuerySpec;
import cn.managame.jpa.docdb.query.DocUpdateSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 内存 DocExecutor 桩：按 eq 条件过滤内存行，记录调用次数，写操作 no-op。
 */
class RecordingDocExecutor implements DocExecutor {

    private final List<Object> rows;
    int findCalls;
    int findAllCalls;
    int findByIdCalls;

    RecordingDocExecutor(List<?> rows) {
        this.rows = new ArrayList<>(rows);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T findById(DocEntityMetadata metadata, Object id, ExecutorContext context) {
        findByIdCalls++;
        return (T) rows.stream()
                .filter(row -> metadata.idField().accessor().get(row).equals(id))
                .findFirst()
                .orElse(null);
    }

    @Override
    public void insert(DocEntityMetadata metadata, Object entity, ExecutorContext context) {
    }

    @Override
    public void save(DocEntityMetadata metadata, Object entity, ExecutorContext context) {
    }

    @Override
    public void deleteById(DocEntityMetadata metadata, Object id, ExecutorContext context) {
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> findAll(DocEntityMetadata metadata, ExecutorContext context) {
        findAllCalls++;
        return (List<T>) new ArrayList<>(rows);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> find(DocEntityMetadata metadata, DocQuerySpec querySpec, ExecutorContext context) {
        findCalls++;
        List<Object> result = new ArrayList<>();
        for (Object row : rows) {
            if (matches(metadata, querySpec, row)) {
                result.add(row);
            }
        }
        return (List<T>) result;
    }

    @Override
    public void update(DocEntityMetadata metadata, Object id, DocUpdateSpec updateSpec, ExecutorContext context) {
    }

    private boolean matches(DocEntityMetadata metadata, DocQuerySpec querySpec, Object row) {
        for (DocQuerySpec.Filter filter : querySpec.filters()) {
            if (filter.op() != DocQuerySpec.FilterOp.EQ) {
                throw new IllegalArgumentException("Recording stub only supports eq filters");
            }
            DocFieldMetadata field = metadata.fieldByPropertyName(filter.field());
            if (field == null || !Objects.equals(field.accessor().get(row), filter.value())) {
                return false;
            }
        }
        return true;
    }
}
