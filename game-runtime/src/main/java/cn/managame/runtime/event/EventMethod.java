package cn.managame.runtime.event;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface EventMethod { int order() default 0; }
