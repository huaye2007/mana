package cn.managame.runtime.execution;

import cn.managame.runtime.annotation.EventHandler;
import cn.managame.runtime.annotation.EventMethod;
import cn.managame.runtime.annotation.Handler;
import cn.managame.runtime.annotation.HandlerMethod;
import cn.managame.runtime.clock.MutableGameClock;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.protocol.ProtocolType;
import cn.managame.runtime.route.RouteKeyResolver;
import cn.managame.runtime.route.RouteType;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Run main with JDK 25; no network or database is required. */
public final class RuntimeExample {
    public interface Player extends RouteType {}
    public record RoleId(long value) {}
    public record Session(RoleId roleId) {}
    public record UseItemReq(int itemId) {}
    public record UseItemRes(boolean used) {}
    public record ItemUsed(long roleId, int itemId) {}

    public static final class ItemEventRoute implements RouteKeyResolver<ItemUsed> {
        @Override public long resolve(ItemUsed event) { return event.roleId(); }
    }

    @Handler(routeType = Player.class)
    public static final class Items {
        private GameRuntime runtime;
        private RuntimeCallback<String> callback;

        @HandlerMethod
        public void use(UseItemReq request, RoleId roleId) {
            System.out.println("Command: " + HandlerContexts.current().route());
            runtime.publish(new ItemUsed(roleId.value(), request.itemId()));
            callback = runtime.callback(body -> System.out.println("Decoded callback body: " + body));
            runtime.schedule(Player.class, roleId.value(), Duration.ofSeconds(5),
                    () -> System.out.println("Timer: " + HandlerContexts.current().route()));
            // Send UseItemRes explicitly through the application's protocol component.
        }
    }

    @EventHandler(routeType = Player.class, routeKeyResolver = ItemEventRoute.class)
    public static final class ItemEvents {
        @EventMethod
        public void onUsed(ItemUsed event) {
            System.out.println("Inline event: item " + event.itemId());
        }
    }

    public static void main(String[] args) {
        MutableGameClock clock = new MutableGameClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        ProtocolRegistry protocols = ProtocolRegistry.builder()
                .register(1001, ProtocolType.REQUEST, UseItemReq.class)
                .register(1001, ProtocolType.RESPONSE, UseItemRes.class)
                .response(UseItemReq.class, UseItemRes.class)
                .build();
        ParameterResolverRegistry parameters = ParameterResolverRegistry.builder()
                .registerRouteSource(RoleId.class, invocation -> ((Session) invocation.connection()).roleId())
                .build();
        Items items = new Items();
        try (GameRuntime runtime = GameRuntime.builder()
                .executionDomain(Player.class, ExecutionDomain.platform("gameplay").threads(2).build())
                .protocols(protocols).parameters(parameters)
                .defaultRoute(Player.class, RoleId.class, RoleId::value)
                .eventType(ItemUsed.class).routeKeyResolver(ItemUsed.class, new ItemEventRoute())
                .handler(items).handler(new ItemEvents())
                .clock(clock).automaticScheduling(false).build()) {
            items.runtime = runtime;
            runtime.command(new UseItemReq(7), new Session(new RoleId(10001))).join();
            // A transport adapter supplies an owned, decoded response object.
            items.callback.onSuccess("inventory updated");
            clock.advance(Duration.ofSeconds(5));
            runtime.runDueTimers();
            runtime.dispatch(Player.class, 10001, () -> {}).join();
        }
    }
}
