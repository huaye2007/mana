package com.github.huaye2007.mana.jpa.core.converter;

/**
 * Implemented by storage executors that can use the bootstrap converter registry.
 */
public interface TypeConverterAware {

    void setTypeConverterRegistry(TypeConverterRegistry registry);
}
