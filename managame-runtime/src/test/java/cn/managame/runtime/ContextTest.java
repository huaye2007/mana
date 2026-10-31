package cn.managame.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

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
    private static final Metadata TRACED = Metadata.builder().put(TRACE, "trace-42").build();

    /** A request from some source; a copy must not carry its ability to answer. */
    private record Request(int domain, long routeKey, byte businessType, long businessId, Metadata metadata)
            implements RequestContext {
        @Override public boolean reply(Object result) { return true; }
        @Override public boolean fail(int errorCode) { return true; }
    }

    @Test void copyOfKeepsEverythingButTheAbilityToAnswer() {
        Request request = new Request(PLAYER, 1001, PLAYER_ID, 1001, TRACED);
        GameContext copy = GameContext.copyOf(request);
        assertEquals(new GameContext(PLAYER, 1001, PLAYER_ID, 1001, TRACED), copy);
        assertSame(TRACED, copy.metadata());
        assertFalse(((Object) copy) instanceof RequestContext);
        assertSame(copy, GameContext.copyOf(copy), "a GameContext is already a plain immutable context");
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
