package cn.managame.runtime.annotation;

import cn.managame.runtime.route.RouteKeyResolver;
import cn.managame.runtime.route.RouteType;
import cn.managame.runtime.route.UnspecifiedRouteKeyResolver;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Handler {
    Class<? extends RouteType> routeType();
    Class<? extends RouteKeyResolver<?>> routeKeyResolver() default UnspecifiedRouteKeyResolver.class;
}
