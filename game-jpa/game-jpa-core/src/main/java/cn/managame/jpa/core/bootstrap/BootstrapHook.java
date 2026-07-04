package cn.managame.jpa.core.bootstrap;

import cn.managame.jpa.core.context.ComponentRegistry;
import cn.managame.jpa.core.registry.MetadataRegistry;

/**
 * Hook invoked during bootstrap.
 */
@FunctionalInterface
public interface BootstrapHook {

    void afterMetadataScan(MetadataRegistry registry);

    /**
     * Invoked after the runtime context and configured components have been
     * created, but before {@code GameJpaBootstrap.bootstrap(...)} returns.
     */
    default void afterContextCreated(ComponentRegistry registry) {
    }
}
