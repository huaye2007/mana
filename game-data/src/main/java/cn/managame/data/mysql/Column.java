package cn.managame.data.mysql;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
public @interface Column {
    String name() default "";
    ColumnType type() default ColumnType.DEFAULT;
    String defaultValue() default "";
}
