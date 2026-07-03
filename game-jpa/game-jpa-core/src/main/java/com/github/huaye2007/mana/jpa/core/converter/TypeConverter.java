package com.github.huaye2007.mana.jpa.core.converter;

/**
 * 类型转换器 SPI。
 * 负责 Java 类型与存储类型之间的双向转换。
 *
 * @param <S> Java 源类型
 * @param <T> 存储目标类型
 */
public interface TypeConverter<S, T> {

    Class<S> sourceType();

    Class<T> targetType();

    /**
     * 写入方向：Java → 存储
     */
    T write(S source);

    /**
     * 读取方向：存储 → Java
     */
    S read(T target);
}
