package cn.managame.data.mongo;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
public @interface Field { String name() default ""; }
