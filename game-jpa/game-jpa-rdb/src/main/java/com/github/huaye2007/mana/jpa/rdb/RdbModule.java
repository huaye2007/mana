package com.github.huaye2007.mana.jpa.rdb;

import com.github.huaye2007.mana.jpa.core.bootstrap.PersistenceConfigurer;
import com.github.huaye2007.mana.jpa.core.bootstrap.PersistenceModule;
import com.github.huaye2007.mana.jpa.rdb.executor.RdbExecutor;
import com.github.huaye2007.mana.jpa.rdb.metadata.RdbEntityMetadataResolver;
import com.github.huaye2007.mana.jpa.rdb.repository.RdbLogRepositoryFactory;
import com.github.huaye2007.mana.jpa.rdb.repository.RdbRepositoryFactory;

public class RdbModule implements PersistenceModule {

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
