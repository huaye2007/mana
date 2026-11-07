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
 * before the argument: {@code (HttpRequestContext, Argument)}. A non-null result is the answer: an {@link HttpResult}
 * for an API's common shape, any type of the endpoint's own that the {@link HttpCodec} writes, or a
 * {@code FullHttpResponse}, sent as is. A void method answers later through the context; null from any other is a bug,
 * answered with RUNTIME_EXECUTION_ERROR. A failure goes to {@link HttpCodec#encodeFailure} whatever the method returns.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HttpMethod {
    /** The path, starting with a slash. */
    String value();

    /** The HTTP method, POST by default. */
    String method() default "POST";

    /**
     * The member of the argument whose value is the route key, as for
     * {@link cn.managame.runtime.annotation.HandleMethod#routeKey()}.
     */
    String routeKey() default "";
}
