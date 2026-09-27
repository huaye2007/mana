package cn.managame.runtime.handler;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface HandlerMethod { int domain() default 0; }
