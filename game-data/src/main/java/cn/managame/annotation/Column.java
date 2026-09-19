package cn.managame.annotation;

import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Column {
    String value() default "";

    /** Optional explicit storage shape. AUTO infers from the Java field type. */
    ColumnType type() default ColumnType.AUTO;

    /** VARCHAR length when type=STRING, or the default length for inferred String fields. */
    int length() default 255;
}
