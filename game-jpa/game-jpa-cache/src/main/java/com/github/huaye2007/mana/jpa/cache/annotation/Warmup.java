package com.github.huaye2007.mana.jpa.cache.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a small, stable entity table whose rows should be loaded at startup and
 * kept in a non-expiring in-memory cache.
 *
 * <p>This is intended for bounded reference/configuration data. Large business
 * tables should keep using explicit cache loading or normal repository reads.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Warmup {
}
