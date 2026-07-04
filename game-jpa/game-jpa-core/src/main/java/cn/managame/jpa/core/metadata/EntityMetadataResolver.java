package cn.managame.jpa.core.metadata;

/**
 * 元数据解析器 SPI。
 * 每种模型提供自己的解析器实现，判断实体是否属于自身模型并构建元数据。
 *
 * @param <M> 具体元数据类型
 */
public interface EntityMetadataResolver<M extends EntityMetadata> {

    /**
     * 判断是否支持解析该实体类
     */
    boolean supports(Class<?> entityClass);

    /**
     * 解析实体类，构建元数据
     */
    M resolve(Class<?> entityClass);
}
