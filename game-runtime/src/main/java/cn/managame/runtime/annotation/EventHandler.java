package cn.managame.runtime.annotation;

import cn.managame.runtime.executor.ExecutorGroups;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a game event handler class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventHandler {

    /**
     * 本 handler 内监听方法的默认执行器组。不指定时默认为玩家线程池组。
     */
    byte group() default ExecutorGroups.PLAYER;
}
