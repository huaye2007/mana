package cn.managame.annotation;

import cn.managame.core.repository.GroupRepository;

import java.lang.annotation.*;

/**
 * Identifies the key of an entity inside a GroupRepository group.
 * If absent, the repository uses the @Id field as its map key.
 * Multiple fields form a colon-separated String using annotation order.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MapKey {
    int order() default 0;
}
