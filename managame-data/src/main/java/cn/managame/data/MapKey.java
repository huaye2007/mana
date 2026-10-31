package cn.managame.data;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A field that tells an entity apart within its group, such as a bag slot; the group's map is keyed by it.
 * Several MapKey fields each need a distinct {@link #order}, and the map key is then their values joined by
 * {@code MapKeys.of(...)}. Integral or String, never changed once inserted.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface MapKey {
    int order() default Integer.MIN_VALUE;
}
