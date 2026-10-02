package cn.managame.runtime;

import cn.managame.runtime.context.HandlerContext;
import cn.managame.runtime.event.EventBus;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.route.RouteCallback;
import cn.managame.runtime.route.RouteKeyRegistry;
import cn.managame.runtime.timer.CronScheduler;
import cn.managame.runtime.timer.RuntimeTimer;
import cn.managame.runtime.http.HttpDispatcher;

import java.util.function.Supplier;
public interface GameRuntime extends AutoCloseable {
    void dispatch(HandlerContext context);
    <T> void call(int routeDomain, long routeKey, Supplier<T> action, RouteCallback<T> callback);
    EventBus eventBus();
    RuntimeTimer timer();
    CronScheduler cron();
    ProtocolRegistry protocols();
    RouteKeyRegistry routeKeys();
    HttpDispatcher http();
    void close();
}
