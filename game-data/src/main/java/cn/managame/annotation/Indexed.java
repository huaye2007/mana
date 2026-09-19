package cn.managame.annotation;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Indexed {
    String name() default "";
    boolean unique() default false;
    boolean sparse() default false;
    IndexDirection direction() default IndexDirection.ASC;
}
