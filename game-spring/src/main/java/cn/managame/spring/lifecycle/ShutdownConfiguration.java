package cn.managame.spring.lifecycle;

import cn.managame.boot.lifecycle.ShutdownBudget;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The shutdown budget, shared by whichever integrations are enabled. Imported by both the Runtime and the
 * Data configuration, so it exists whenever either of them does and both read the same one; Spring
 * collapses the repeated import to a single bean definition.
 */
@Configuration(proxyBeanMethods = false)
public class ShutdownConfiguration {
    @Bean
    ShutdownBudget gameShutdownBudget(Environment environment) {
        return ShutdownBudget.of(
                environment.getProperty("game.shutdown.budget-millis", Long.class, ShutdownBudget.DEFAULT_TOTAL_MILLIS),
                environment.getProperty("game.shutdown.lead-millis", Long.class, ShutdownBudget.DEFAULT_LEAD_MILLIS),
                environment.getProperty("game.shutdown.flush-reserve-millis", Long.class,
                        ShutdownBudget.DEFAULT_FLUSH_RESERVE_MILLIS));
    }
}
