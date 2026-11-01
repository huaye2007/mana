package cn.managame.runtime.timer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the annotated method on a calendar schedule, on the route {@code (domain, routeKey)}, through the runtime;
 * {@link GameTimer#register(java.util.Collection)} schedules every such method of the objects it is given:
 *
 * <pre>{@code
 * @Cron(domain = WORLD, value = "0 0 5 * * *")     // every day at 05:00:00 in GameTime's zone
 * void resetDaily() { world.resetDaily(); }
 * }</pre>
 *
 * The method takes nothing or one GameContext, {@code GameContext.of(domain, routeKey)}, and what it returns is
 * ignored. The expression, read on {@link cn.managame.runtime.time.GameTime} in its zone, and when it fires are as
 * for {@link GameTimer#cron(cn.managame.runtime.Context, String, cn.managame.runtime.GameTask)}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Cron {
    /** Six fields: second, minute, hour, day of month, month, day of week. */
    String value();

    int domain();

    long routeKey() default 0;
}
