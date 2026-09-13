package cn.managame.runtime.annotation;

import cn.managame.runtime.route.RouteType;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Cron {
    String value();
    Class<? extends RouteType> routeType();
    long routeKey();
}
