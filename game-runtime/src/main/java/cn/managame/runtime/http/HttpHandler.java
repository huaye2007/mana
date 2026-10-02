package cn.managame.runtime.http;

import java.lang.annotation.*;

/** HTTP defaults; an endpoint's nonempty RouteKey configuration overrides the entire class rule. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) @Inherited
public @interface HttpHandler {
    int domain() default 0;
    /** Exact query field for GET, otherwise a top-level JSON body field. */
    String routeKey() default "";
    /** Public instance long/Long method accepting FullHttpRequest; exclusive with routeKey. */
    String routeKeyMethod() default "";
}
