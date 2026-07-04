package cn.managame.runtime.context;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameTaskContextHolderTest {

    private static GameTaskContext newContext(GameTaskType type) {
        return new GameTaskContext(type, (byte) 1, 100L, (byte) 1, 1L, null);
    }

    @Test
    void currentReturnsNullWhenUnbound() {
        assertNull(GameTaskContextHolder.current());
    }

    @Test
    void platformThreadBindingRestoresPreviousContext() {
        GameTaskContext outer = newContext(GameTaskType.EVENT);
        GameTaskContext inner = newContext(GameTaskType.CALLBACK);

        GameTaskContextHolder.runWith(outer, () -> {
            assertSame(outer, GameTaskContextHolder.current());
            GameTaskContextHolder.runWith(inner, () ->
                    assertSame(inner, GameTaskContextHolder.current()));
            assertSame(outer, GameTaskContextHolder.current());
        });
        assertNull(GameTaskContextHolder.current());
    }

    @Test
    void platformThreadUnbindsAfterRunnableThrows() {
        GameTaskContext context = newContext(GameTaskType.TIMER);
        try {
            GameTaskContextHolder.runWith(context, () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
        }
        assertNull(GameTaskContextHolder.current());
    }

    @Test
    void virtualThreadBindsViaScopedValue() throws Exception {
        GameTaskContext context = newContext(GameTaskType.EVENT);
        AtomicReference<GameTaskContext> seen = new AtomicReference<>();
        AtomicReference<GameTaskContext> afterScope = new AtomicReference<>();

        Thread vt = Thread.ofVirtual().start(() -> {
            assertTrue(Thread.currentThread().isVirtual());
            GameTaskContextHolder.runWith(context, () -> seen.set(GameTaskContextHolder.current()));
            afterScope.set(GameTaskContextHolder.current());
        });
        vt.join();

        assertSame(context, seen.get());
        assertNull(afterScope.get());
    }
}
