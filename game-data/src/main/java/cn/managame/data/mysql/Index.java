package cn.managame.data.mysql;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({})
public @interface Index {
    String name() default "";
    String columnList();
    boolean unique() default false;
}
