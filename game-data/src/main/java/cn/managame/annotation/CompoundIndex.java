package cn.managame.annotation;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(CompoundIndexes.class)
@Documented
public @interface CompoundIndex {
    String name() default "";
    String def();
    boolean unique() default false;
}
