package com.github.huaye2007.mana.runtime.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks one game protocol handler method.
 *
 * <p>The method signature must be {@code (GameTaskContext, MessageObject)}.
 * Leave {@link #routerKeyMethod()} empty to use the runtime group default, or
 * dispatch with an explicit router key from the transport layer.</p>
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
