package cn.managame.demo;

import cn.managame.demo.common.runtime.GameRuntimeConfig;
import cn.managame.demo.event.DemoEvent;
import cn.managame.demo.event.DemoEventHandler;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.context.EventContext;
import cn.managame.runtime.error.RuntimeDispatchException;
import cn.managame.runtime.event.EventHandler;
import cn.managame.runtime.event.EventMethod;
import cn.managame.runtime.event.Events;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Profile;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DemoEventTest {
    @Test void springRegistersListenersAndPublishesTheDemoEventOnItsRoute() throws Exception {
        GameRuntime runtime;
        var event = new DemoEvent(1001L, "hello game-runtime");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("demo-event-test");
            context.register(GameRuntimeConfig.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(DemoEventHandler.class).size());
            runtime = context.getBean(GameRuntime.class);
            Events.publish(event);
            Received received = context.getBean(Probe.class).events.poll(5, TimeUnit.SECONDS);
            assertNotNull(received, "DemoEvent was not handled");
            assertSame(event, received.event());
            assertSame(event, received.context().event());
            assertEquals(DemoEvent.ROUTE_DOMAIN, received.context().routeDomain());
            assertEquals(1001L, received.context().routeKey());
            assertTrue(received.virtualThread());
        }
        assertThrows(RuntimeDispatchException.class, () -> runtime.eventBus().publish(event));
        assertThrows(IllegalStateException.class, () -> Events.publish(event));
    }

    record Received(DemoEvent event, EventContext context, boolean virtualThread) {}

    @EventHandler
    @Profile("demo-event-test")
    static class Probe {
        final LinkedBlockingQueue<Received> events = new LinkedBlockingQueue<>();
        @EventMethod(order = 10)
        public void onDemoEvent(DemoEvent event) {
            events.add(new Received(event, (EventContext) Contexts.current(), Thread.currentThread().isVirtual()));
        }
    }
}
