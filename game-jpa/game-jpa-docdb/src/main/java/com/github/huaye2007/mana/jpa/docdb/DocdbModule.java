package com.github.huaye2007.mana.jpa.docdb;

import com.github.huaye2007.mana.jpa.core.bootstrap.PersistenceConfigurer;
import com.github.huaye2007.mana.jpa.core.bootstrap.PersistenceModule;
import com.github.huaye2007.mana.jpa.docdb.executor.DocExecutor;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocEntityMetadataResolver;
import com.github.huaye2007.mana.jpa.docdb.repository.DocRepositoryFactory;

public class DocdbModule implements PersistenceModule {

    private final DocExecutor executor;

    public DocdbModule() {
        this(null);
    }

    public DocdbModule(DocExecutor executor) {
        this.executor = executor;
    }

    public static DocdbModule withExecutor(DocExecutor executor) {
        return new DocdbModule(executor);
    }

    @Override
    public void configure(PersistenceConfigurer configurer) {
        configurer.addResolver(new DocEntityMetadataResolver());
        configurer.addRepositoryFactory(new DocRepositoryFactory());
        if (executor != null) {
            configurer.registerComponent(DocExecutor.class, executor);
        }
    }
}
