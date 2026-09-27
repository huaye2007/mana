package cn.managame.runtime.event;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) @Inherited
public @interface EventHandler {}
