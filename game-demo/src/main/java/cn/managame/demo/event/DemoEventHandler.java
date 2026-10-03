package cn.managame.demo.event;

import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.event.EventHandler;
import cn.managame.runtime.event.EventMethod;

@EventHandler
public class DemoEventHandler {
    @EventMethod
    public void onDemoEvent(DemoEvent event) {
        var context = Contexts.current();
        System.out.printf("DemoEvent received: routeDomain=%d, routeKey=%d, message=%s, virtualThread=%s%n",
                context.routeDomain(), context.routeKey(), event.message(), Thread.currentThread().isVirtual());
    }
}
