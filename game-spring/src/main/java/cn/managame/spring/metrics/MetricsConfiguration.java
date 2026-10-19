package cn.managame.spring.metrics;

import cn.managame.boot.metrics.FrameworkMetrics;
import cn.managame.boot.metrics.ObservabilityEndpoints;
import cn.managame.boot.metrics.ReadinessCheck;
import cn.managame.core.metrics.MetricsRegistry;
import cn.managame.data.GameData;
import cn.managame.network.netty.NetworkServer;
import cn.managame.rpc.node.RpcNode;
import cn.managame.runtime.GameRuntime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Publishes a {@link MetricsSource} for every managed component that is present and serves the two
 * observability paths. Each component is optional: a Node that runs Runtime without Data, or RPC without a
 * client-facing listener, publishes only what it has.
 *
 * <p>Prefixes are fixed so a dashboard built against one deployment reads another: {@code runtime},
 * {@code data}, {@code rpc} and {@code network}. A NetworkServer the application created itself and did not
 * expose as a bean is its own to publish, with {@code registry.publish(prefix, source)}.
 */
@Configuration(proxyBeanMethods = false)
class MetricsConfiguration {
    @Bean
    MetricsRegistry gameMetricsRegistry(ObjectProvider<GameRuntime> runtime, ObjectProvider<GameData> data,
                                        ObjectProvider<RpcNode> rpc, ObjectProvider<NetworkServer> network) {
        MetricsRegistry registry = new MetricsRegistry();
        GameRuntime r = runtime.getIfAvailable();
        if (r != null) registry.publish("runtime", FrameworkMetrics.of(r));
        GameData d = data.getIfAvailable();
        if (d != null) registry.publish("data", FrameworkMetrics.of(d));
        RpcNode n = rpc.getIfAvailable();
        if (n != null) registry.publish("rpc", FrameworkMetrics.of(n));
        NetworkServer s = network.getIfAvailable();
        if (s != null) registry.publish("network", FrameworkMetrics.of(s));
        return registry;
    }

    /**
     * The framework's own readiness checks, plus whatever the application contributes. Only conditions a
     * component reports about itself are built in: Runtime still admitting work and Data still accepting
     * changes. Whether an instance is ready to serve players also depends on policy the framework does not
     * own — attachment to a Router, a loaded world — which an application adds as its own ReadinessCheck.
     */
    @Bean
    ObservabilityEndpoints gameObservabilityEndpoints(MetricsRegistry gameMetricsRegistry, Environment environment,
                                                      ObjectProvider<GameRuntime> runtime,
                                                      ObjectProvider<GameData> data,
                                                      ObjectProvider<ReadinessCheck> extra) {
        // Contributed conditions come first, in Spring order, so the Runtime integration's own
        // shutdown.rotation (declared at HIGHEST_PRECEDENCE) leads: it is the one condition that turns
        // false while the instance is still serving normally, which is the window a load balancer needs
        // to stop sending before admission closes. The two below are derived here instead of contributed,
        // because they are just a component reporting on itself.
        List<ReadinessCheck> checks = new ArrayList<>(extra.orderedStream().toList());
        GameRuntime r = runtime.getIfAvailable();
        if (r != null) checks.add(check("runtime.accepting", () -> r.stats().accepting()));
        GameData d = data.getIfAvailable();
        if (d != null) checks.add(check("data.accepting", () -> d.stats().accepting()));
        return new ObservabilityEndpoints(gameMetricsRegistry, checks,
                environment.getProperty("game.metrics.path", "/metrics"),
                environment.getProperty("game.metrics.readiness-path", "/readiness"));
    }

    private static ReadinessCheck check(String name, java.util.function.BooleanSupplier ready) {
        return new ReadinessCheck() {
            @Override public String name() { return name; }
            @Override public boolean ready() { return ready.getAsBoolean(); }
        };
    }
}
