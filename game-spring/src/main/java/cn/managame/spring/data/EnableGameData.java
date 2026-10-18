package cn.managame.spring.data;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Repository;
import java.lang.annotation.*;

/** Registers annotated Repository Beans and initializes them against the state and log sources the application declares. */
@Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME) @Documented
@Import(DataConfiguration.class)
@ComponentScan(useDefaultFilters = false, includeFilters = @ComponentScan.Filter(Repository.class))
public @interface EnableGameData {
    @AliasFor(annotation = ComponentScan.class, attribute = "basePackages")
    String[] basePackages() default {};
}
