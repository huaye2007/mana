package cn.managame.jpa.rdb.mysql;

import cn.managame.jpa.core.bootstrap.BootstrapHook;
import cn.managame.jpa.core.bootstrap.ModelTypes;
import cn.managame.jpa.core.bootstrap.PersistenceConfigurer;
import cn.managame.jpa.core.bootstrap.PersistenceModule;
import cn.managame.jpa.core.context.ComponentRegistry;
import cn.managame.jpa.core.metadata.EntityMetadata;
import cn.managame.jpa.core.registry.MetadataRegistry;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Bootstrap module that synchronizes MySQL table structure from registered RDB metadata.
 */
public final class MysqlSchemaModule implements PersistenceModule {

    private final MysqlSchemaGenerator generator;
    private final MysqlSchemaGenerator.Mode mode;

    private MysqlSchemaModule(MysqlSchemaGenerator generator, MysqlSchemaGenerator.Mode mode) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public static MysqlSchemaModule create(DataSource dataSource) {
        return mode(dataSource, MysqlSchemaGenerator.Mode.CREATE);
    }

    public static MysqlSchemaModule update(DataSource dataSource) {
        return mode(dataSource, MysqlSchemaGenerator.Mode.UPDATE);
    }

    public static MysqlSchemaModule generateOnly(DataSource dataSource) {
        return mode(dataSource, MysqlSchemaGenerator.Mode.GENERATE_ONLY);
    }

    public static MysqlSchemaModule mode(DataSource dataSource, MysqlSchemaGenerator.Mode mode) {
        return withGenerator(new MysqlSchemaGenerator(Objects.requireNonNull(dataSource, "dataSource")), mode);
    }

    public static MysqlSchemaModule withGenerator(MysqlSchemaGenerator generator, MysqlSchemaGenerator.Mode mode) {
        return new MysqlSchemaModule(generator, mode);
    }

    @Override
    public void configure(PersistenceConfigurer configurer) {
        configurer.addBootstrapHook(new BootstrapHook() {
            @Override
            public void afterMetadataScan(MetadataRegistry registry) {
            }

            @Override
            public void afterContextCreated(ComponentRegistry registry) {
                MetadataRegistry metadataRegistry = registry.get(MetadataRegistry.class);
                generator.synchronize(nonShardedMetadata(metadataRegistry), mode);
            }
        });
    }

    private MetadataRegistry nonShardedMetadata(MetadataRegistry metadataRegistry) {
        MetadataRegistry result = new MetadataRegistry();
        for (EntityMetadata entityMetadata : metadataRegistry.getByModel(ModelTypes.RDB)) {
            if (entityMetadata instanceof RdbEntityMetadata rdbMetadata
                    && !rdbMetadata.hasShardKey()) {
                result.register(rdbMetadata);
            }
        }
        return result;
    }
}
