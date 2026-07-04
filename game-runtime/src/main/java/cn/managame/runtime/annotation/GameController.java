package cn.managame.runtime.annotation;

import cn.managame.runtime.executor.ExecutorGroups;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a game protocol controller.
 *
 * <p>The group names the executor group used by all command methods in the
 * controller.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GameController {

    /**
     * 本控制器所有命令方法的执行器组。不指定时默认为玩家线程池组。
     */
    byte group() default ExecutorGroups.PLAYER;
}
