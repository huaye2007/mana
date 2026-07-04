package cn.managame.jpa.docdb;

import cn.managame.jpa.core.bootstrap.PersistenceConfigurer;
import cn.managame.jpa.core.bootstrap.PersistenceModule;
import cn.managame.jpa.docdb.executor.DocExecutor;
import cn.managame.jpa.docdb.metadata.DocEntityMetadataResolver;
import cn.managame.jpa.docdb.repository.DocRepositoryFactory;

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
