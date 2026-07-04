package cn.managame.jpa.docdb.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 文档查询规格。
 * 支持 eq / ne / in / exists / sort / limit / skip。
 */
public class DocQuerySpec {

    private final List<Filter> filters = new ArrayList<>();
    private final List<Sort> sorts = new ArrayList<>();
    private int skip = -1;
    private int limit = -1;

    public DocQuerySpec eq(String field, Object value) {
        filters.add(new Filter(field, FilterOp.EQ, value));
        return this;
    }

    public DocQuerySpec ne(String field, Object value) {
        filters.add(new Filter(field, FilterOp.NE, value));
        return this;
    }

    public DocQuerySpec in(String field, Collection<?> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("IN values must not be empty: " + field);
        }
        filters.add(new Filter(field, FilterOp.IN, values));
        return this;
    }

    public DocQuerySpec exists(String field, boolean exists) {
        filters.add(new Filter(field, FilterOp.EXISTS, exists));
        return this;
    }

    public DocQuerySpec sortAsc(String field) {
        sorts.add(new Sort(field, true));
        return this;
    }

    public DocQuerySpec sortDesc(String field) {
        sorts.add(new Sort(field, false));
        return this;
    }

    public DocQuerySpec skip(int skip) {
        if (skip < 0) {
            throw new IllegalArgumentException("skip must be >= 0");
        }
        this.skip = skip;
        return this;
    }

    public DocQuerySpec limit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        this.limit = limit;
        return this;
    }

    public List<Filter> filters() { return Collections.unmodifiableList(filters); }
    public List<Sort> sorts() { return Collections.unmodifiableList(sorts); }
    public int skip() { return skip; }
    public int limit() { return limit; }

    public enum FilterOp { EQ, NE, IN, EXISTS }

    public record Filter(String field, FilterOp op, Object value) {}

    public record Sort(String field, boolean ascending) {}
}
