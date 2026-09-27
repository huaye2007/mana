package cn.managame.data.mongo;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
public @interface Collection { String name(); MongoIndex[] indexes() default {}; }
