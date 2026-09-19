package cn.managame.annotation;

import cn.managame.core.repository.GroupRepository;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks one field as part of a GroupRepository group key.
 * A single field keeps its value type; multiple fields form a colon-separated String in order.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface GroupKey {
    int order() default 0;
}
