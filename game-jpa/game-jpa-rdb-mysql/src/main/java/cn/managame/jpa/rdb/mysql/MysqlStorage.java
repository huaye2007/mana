package cn.managame.jpa.rdb.mysql;

import cn.managame.jpa.core.bootstrap.GameJpaExtension;
import cn.managame.jpa.core.bootstrap.PersistenceConfigurer;
import cn.managame.jpa.core.registry.DataSourceRegistry;
import cn.managame.jpa.rdb.executor.RdbExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Configures the MySQL backend from a single data-source definition.
 *
 * <p>The storage owns the {@link MysqlRdbExecutor} registration and optional
 * schema synchronization. Repository behavior remains an independent choice:
 * use {@code RdbModule.defaults()} for direct repositories or
 * {@code RdbCacheModule.defaults()} for cache-backed repositories.</p>
 */
public final class MysqlStorage implements GameJpaExtension {

    private final DataSource schemaDataSource;
    private final MysqlRdbExecutor executor;
    private MysqlSchemaGenerator.Mode schemaMode;
    private ObjectMapper objectMapper;

    private MysqlStorage(DataSource dataSource) {
        this.schemaDataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.executor = new MysqlRdbExecutor(dataSource);
    }

    private MysqlStorage(DataSourceRegistry<DataSource> dataSources) {
        DataSourceRegistry<DataSource> registry = Objects.requireNonNull(dataSources, "dataSources");
        this.schemaDataSource = registry.getDefault();
        this.executor = new MysqlRdbExecutor(registry);
    }

    public static MysqlStorage using(DataSource dataSource) {
        return new MysqlStorage(dataSource);
    }

    /**
     * Uses a named data-source registry. Automatic schema synchronization applies
     * only to its default data source; routed/sharded schemas require migrations.
     */
    public static MysqlStorage using(DataSourceRegistry<DataSource> dataSources) {
        return new MysqlStorage(dataSources);
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
            MysqlSchemaGenerator generator = objectMapper == null
                    ? new MysqlSchemaGenerator(schemaDataSource)
                    : new MysqlSchemaGenerator(schemaDataSource, objectMapper);
            MysqlSchemaModule.withGenerator(generator, schemaMode).configure(configurer);
        }
    }
}
