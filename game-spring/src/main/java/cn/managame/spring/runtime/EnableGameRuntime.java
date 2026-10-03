package cn.managame.spring.runtime;

import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.event.EventHandler;
import cn.managame.runtime.http.HttpHandler;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.AliasFor;
import java.lang.annotation.*;

/** Discovers Runtime handlers and Cron owners; Domain policy is supplied by configurer Beans. */
@Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME) @Documented
@Import(RuntimeConfiguration.class)
@ComponentScan(useDefaultFilters = false, includeFilters = {
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = {Handler.class, EventHandler.class, HttpHandler.class}),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = CronMethodFilter.class)})
public @interface EnableGameRuntime {
    @AliasFor(annotation = ComponentScan.class, attribute = "basePackages")
    String[] basePackages() default {};
}
