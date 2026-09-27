package cn.managame.data.mysql;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.FIELD)
public @interface PartitionKey { PartitionType type() default PartitionType.VALUE; }
