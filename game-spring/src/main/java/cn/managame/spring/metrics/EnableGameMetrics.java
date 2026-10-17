package cn.managame.spring.metrics;

import java.lang.annotation.*;
import org.springframework.context.annotation.Import;

/**
 * Publishes a reading source for every managed component that is present and serves the readiness and
 * metrics paths on the Runtime's HTTP listener. Opt-in, like the other integrations: without it the
 * components still keep their counters and an application can read them through their own {@code stats()}.
 *
 * <p>Properties: {@code game.metrics.path} (default {@code /metrics}) and
 * {@code game.metrics.readiness-path} (default {@code /readiness}). Both are absolute and bypass
 * {@code game.http.context-path}, so a probe does not need to know it. Serving them needs the Runtime HTTP
 * listener enabled; the registry Bean is available either way.
 */
@Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME) @Documented
@Import(MetricsConfiguration.class)
public @interface EnableGameMetrics {
}
