package cn.managame.core.access;

import java.util.*;

public final class Query {
    public enum Operator { EQ, NE, GT, GTE, LT, LTE, IN }

    public record Criterion(String property, Operator operator, Object value) {}

    private final List<Criterion> criteria;
    private final int limit;

    private Query(List<Criterion> criteria, int limit) {
        this.criteria = List.copyOf(criteria);
        this.limit = limit;
    }

    public static Query where(String property, Object value) {
        return new Query(new ArrayList<>(List.of(new Criterion(property, Operator.EQ, value))), 0);
    }

    public Query and(String property, Object value) {
        return and(property, Operator.EQ, value);
    }

    public Query and(String property, Operator operator, Object value) {
        List<Criterion> next = new ArrayList<>(criteria);
        next.add(new Criterion(property, operator, value));
        return new Query(next, limit);
    }

    public Query limit(int limit) {
        if (limit < 0) throw new IllegalArgumentException("limit < 0");
        return new Query(criteria, limit);
    }

    public List<Criterion> criteria() { return criteria; }
    public int limit() { return limit; }
}
