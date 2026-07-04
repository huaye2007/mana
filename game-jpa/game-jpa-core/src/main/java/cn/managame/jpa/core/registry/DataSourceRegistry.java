package cn.managame.jpa.core.registry;

import cn.managame.jpa.core.exception.ConfigurationException;

import java.util.Map;
import java.util.Optional;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源注册中心。
 * 管理多数据源，支持按名称查找。
 *
 * @param <D> 数据源类型（如 javax.sql.DataSource、MongoDatabase、StatefulRedisConnection 等）
 */
public class DataSourceRegistry<D> {

    private final Map<String, D> dataSources = new ConcurrentHashMap<>();
    private static final String DEFAULT = "default";

    public void register(String name, D dataSource) {
        dataSources.put(name, dataSource);
    }

    public void registerDefault(D dataSource) {
        dataSources.put(DEFAULT, dataSource);
    }

    public D get(String name) {
        D ds = dataSources.get(name);
        if (ds == null) {
            throw new ConfigurationException("DataSource not registered: '" + name
                    + "'. 请确认 RoutingStrategy 产出的数据源名都已注册。已注册: " + dataSources.keySet());
        }
        return ds;
    }

    public D getDefault() {
        return get(DEFAULT);
    }

    public Optional<D> find(String name) {
        return Optional.ofNullable(dataSources.get(name));
    }

    public boolean contains(String name) {
        return dataSources.containsKey(name);
    }

    public Collection<D> values() {
        return dataSources.values();
    }

    /** 已注册的数据源名集合（含 {@code "default"}）。 */
    public java.util.Set<String> names() {
        return java.util.Set.copyOf(dataSources.keySet());
    }
}
