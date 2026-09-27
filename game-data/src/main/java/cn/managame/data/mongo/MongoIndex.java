package cn.managame.data.mongo;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({})
public @interface MongoIndex { String name() default ""; String[] fields(); boolean unique() default false; }
