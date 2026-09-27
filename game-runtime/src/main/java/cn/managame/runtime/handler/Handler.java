package cn.managame.runtime.handler;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) @Inherited
public @interface Handler { int domain() default 0; }
