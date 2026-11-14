package cn.managame.data.mysql.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Says how a field's column is named, typed or filled otherwise than by the rules. Every field that is neither static
 * nor transient is a column whether or not it carries this annotation, and each attribute left out keeps its rule.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Column {
    /** The column's name; by default the field's name in snake case ({@code configId} is {@code config_id}). */
    String name() default "";

    /**
     * The column's MySQL type, as written in a CREATE TABLE, such as {@code "VARCHAR(64)"} or {@code "JSON"}: what the
     * mapper creates the column as. By default the type that fits the field: BIGINT for a long, VARCHAR(255) for a
     * String, MEDIUMTEXT for a field saved as JSON.
     */
    String type() default "";

    /**
     * The column's DEFAULT, as written in SQL, such as {@code "0"} or {@code "'none'"}: what a row that was stored
     * before the column was added has in it. By default the value the no-argument constructor gives a primitive field,
     * and none for any other field, whose column is then NULL in such a row.
     */
    String defaultValue() default "";
}
