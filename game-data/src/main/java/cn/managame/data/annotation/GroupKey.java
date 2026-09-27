package cn.managame.data.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface GroupKey { int order() default Integer.MIN_VALUE; }
