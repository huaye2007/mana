package cn.managame.data.mysql;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
public @interface Table { String name(); Index[] indexes() default {}; }
