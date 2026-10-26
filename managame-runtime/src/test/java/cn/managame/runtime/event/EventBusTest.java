package cn.managame.runtime.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.Metadata;
import cn.managame.MetadataKey;
import cn.managame.runtime.Context;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.annotation.Domain;
import cn.managame.runtime.annotation.EventMethod;
import cn.managame.runtime.annotation.RouteKey;
import cn.managame.runtime.dispatch.HandleContext;
import cn.managame.runtime.executor.RouteExecutors;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EventBusTest {
    private static final int PLAYER = 1, GUILD = 2, ROOM = 3;
    private static final byte ROLE = 1;
    private static final MetadataKey<String> TRACE = MetadataKey.stringKey(1);

    record LevelUp(@RouteKey(PLAYER) long playerId, @RouteKey(GUILD) long guildId, int level) {}
    record Unheard() {}

    private final GameRuntime runtime = GameRuntime.builder()
            .domain(PLAYER, RouteExecutors.virtualThreads())
            .domain(GUILD, RouteExecutors.virtualThreads())
            .domain(ROOM, RouteExecutors.virtualThreads()).build();
    private final BlockingQueue<String> seen = new LinkedBlockingQueue<>();

    @AfterEach void stop() { runtime.close(); }

    @Domain(PLAYER)
    class Quests {
        @EventMethod void onLevelUp(LevelUp event) {
            Context context = Context.current();
            seen.add("player " + context.routingKey() + " level " + event.level() + " by " + context.businessId()
                    + " " + context.metadata().get(TRACE));
        }
    }

    @Domain(GUILD)
    class GuildLog {
        @EventMethod void onLevelUp(GameContext context, LevelUp event) {
            seen.add("guild " + context.routingKey() + " " + context.metadata().get(TRACE));
        }
    }

    @Test void everySubscriberRunsOnItsDomainsRouteActingForThePublisher() throws Exception {
        EventBus events = EventBus.builder(runtime).register(new Quests(), new GuildLog()).build();
        Metadata traced = Metadata.builder().put(TRACE, "t1").build();
        runtime.execute(new GameContext(PLAYER, 1001, ROLE, 1001, traced), context -> events.publish(new LevelUp(1001, 7, 10)));

        List<String> received = List.of(seen.poll(5, TimeUnit.SECONDS), seen.poll(5, TimeUnit.SECONDS));
        assertTrue(received.contains("player 1001 level 10 by 1001 t1"), received.toString());
        assertTrue(received.contains("guild 7 t1"), received.toString());
    }

    @Test void subscribersRunAfterThePublishingTaskInPublishOrder() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(3);
        @Domain(PLAYER) class Recorder {
            @EventMethod void on(LevelUp event) {
                order.add("event " + event.level());
                done.countDown();
            }
        }
        EventBus events = EventBus.builder(runtime).register(new Recorder()).build();
        runtime.execute(GameContext.of(PLAYER, 1001), context -> {
            order.add("task start");
            for (int level = 1; level <= 3; level++) events.publish(new LevelUp(1001, 0, level));
            order.add("task end"); // the subscriber shares this route, yet nothing ran inside the task
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("task start", "task end", "event 1", "event 2", "event 3"), order);
    }

    @Test void publishingOutsideATaskActsForNobodyAndUnheardEventsAreIgnored() throws Exception {
        EventBus events = EventBus.builder(runtime).register(new Quests()).build();
        events.publish(new Unheard());
        events.publish(new LevelUp(5, 0, 1));
        assertEquals("player 5 level 1 by 0 null", seen.poll(5, TimeUnit.SECONDS));
    }

    @Test void aSubscriberThatCannotBeRoutedDoesNotStopTheOthers() throws Exception {
        @Domain(ROOM) class RoomLog { // LevelUp has no @RouteKey for ROOM
            @EventMethod void on(LevelUp event) { seen.add("room"); }
        }
        EventBus events = EventBus.builder(runtime).register(new RoomLog(), new Quests()).build();
        assertThrows(IllegalArgumentException.class, () -> events.publish(new LevelUp(5, 0, 1)));
        assertTrue(seen.poll(5, TimeUnit.SECONDS).startsWith("player 5"));
        assertNull(seen.poll(100, TimeUnit.MILLISECONDS));
    }

    @Test void subscribersReceiveOnlyTheirExactClassAndTakeOnlyAGameContext() throws Exception {
        @Domain(PLAYER) class Everything { @EventMethod void on(Object event) { seen.add("object"); } }
        EventBus.builder(runtime).register(new Everything()).build().publish(new LevelUp(1, 0, 1));
        assertNull(seen.poll(100, TimeUnit.MILLISECONDS));

        @Domain(PLAYER) class Misdeclared { @EventMethod void on(HandleContext context, LevelUp event) {} }
        assertThrows(IllegalArgumentException.class, () -> EventBus.builder(runtime).register(new Misdeclared()));
    }
}
