package cn.managame.runtime.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The domain the annotated handler methods run in: on a class for all its methods, on a method for that one.
 * Where a method runs and what triggers it are separate: this says where, the method annotation what.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Inherited
public @interface Domain {
    int value();
}
