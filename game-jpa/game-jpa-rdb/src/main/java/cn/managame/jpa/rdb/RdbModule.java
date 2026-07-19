package cn.managame.jpa.rdb;

import cn.managame.jpa.core.bootstrap.GameJpaExtension;
import cn.managame.jpa.core.bootstrap.PersistenceConfigurer;
import cn.managame.jpa.rdb.executor.RdbExecutor;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadataResolver;
import cn.managame.jpa.rdb.repository.RdbLogRepositoryFactory;
import cn.managame.jpa.rdb.repository.RdbRepositoryFactory;

public class RdbModule implements GameJpaExtension {

    private final RdbExecutor executor;

    public RdbModule() {
        this(null);
    }

    public RdbModule(RdbExecutor executor) {
        this.executor = executor;
    }

    public static RdbModule withExecutor(RdbExecutor executor) {
        return new RdbModule(executor);
    }

    public static RdbModule defaults() {
        return new RdbModule();
    }

    @Override
    public void configure(PersistenceConfigurer configurer) {
        configurer.addResolver(new RdbEntityMetadataResolver());
        configurer.addRepositoryFactory(new RdbLogRepositoryFactory());
        configurer.addRepositoryFactory(new RdbRepositoryFactory());
        if (executor != null) {
            configurer.registerComponent(RdbExecutor.class, executor);
        }
    }
}
