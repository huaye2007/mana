package com.github.huaye2007.mana.jpa.core.registry;

import com.github.huaye2007.mana.jpa.core.context.ComponentRegistry;
import com.github.huaye2007.mana.jpa.core.metadata.EntityMetadata;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 实体 → home 数据源 的绑定登记表。
 * <p>
 * 解析"实体住在哪个库"，优先级：
 * <ol>
 *   <li>实体注解（{@link EntityMetadata#dataSourceName()}，来自 {@code @Table(dataSource=...)} 等），非默认即采用；</li>
 *   <li>按实体类显式注册（{@link #register}）；</li>
 *   <li>按包前缀注册（{@link #registerPackage}），命中多个时取最长前缀；</li>
 *   <li>都没有则 {@code "default"}（游戏库）。</li>
 * </ol>
 * 仅 bootstrap 期写入，运行期只读。
 */
public final class DataSourceBinding {

    public static final String DEFAULT = "default";

    private final Map<Class<?>, String> byClass = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PackageBinding> byPackage = new CopyOnWriteArrayList<>();

    private record PackageBinding(String prefix, String dataSource) {
    }

    public DataSourceBinding register(Class<?> entityType, String dataSource) {
        if (entityType == null || dataSource == null || dataSource.isEmpty()) {
            throw new IllegalArgumentException("entityType and dataSource must not be empty");
        }
        byClass.put(entityType, dataSource);
        return this;
    }

    public DataSourceBinding registerPackage(String packagePrefix, String dataSource) {
        if (packagePrefix == null || packagePrefix.isEmpty() || dataSource == null || dataSource.isEmpty()) {
            throw new IllegalArgumentException("packagePrefix and dataSource must not be empty");
        }
        byPackage.add(new PackageBinding(packagePrefix, dataSource));
        return this;
    }

    /**
     * 工厂便捷入口：从容器取 {@link DataSourceBinding}（未注册则只看注解），解析实体 home 数据源名。
     */
    public static String resolveHomeDataSource(ComponentRegistry registry, EntityMetadata metadata) {
        DataSourceBinding binding = registry.find(DataSourceBinding.class);
        return binding != null ? binding.resolve(metadata) : metadata.dataSourceName();
    }

    /**
     * 解析实体的 home 数据源名，按注解 &gt; 类 &gt; 包(最长前缀) &gt; default 的优先级。
     */
    public String resolve(EntityMetadata metadata) {
        String annotated = metadata.dataSourceName();
        if (annotated != null && !annotated.isEmpty() && !DEFAULT.equals(annotated)) {
            return annotated;
        }
        String byType = byClass.get(metadata.entityType());
        if (byType != null) {
            return byType;
        }
        String className = metadata.entityType().getName();
        return byPackage.stream()
                .filter(b -> className.startsWith(b.prefix()))
                .max(Comparator.comparingInt(b -> b.prefix().length()))
                .map(PackageBinding::dataSource)
                .orElse(DEFAULT);
    }
}
