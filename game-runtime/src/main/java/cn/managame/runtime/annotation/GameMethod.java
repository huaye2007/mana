package cn.managame.runtime.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks one game protocol handler method.
 *
 * <p>The method signature must contain exactly two parameters. A first parameter of
 * {@code long}/{@link Long} receives the task bus id; any other reference type receives the
 * session object supplied by the host. The second parameter is the decoded message object.
 * Task context is available through {@code GameTaskContextHolder.current()}.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GameMethod {

    /**
     * Globally unique command id.
     */
    int value();

    /**
     * Method name used to extract the route key from the message object. Leave
     * empty to use the runtime group default.
     */
    String routerKeyMethod() default "";
}
