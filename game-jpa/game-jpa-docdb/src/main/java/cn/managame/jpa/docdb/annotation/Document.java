package cn.managame.jpa.docdb.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个类为文档型实体。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Document {

    /**
     * collection 名称
     */
    String collection();

    /**
     * 实体所属的 home 数据源名（"住在哪个库"），默认空=默认库（{@code "default"}）。
     * 非分片读写以此为目标库；分片实体的库由 {@code RoutingStrategy} 决定。
     */
    String dataSource() default "";
}
