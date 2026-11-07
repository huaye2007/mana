package cn.managame.runtime.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method a Dispatcher calls for messages of its parameter's exact class: {@code (Message)}, or
 * {@code (HandleContext, Message)} to receive the context. It returns its
 * {@link cn.managame.runtime.dispatch.HandleResult}, the one shape of every answer:
 *
 * <pre>{@code
 * @HandleMethod HandleResult login(LoginReq req) {
 *     int code = logins.check(req);
 *     return code != 0 ? HandleResult.fail(code) : HandleResult.ok(logins.login(req));
 * }
 * }</pre>
 *
 * A handler whose answer comes later, from a call to another server say, returns void or null and answers through the
 * context, with {@code reply} or {@code fail}, from any thread. Only the first answer counts.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HandleMethod {
    /**
     * The member of the message whose value is the route key, such as {@code "guildId"}: a no-argument method of that
     * name or its getter ({@code getGuildId()}, as protobuf and JavaBeans name it), or a field, of an integral type or
     * its box. It is compiled into a method handle at registration, which fails for a message without it, and read
     * once per message. Empty, the default, routes by the domain's resolver. Every method of a domain must route by
     * the same kind of key, such as the guild's id in a guild domain.
     */
    String routeKey() default "";
}
