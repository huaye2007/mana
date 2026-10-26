package cn.managame.runtime.http;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method HttpRoutes calls for requests to an exact path below the listener's context path. Its one
 * parameter is the request's argument, which the routes' {@link HttpCodec} reads, normally from the body:
 *
 * <pre>{@code
 * @HttpMethod("/mail") String mail(SendMail mail) { ... }
 * }</pre>
 *
 * The {@link HttpRequestContext} is {@link HttpRequestContext#current()} while the method runs, or may be declared
 * before the argument: {@code (HttpRequestContext, Argument)}. A non-null result is the answer; void or null means
 * the answer comes later through the context.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HttpMethod {
    /** The path, starting with a slash. */
    String value();

    /** The HTTP method, POST by default. */
    String method() default "POST";
}
