package cn.managame.runtime.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method a Dispatcher calls for messages of its parameter's exact class: {@code (Message)}, or
 * {@code (HandleContext, Message)} to receive the context. It returns void and answers through the context, with
 * {@code reply} or {@code fail}, while it runs or later from any thread:
 *
 * <pre>{@code
 * @HandleMethod void login(HandleContext context, LoginReq req) {
 *     int code = logins.check(req);
 *     if (code != 0) context.fail(code);
 *     else context.reply(logins.login(req));
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HandleMethod {}
