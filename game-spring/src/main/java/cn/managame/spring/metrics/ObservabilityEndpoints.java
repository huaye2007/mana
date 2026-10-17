package cn.managame.spring.metrics;

import cn.managame.core.metrics.MetricSink;
import cn.managame.core.metrics.MetricsRegistry;
import cn.managame.network.http.HttpResponseCallback;
import cn.managame.runtime.http.HttpDispatcher;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Serves the readiness and metrics paths on the Runtime's HTTP listener, in front of business dispatch. Both
 * are answered on the HTTP thread without entering a Route executor, so a saturated or draining instance
 * still answers — an endpoint that queues behind the work it is reporting on would say nothing useful at the
 * one moment it matters.
 *
 * <p>Paths are absolute and do not go through the configured context path: a probe should not have to know
 * it. Anything else is passed to business dispatch unchanged.
 */
public final class ObservabilityEndpoints {
    private final MetricsRegistry registry;
    private final List<ReadinessCheck> checks;
    private final String metricsPath, readinessPath;

    ObservabilityEndpoints(MetricsRegistry registry, List<ReadinessCheck> checks,
                           String metricsPath, String readinessPath) {
        this.registry = Objects.requireNonNull(registry);
        this.checks = List.copyOf(checks);
        this.metricsPath = path(metricsPath, "game.metrics.path");
        this.readinessPath = path(readinessPath, "game.metrics.readiness-path");
        if (this.metricsPath.equals(this.readinessPath))
            throw new IllegalArgumentException("Metrics and readiness paths must differ: " + this.metricsPath);
    }
    private static String path(String value, String property) {
        if (value == null || !value.startsWith("/") || value.length() < 2 || value.contains("?") || value.contains("#"))
            throw new IllegalArgumentException(property + " must be an absolute path: " + value);
        return value;
    }

    /** Wraps business dispatch so the two observability paths are answered before it is reached. */
    public HttpDispatcher wrap(HttpDispatcher business) {
        Objects.requireNonNull(business);
        return (request, callback) -> {
            String uri = request.uri();
            int query = uri.indexOf('?');
            String requested = query < 0 ? uri : uri.substring(0, query);
            if (!HttpMethod.GET.equals(request.method())) { business.dispatch(request, callback); return; }
            if (requested.equals(metricsPath)) { respond(callback, HttpResponseStatus.OK, metrics()); return; }
            if (requested.equals(readinessPath)) { readiness(callback); return; }
            business.dispatch(request, callback);
        };
    }

    /**
     * Every reading as {@code name value} on its own line, in prefix order. Deliberately not a specific
     * exposition format: an application that needs one implements {@link MetricSink} and maps the names,
     * which is what the Core contract is for.
     */
    String metrics() {
        StringBuilder out = new StringBuilder(1024);
        registry.read(new MetricSink() {
            @Override public void gauge(String name, long value) { out.append(name).append(' ').append(value).append('\n'); }
            @Override public void counter(String name, long value) { out.append(name).append(' ').append(value).append('\n'); }
        });
        return out.toString();
    }

    /** 200 and {@code ready}, or 503 naming every check that is not ready. A throwing check is not ready. */
    void readiness(HttpResponseCallback callback) {
        List<String> failed = new ArrayList<>();
        for (ReadinessCheck check : checks) {
            boolean ready;
            try { ready = check.ready(); }
            catch (RuntimeException error) { ready = false; }
            if (!ready) failed.add(check.name());
        }
        if (failed.isEmpty()) respond(callback, HttpResponseStatus.OK, "ready\n");
        else respond(callback, HttpResponseStatus.SERVICE_UNAVAILABLE, "not ready: " + String.join(", ", failed) + "\n");
    }

    private static void respond(HttpResponseCallback callback, HttpResponseStatus status, String body) {
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(body, CharsetUtil.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        HttpUtil.setContentLength(response, response.content().readableBytes());
        callback.onResponse(response);
    }
}
