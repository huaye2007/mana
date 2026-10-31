package cn.managame.data.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A field a group of entities is loaded by, such as the owning player's id: a GroupRepository loads a group with
 * one query that matches every GroupKey field. Several GroupKey fields each need a distinct {@link #order}, the
 * order of their values in {@code GroupKeys.of(...)}. Integral or String, never changed once inserted.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface GroupKey {
    int order() default Integer.MIN_VALUE;
}
