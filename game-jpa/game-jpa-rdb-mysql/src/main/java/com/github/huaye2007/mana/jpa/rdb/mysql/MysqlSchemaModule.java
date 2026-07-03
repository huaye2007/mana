package com.github.huaye2007.mana.jpa.rdb.mysql;

import com.github.huaye2007.mana.jpa.core.bootstrap.BootstrapHook;
import com.github.huaye2007.mana.jpa.core.bootstrap.ModelTypes;
import com.github.huaye2007.mana.jpa.core.bootstrap.PersistenceConfigurer;
import com.github.huaye2007.mana.jpa.core.bootstrap.PersistenceModule;
import com.github.huaye2007.mana.jpa.core.context.ComponentRegistry;
import com.github.huaye2007.mana.jpa.core.exception.GameJpaException;
import com.github.huaye2007.mana.jpa.core.metadata.EntityMetadata;
import com.github.huaye2007.mana.jpa.core.registry.MetadataRegistry;
import com.github.huaye2007.mana.jpa.core.routing.RoutingStrategyRegistry;
import com.github.huaye2007.mana.jpa.rdb.metadata.RdbEntityMetadata;

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
                assertNoRoutedShardedEntities(metadataRegistry, registry.find(RoutingStrategyRegistry.class));
                generator.synchronize(metadataRegistry, mode);
            }
        });
    }

    private void assertNoRoutedShardedEntities(MetadataRegistry metadataRegistry,
            RoutingStrategyRegistry routingRegistry) {
        if (routingRegistry == null) {
            return;
        }
        for (EntityMetadata entityMetadata : metadataRegistry.getByModel(ModelTypes.RDB)) {
            if (entityMetadata instanceof RdbEntityMetadata rdbMetadata
                    && rdbMetadata.hasShardKey()
                    && routingRegistry.resolve(rdbMetadata.entityType(), rdbMetadata.logicalName()) != null) {
                throw new GameJpaException("MysqlSchemaModule cannot synchronize sharded RDB entity "
                        + rdbMetadata.entityType().getName()
                        + " because physical tables/data sources are resolved by RoutingStrategy. "
                        + "Manage shard DDL with an explicit migration or generated physical-table scripts.");
            }
        }
    }
}
