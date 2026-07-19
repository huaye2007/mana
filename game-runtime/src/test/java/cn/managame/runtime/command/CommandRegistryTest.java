package cn.managame.runtime.command;

import cn.managame.runtime.annotation.GameController;
import cn.managame.runtime.annotation.GameMethod;
import cn.managame.runtime.runnable.GameCommandTaskRunnable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandRegistryTest {

    public static class Message {
        private final long key;
        private final boolean fail;

        Message(long key, boolean fail) {
            this.key = key;
            this.fail = fail;
        }

        public long key() {
            if (fail) {
                throw new IllegalStateException("bad key");
            }
            return key;
        }
    }

    @GameController(group = 9)
    public static class ValidController {
        final AtomicLong boxedBusId = new AtomicLong();
        final AtomicLong primitiveBusId = new AtomicLong();

        @GameMethod(value = 100, routerKeyMethod = "key")
        public void boxed(Long busId, Message message) {
            boxedBusId.set(busId);
        }

        @GameMethod(101)
        public void primitive(long busId, Message message) {
            primitiveBusId.set(busId);
        }
    }

    @GameController(group = 9)
    public static class PartiallyInvalidController {
        @GameMethod(200)
        public void valid(Long busId, Message message) {
        }

        @GameMethod(201)
        public void invalid(int unsupportedPrimitive, Message message) {
        }
    }

    @Test
    void longAndBoxedLongReceiveBusIdAndRouterFailuresReject() {
        CommandRegistry registry = new CommandRegistry();
        ValidController controller = new ValidController();
        registry.register(controller);

        CommandMeta boxed = registry.getCommandMeta(100);
        CommandMeta primitive = registry.getCommandMeta(101);
        assertEquals(7L, boxed.extractRouterKey(new Message(7L, false), 99L));
        assertThrows(IllegalArgumentException.class,
                () -> boxed.extractRouterKey(new Message(7L, true), 99L));

        new GameCommandTaskRunnable(boxed, 7L, (byte) 0, 42L,
                1, null, new Message(7L, false), new Object()).run();
        new GameCommandTaskRunnable(primitive, 8L, (byte) 0, 43L,
                2, null, new Message(8L, false), new Object()).run();
        assertEquals(42L, controller.boxedBusId.get());
        assertEquals(43L, controller.primitiveBusId.get());

        Class<?>[] exposed = boxed.getParamTypes();
        exposed[0] = String.class;
        assertEquals(Long.class, boxed.getParamTypes()[0]);
    }

    @Test
    void controllerRegistrationIsAtomicAndFreezeIsEnforced() {
        CommandRegistry registry = new CommandRegistry();
        assertThrows(IllegalStateException.class,
                () -> registry.register(new PartiallyInvalidController()));
        assertNull(registry.getCommandMeta(200));
        assertNull(registry.getCommandMeta(201));

        registry.freeze();
        assertThrows(IllegalStateException.class, () -> registry.register(new ValidController()));
    }
}
