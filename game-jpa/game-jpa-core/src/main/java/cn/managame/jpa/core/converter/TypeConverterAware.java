package cn.managame.jpa.core.converter;

/**
 * Implemented by storage executors that can use the bootstrap converter registry.
 */
public interface TypeConverterAware {

    void setTypeConverterRegistry(TypeConverterRegistry registry);
}
