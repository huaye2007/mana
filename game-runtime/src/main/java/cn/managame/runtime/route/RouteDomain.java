package cn.managame.runtime.route;

import java.util.Objects;
public record RouteDomain(int id, String name) {
    public RouteDomain { if (id <= 0) throw new IllegalArgumentException("Domain must be positive"); Objects.requireNonNull(name); }
    public static RouteDomain of(int id, String name) { return new RouteDomain(id, name); }
}
