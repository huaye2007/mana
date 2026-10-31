package cn.managame.data.mysql.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Renames a column. Every field that is neither static nor transient is a column whether or not it carries this
 * annotation, named after the field in snake case ({@code configId} is {@code config_id}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Column {
    String name();
}
