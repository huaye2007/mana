package cn.managame.data.mysql;
import java.lang.annotation.*;
/**
 * Optional column settings. Every non-static, non-transient field is a column whether or not it carries this
 * annotation (D-MAP-01); the annotation only renames a column, picks its type, sets its default or sizes it.
 */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
public @interface Column {
    String name() default "";
    ColumnType type() default ColumnType.DEFAULT;
    String defaultValue() default "";
    /** VARCHAR length of a String column under DEFAULT; 0 means 255. Longer text belongs in TEXT. */
    int length() default 0;
}
