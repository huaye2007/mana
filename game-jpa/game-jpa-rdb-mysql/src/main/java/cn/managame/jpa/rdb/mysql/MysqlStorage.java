package cn.managame.jpa.rdb.mysql;

import cn.managame.jpa.core.bootstrap.BootstrapHook;
import cn.managame.jpa.core.bootstrap.GameJpaExtension;
import cn.managame.jpa.core.bootstrap.ModelTypes;
import cn.managame.jpa.core.bootstrap.PersistenceConfigurer;
import cn.managame.jpa.core.context.ComponentRegistry;
import cn.managame.jpa.core.metadata.EntityMetadata;
import cn.managame.jpa.core.registry.DataSourceBinding;
import cn.managame.jpa.core.registry.DataSourceRegistry;
import cn.managame.jpa.core.registry.MetadataRegistry;
import cn.managame.jpa.rdb.executor.RdbExecutor;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configures the MySQL backend from a single data-source definition.
 *
 * <p>The storage owns the {@link MysqlRdbExecutor} registration and optional
 * schema synchronization. Repository behavior remains an independent choice:
 * use {@code RdbModule.defaults()} for direct repositories or
 * {@code RdbCacheModule.defaults()} for cache-backed repositories. Additional
 * named databases belong to this same storage and can be registered through
 * {@link #addDataSource(String, DataSource)}.</p>
 */
public final class MysqlStorage implements GameJpaExtension {

    private final DataSourceRegistry<DataSource> dataSources;
    private final MysqlRdbExecutor executor;
    private MysqlSchemaGenerator.Mode schemaMode;
    private ObjectMapper objectMapper;

    private MysqlStorage(DataSource dataSource) {
        this(defaultRegistry(dataSource));
    }

    private MysqlStorage(DataSourceRegistry<DataSource> dataSources) {
        this.dataSources = Objects.requireNonNull(dataSources, "dataSources");
        this.dataSources.getDefault();
        this.executor = new MysqlRdbExecutor(this.dataSources);
    }

    public static MysqlStorage using(DataSource dataSource) {
        return new MysqlStorage(dataSource);
    }

    /**
     * Uses a named data-source registry. Non-sharded entity schemas are synchronized
     * against the data source selected by their final home-data-source binding.
     */
    public static MysqlStorage using(DataSourceRegistry<DataSource> dataSources) {
        return new MysqlStorage(dataSources);
    }

    /** Adds a named MySQL data source to the same executor and schema configuration. */
    public MysqlStorage addDataSource(String name, DataSource dataSource) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("data source name must not be blank");
        }
        if (DataSourceBinding.DEFAULT.equals(name)) {
            throw new IllegalArgumentException("the default data source is configured by MysqlStorage.using(...)");
        }
        if (dataSources.contains(name)) {
            throw new IllegalArgumentException("data source already configured: " + name);
        }
        dataSources.register(name, Objects.requireNonNull(dataSource, "dataSource"));
        return this;
    }

    /** Leaves existing tables untouched. This is the default. */
    public MysqlStorage keepSchema() {
        this.schemaMode = null;
        return this;
    }

    public MysqlStorage createSchema() {
        return schemaMode(MysqlSchemaGenerator.Mode.CREATE);
    }

    public MysqlStorage updateSchema() {
        return schemaMode(MysqlSchemaGenerator.Mode.UPDATE);
    }

    public MysqlStorage generateSchemaOnly() {
        return schemaMode(MysqlSchemaGenerator.Mode.GENERATE_ONLY);
    }

    public MysqlStorage schemaMode(MysqlSchemaGenerator.Mode mode) {
        this.schemaMode = Objects.requireNonNull(mode, "mode");
        return this;
    }

    public MysqlStorage objectMapper(ObjectMapper mapper) {
        this.objectMapper = Objects.requireNonNull(mapper, "mapper");
        this.executor.objectMapper(mapper);
        return this;
    }

    public MysqlStorage columnAutoWiden(boolean enabled) {
        this.executor.columnAutoWiden(enabled);
        return this;
    }

    @Override
    public void configure(PersistenceConfigurer configurer) {
        Objects.requireNonNull(configurer, "configurer")
                .registerComponent(RdbExecutor.class, executor);
        if (schemaMode != null) {
            configurer.addBootstrapHook(new BootstrapHook() {
                @Override
                public void afterMetadataScan(MetadataRegistry registry) {
                }

                @Override
                public void afterContextCreated(ComponentRegistry registry) {
                    synchronizeSchemas(registry);
                }
            });
        }
    }

    private void synchronizeSchemas(ComponentRegistry components) {
        for (Map.Entry<String, MetadataRegistry> entry : resolveSchemaMetadata(components).entrySet()) {
            DataSource dataSource = dataSources.get(entry.getKey());
            newSchemaGenerator(dataSource, objectMapper).synchronize(entry.getValue(), schemaMode);
        }
    }

    Map<String, MetadataRegistry> resolveSchemaMetadata(ComponentRegistry components) {
        Map<String, MetadataRegistry> result = new LinkedHashMap<>();
        MetadataRegistry metadataRegistry = components.get(MetadataRegistry.class);
        for (EntityMetadata entityMetadata : metadataRegistry.getByModel(ModelTypes.RDB)) {
            if (entityMetadata instanceof RdbEntityMetadata rdbMetadata && !rdbMetadata.hasShardKey()) {
                String dataSourceName = DataSourceBinding.resolveHomeDataSource(components, rdbMetadata);
                // Resolve now so an unknown home data source fails before any schema is changed.
                dataSources.get(dataSourceName);
                result.computeIfAbsent(dataSourceName, ignored -> new MetadataRegistry())
                        .register(rdbMetadata);
            }
        }
        return result;
    }

    private static DataSourceRegistry<DataSource> defaultRegistry(DataSource dataSource) {
        DataSourceRegistry<DataSource> registry = new DataSourceRegistry<>();
        registry.registerDefault(Objects.requireNonNull(dataSource, "dataSource"));
        return registry;
    }

    private static MysqlSchemaGenerator newSchemaGenerator(DataSource dataSource, ObjectMapper objectMapper) {
        return objectMapper == null
                ? new MysqlSchemaGenerator(dataSource)
                : new MysqlSchemaGenerator(dataSource, objectMapper);
    }

}
