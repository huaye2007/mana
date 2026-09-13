package cn.managame.runtime.annotation;

import cn.managame.runtime.route.RouteKeyResolver;
import cn.managame.runtime.route.RouteType;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventHandler {
    Class<? extends RouteType> routeType();
    Class<? extends RouteKeyResolver<?>> routeKeyResolver();
}
