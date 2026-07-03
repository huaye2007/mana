package com.github.huaye2007.mana.jpa.core.bootstrap;

import com.github.huaye2007.mana.jpa.core.context.ComponentRegistry;
import com.github.huaye2007.mana.jpa.core.registry.MetadataRegistry;

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
