package cn.managame.runtime.http;

import java.lang.annotation.*;

/** Exact raw path, excluding query; public instance method returning void or a business object. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface HttpMethod {
    String value();
    /** Endpoint method; defaults to POST. GET must be selected explicitly. */
    HttpRequestMethod method() default HttpRequestMethod.POST;
    int domain() default 0;
    /** Exact query field for GET, otherwise a top-level JSON body field; empty inherits the class rule. */
    String routeKey() default "";
    /** Public instance long/Long method accepting FullHttpRequest; exclusive with routeKey. */
    String routeKeyMethod() default "";
}
