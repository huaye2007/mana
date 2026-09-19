package cn.managame.annotation;

import java.lang.annotation.*;

/** JPA-style index declaration using physical column names, optionally followed by ASC or DESC. */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Index {
    String name() default "";
    String columnList();
    boolean unique() default false;
}
