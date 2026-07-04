package cn.managame.jpa.rdb.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 轻量条件查询规格。
 * 支持等值、比较、in、排序、分页。
 * <p>
 * 所有字段名使用 Java 属性名（如 "roleId"），框架在执行时自动转换为数据库列名。
 */
public class RdbQuerySpec {

    private final List<Condition> conditions = new ArrayList<>();
    private final List<OrderBy> orderBys = new ArrayList<>();
    private int offset = -1;
    private int limit = -1;

    public RdbQuerySpec eq(String property, Object value) {
        conditions.add(new Condition(property, Operator.EQ, value));
        return this;
    }

    public RdbQuerySpec ne(String property, Object value) {
        conditions.add(new Condition(property, Operator.NE, value));
        return this;
    }

    public RdbQuerySpec gt(String property, Object value) {
        conditions.add(new Condition(property, Operator.GT, value));
        return this;
    }

    public RdbQuerySpec gte(String property, Object value) {
        conditions.add(new Condition(property, Operator.GTE, value));
        return this;
    }

    public RdbQuerySpec lt(String property, Object value) {
        conditions.add(new Condition(property, Operator.LT, value));
        return this;
    }

    public RdbQuerySpec lte(String property, Object value) {
        conditions.add(new Condition(property, Operator.LTE, value));
        return this;
    }

    public RdbQuerySpec in(String property, Collection<?> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("IN values must not be empty: " + property);
        }
        conditions.add(new Condition(property, Operator.IN, values));
        return this;
    }

    public RdbQuerySpec orderByAsc(String property) {
        orderBys.add(new OrderBy(property, true));
        return this;
    }

    public RdbQuerySpec orderByDesc(String property) {
        orderBys.add(new OrderBy(property, false));
        return this;
    }

    public RdbQuerySpec offset(int offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        this.offset = offset;
        return this;
    }

    public RdbQuerySpec limit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        this.limit = limit;
        return this;
    }

    public List<Condition> conditions() { return Collections.unmodifiableList(conditions); }
    public List<OrderBy> orderBys() { return Collections.unmodifiableList(orderBys); }
    public int offset() { return offset; }
    public int limit() { return limit; }

    public enum Operator { EQ, NE, GT, GTE, LT, LTE, IN }

    /**
     * @param property Java 属性名（非列名）
     */
    public record Condition(String property, Operator operator, Object value) {}

    /**
     * @param property Java 属性名（非列名）
     */
    public record OrderBy(String property, boolean ascending) {}
}
