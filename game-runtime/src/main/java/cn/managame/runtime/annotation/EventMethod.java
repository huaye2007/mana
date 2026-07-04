package cn.managame.runtime.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventMethod {

    /**
     * 执行器组。为 0（默认）时继承类级 {@link EventHandler#group()}；
     * 两级都不指定则落到玩家线程池组。
     */
    byte group() default 0;


    /**
     * Listener order. Smaller values run first.
     */
    int order() default 0;
}
