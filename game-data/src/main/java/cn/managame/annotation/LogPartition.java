package cn.managame.annotation;

import java.lang.annotation.*;

/** Marks the event-time field used for automatic log table/collection partitioning. */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LogPartition {
    Period value();
    /** Time zone for Instant and epoch-millisecond long/Long fields. */
    String zone() default "UTC";
    enum Period { DAY, MONTH, YEAR }
}
