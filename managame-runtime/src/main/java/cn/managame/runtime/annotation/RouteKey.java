package cn.managame.runtime.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the integral field or no-argument method of a message that is its route key in the given domains, for
 * domains resolving keys with RouteKeys.annotated. On a record component it applies to the accessor.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface RouteKey {
    /** The domains this member routes the message in. */
    int[] value();
}
