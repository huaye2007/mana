package cn.managame.jpa.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记字段为分片路由键（rdb 分库分表 / docdb 分片通用）。
 * <p>
 * 框架执行持久化时提取该字段值作为路由键，传给
 * {@link cn.managame.jpa.core.routing.RoutingStrategy} 决定目标数据源与物理表/分片。
 * 每个实体最多标记一个 {@code @ShardKey} 字段；未标记则视为非分片实体。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ShardKey {
}
