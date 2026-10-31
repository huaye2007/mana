package cn.managame.data.mysql;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Splits a log's table: each log goes to {@code <table>_<suffix>}, the suffix taken from this field. A table that
 * does not exist yet is created on first use as {@code CREATE TABLE ... LIKE <table>}. At most one per log.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface PartitionKey {
    PartitionType type() default PartitionType.VALUE;
}
