package cn.managame.runtime.invoke;

import cn.managame.runtime.context.GameTaskContext;
import cn.managame.runtime.context.GameTaskType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InvokersTest {

    public static class Message {
        public long longKey() {
            return 11L;
        }

        public int intKey() {
            return 12;
        }

        public Long boxedLongKey() {
            return 13L;
        }

        public Integer boxedIntKey() {
            return 14;
        }
    }

    public static class Handler {
        final AtomicReference<Object> seenEvent = new AtomicReference<>();
        final AtomicReference<Object> seenMsg = new AtomicReference<>();
        final AtomicReference<GameTaskContext> seenCtx = new AtomicReference<>();

        public void onEvent(Message event) {
            seenEvent.set(event);
        }

        public void onCommand(GameTaskContext ctx, Message msg) {
            seenCtx.set(ctx);
            seenMsg.set(msg);
        }

        public void boom(Message event) {
            throw new IllegalStateException("boom");
        }

        void packagePrivate(Message event) {
        }
    }

    @Test
    void eventInvokerCallsTargetMethod() throws Exception {
        Handler handler = new Handler();
        Message event = new Message();
        Invokers.eventInvoker(Handler.class.getMethod("onEvent", Message.class))
                .invoke(handler, event);
        assertSame(event, handler.seenEvent.get());
    }

    @Test
    void commandInvokerCallsTargetMethod() throws Exception {
        Handler handler = new Handler();
        Message msg = new Message();
        GameTaskContext ctx = new GameTaskContext(GameTaskType.COMMAND, (byte) 1, 1L, (byte) 0, 0L, null);
        Invokers.commandInvoker(Handler.class.getMethod("onCommand", GameTaskContext.class, Message.class))
                .invoke(handler, ctx, msg);
        assertSame(ctx, handler.seenCtx.get());
        assertSame(msg, handler.seenMsg.get());
    }

    @Test
    void routerKeyExtractorAdaptsAllNumericReturnTypes() throws Exception {
        Message msg = new Message();
        assertEquals(11L, Invokers.routerKeyExtractor(Message.class.getMethod("longKey")).extract(msg));
        assertEquals(12L, Invokers.routerKeyExtractor(Message.class.getMethod("intKey")).extract(msg));
        assertEquals(13L, Invokers.routerKeyExtractor(Message.class.getMethod("boxedLongKey")).extract(msg));
        assertEquals(14L, Invokers.routerKeyExtractor(Message.class.getMethod("boxedIntKey")).extract(msg));
    }

    @Test
    void invokerThrowsOriginalBusinessException() throws Exception {
        Invokers.EventInvoker invoker = Invokers.eventInvoker(Handler.class.getMethod("boom", Message.class));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> invoker.invoke(new Handler(), new Message()));
        assertEquals("boom", e.getMessage(), "调用器应直接抛业务原始异常，不包 InvocationTargetException");
    }

    @Test
    void nonPublicMethodFailsFastAtRegistration() throws Exception {
        var method = Handler.class.getDeclaredMethod("packagePrivate", Message.class);
        assertThrows(IllegalStateException.class, () -> Invokers.requireInvokable(method));
        assertThrows(IllegalStateException.class, () -> Invokers.eventInvoker(method));
    }
}
