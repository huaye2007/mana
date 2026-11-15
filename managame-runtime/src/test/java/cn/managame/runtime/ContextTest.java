package cn.managame.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.managame.Metadata;
import cn.managame.MetadataKey;
import cn.managame.runtime.executor.RouteExecutors;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ContextTest {
    private static final int PLAYER = 1, GUILD = 2;
    private static final byte PLAYER_ID = 1, GUILD_ID = 2;
    private static final MetadataKey<String> TRACE = MetadataKey.stringKey(1);
    private static final MetadataKey<Long> SERVER = MetadataKey.longKey(2);
    private static final Metadata TRACED = Metadata.builder().put(TRACE, "trace-42").build();

    /**
     * A request from some source, as an application writes its own: it keeps whom it is for and its metadata in a
     * GameContext, so what is put in it goes there. A copy must not carry its ability to answer.
     */
    private record Request(GameContext subject) implements RequestContext {
        Request(int domain, long routeKey, byte businessType, long businessId, Metadata metadata) {
            this(new GameContext(domain, routeKey, businessType, businessId, metadata));
        }

        @Override public int domain() { return subject.domain(); }
        @Override public long routeKey() { return subject.routeKey(); }
        @Override public byte businessType() { return subject.businessType(); }
        @Override public long businessId() { return subject.businessId(); }
        @Override public Metadata metadata() { return subject.metadata(); }
        @Override public <T> void put(MetadataKey<T> key, T value) { subject.put(key, value); }
        @Override public boolean reply(Object result) { return true; }
        @Override public boolean fail(int errorCode) { return true; }
    }

    @Test void copyOfKeepsEverythingButTheAbilityToAnswer() {
        Request request = new Request(PLAYER, 1001, PLAYER_ID, 1001, TRACED);
        GameContext copy = GameContext.copyOf(request);
        assertEquals(new GameContext(PLAYER, 1001, PLAYER_ID, 1001, TRACED), copy);
        assertSame(TRACED, copy.metadata());
        assertFalse(((Object) copy) instanceof RequestContext);
        GameContext again = GameContext.copyOf(copy);
        assertEquals(copy, again);
        assertNotSame(copy, again, "a copy of a GameContext is a context of its own too");
    }

    @Test void whatIsPutShowsInThatContextOnly() {
        GameContext context = new GameContext(PLAYER, 1001, PLAYER_ID, 1001, TRACED);
        GameContext before = GameContext.copyOf(context);
        context.put(SERVER, 7L);
        context.put(TRACE, "trace-43");
        assertEquals(7L, context.metadata().get(SERVER));
        assertEquals("trace-43", context.metadata().get(TRACE), "a put replaces the value");
        assertNull(before.metadata().get(SERVER), "a copy made before keeps the metadata it took");
        assertEquals("trace-42", before.metadata().get(TRACE));
        assertEquals("trace-42", TRACED.get(TRACE), "and the metadata itself never changes");
        assertNotEquals(before, context);
        assertEquals(before.hashCode(), context.hashCode(), "a context stays where a hash put it");
        assertEquals("trace-43", GameContext.copyOf(context).metadata().get(TRACE), "a copy made afterwards has it");

        GameContext empty = GameContext.of(PLAYER, 1001);
        empty.put(TRACE, "t1");
        assertEquals("t1", empty.metadata().get(TRACE));
        assertTrue(GameContext.of(PLAYER, 1002).metadata().isEmpty(), "the empty metadata others share stays empty");
    }

    @Test void anApplicationsOwnContextTakesWhatIsPutToo() {
        Request request = new Request(PLAYER, 1001, PLAYER_ID, 1001, TRACED);
        request.put(SERVER, 7L);
        assertEquals(7L, request.metadata().get(SERVER));
        assertEquals(7L, GameContext.copyOf(request).metadata().get(SERVER));
    }

    @Test void metadataFollowsWorkHandedToAnotherRoute() throws Exception {
        BlockingQueue<Object> seen = new LinkedBlockingQueue<>();
        try (GameRuntime runtime = GameRuntime.builder()
                .domain(PLAYER, RouteExecutors.virtualThreads())
                .domain(GUILD, RouteExecutors.virtualThreads()).build()) {
            runtime.execute(new GameContext(PLAYER, 1001, PLAYER_ID, 1001, TRACED), player ->
                    runtime.execute(new GameContext(GUILD, 7, GUILD_ID, 7, player.metadata()), guild ->
                            seen.add(guild.domain() + ":" + guild.metadata().get(TRACE))));
            assertEquals(GUILD + ":trace-42", seen.poll(5, TimeUnit.SECONDS));
        }
    }
}
