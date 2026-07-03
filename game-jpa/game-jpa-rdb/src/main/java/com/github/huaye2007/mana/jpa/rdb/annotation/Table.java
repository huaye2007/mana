package com.github.huaye2007.mana.jpa.rdb.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 指定实体对应的数据库表名。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Table {

    /**
     * 表名
     */
    String name();

    /**
     * 实体所属的 home 数据源名（"住在哪个库"），默认空=游戏库（{@code "default"}）。
     * 例如日志实体写 {@code @Table(name="login_log", dataSource="log")} 即落到日志库。
     * 非分片读写以此为目标库；分片实体的库由 {@code RoutingStrategy} 决定。
     */
    String dataSource() default "";
}
