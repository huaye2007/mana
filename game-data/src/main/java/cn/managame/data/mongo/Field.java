package cn.managame.data.mongo;
import java.lang.annotation.*;
/**
 * Optional field name. Every non-static, non-transient field is saved whether or not it carries this
 * annotation (D-MAP-01); the annotation only renames it in the document.
 */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
public @interface Field { String name() default ""; }
