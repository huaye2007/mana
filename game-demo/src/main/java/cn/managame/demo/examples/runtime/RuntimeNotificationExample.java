package cn.managame.demo.examples.runtime;

import cn.managame.runtime.GameRuntimeBuilder;
import cn.managame.runtime.context.DefaultHandlerContext;
import cn.managame.runtime.executor.RouteExecuteStatus;
import cn.managame.runtime.executor.RouteExecutor;
import cn.managame.runtime.executor.RouteExecutorBinding;
import cn.managame.runtime.handler.Handler;
import cn.managame.runtime.handler.HandlerMethod;
import cn.managame.runtime.protocol.ProtocolDescriptor;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.RouteDomain;

import java.util.List;

/** Runs a one-way notification through Runtime's registered message handler. */
public final class RuntimeNotificationExample {
    private RuntimeNotificationExample() {}

    public static void main(String[] args) {
        RouteExecutor executor = (domain, key, task) -> {
            task.run();
            return RouteExecuteStatus.ACCEPTED;
        };
        var notification = new Notification("ready");

        try (var runtime = GameRuntimeBuilder.builder()
                .routeDomains(List.of(RouteDomain.of(1, "application-defined")))
                .routeExecutors(List.of(RouteExecutorBinding.of(executor, 1)))
                .protocols(ProtocolRegistry.of(ProtocolDescriptor.notify(1002, Notification.class)))
                .handlers(List.of(new Notifications()))
                .build()) {
            runtime.dispatch(new DefaultHandlerContext(1, 10001, notification));
        }
    }

    public record Notification(String message) {}

    @Handler(domain = 1)
    public static final class Notifications {
        @HandlerMethod
        public void handle(Notification notification) {
            System.out.println("Received notification: " + notification.message());
        }
    }
}
