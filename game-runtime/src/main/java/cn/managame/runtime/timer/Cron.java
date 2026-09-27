package cn.managame.runtime.timer;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface Cron { String value(); int domain(); long routeKey(); }
