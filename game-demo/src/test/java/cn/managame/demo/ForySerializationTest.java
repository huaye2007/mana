package cn.managame.demo;

import cn.managame.demo.common.serialization.ForyConfig;
import cn.managame.demo.common.protocol.GameProtocols;
import cn.managame.demo.common.runtime.GameRuntimeConfig;
import cn.managame.demo.network.GamePacket;
import cn.managame.demo.network.GamePacketHandler;
import cn.managame.demo.network.message.DemoMessage;
import cn.managame.demo.network.message.PingMessage;
import org.apache.fory.ThreadSafeFory;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ForySerializationTest {
    @Test void springReusesTheSerializerAndIndependentPeersReadItsPayloads() {
        try (var context = new AnnotationConfigApplicationContext(
                ForyConfig.class, GameRuntimeConfig.class, GamePacketHandler.class)) {
            ThreadSafeFory writer = context.getBean(ThreadSafeFory.class);
            assertSame(writer, context.getBean(ThreadSafeFory.class));
            assertNotNull(context.getBean(GamePacketHandler.class));
            ThreadSafeFory reader = new ForyConfig().fory(new GameProtocols());
            var expected = new DemoMessage(Long.MAX_VALUE, "你好 Fory 🌍");
            byte[] encoded = writer.serialize(expected);
            assertEquals(expected, reader.deserialize(encoded, DemoMessage.class));
            assertEquals(expected, writer.deserialize(reader.serialize(expected), DemoMessage.class));
        }
    }

    @Test void sharedSerializerKeepsConcurrentVirtualThreadPayloadsSeparate() throws Exception {
        ThreadSafeFory fory = new ForyConfig().fory(new GameProtocols());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<?>>();
            for (int index = 0; index < 128; index++) {
                int id = index;
                results.add(executor.submit(() -> {
                    assertTrue(Thread.currentThread().isVirtual());
                    for (int attempt = 0; attempt < 5; attempt++) {
                        var expected = new DemoMessage(id, "消息-" + id + "-" + attempt);
                        assertEquals(expected, fory.deserialize(fory.serialize(expected), DemoMessage.class));
                    }
                }));
            }
            for (Future<?> result : results) result.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void unregisteredTypesWrongTypesAndMalformedBodiesAreRejected() {
        ThreadSafeFory fory = new ForyConfig().fory(new GameProtocols());
        assertThrows(RuntimeException.class, () -> fory.serialize(new UnregisteredMessage(1)));
        assertThrows(RuntimeException.class,
                () -> DemoMessage.class.cast(fory.deserialize(fory.serialize("wrong type"))));
        assertThrows(RuntimeException.class, () -> fory.deserialize(new byte[]{1, 2, 3}, DemoMessage.class));
        assertThrows(RuntimeException.class, () -> fory.deserialize(new byte[0], DemoMessage.class));
    }

    @Test void handlerRejectsUnknownCommandsBeforeDecodingAndDetectsCommandTypeMismatch() {
        try (var context = new AnnotationConfigApplicationContext(
                ForyConfig.class, GameRuntimeConfig.class, GamePacketHandler.class)) {
            var handler = context.getBean(GamePacketHandler.class);
            var fory = context.getBean(ThreadSafeFory.class);
            var packet = new GamePacket();
            packet.setCommand(-1);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> handler.onMessage(null, packet));
            assertEquals("Unknown request command: -1", failure.getMessage());
            packet.setCommand(1001);
            packet.setBody(fory.serialize(new PingMessage(123L)));
            assertThrows(ClassCastException.class, () -> handler.onMessage(null, packet));
            packet.setCommand(1002);
            packet.setBody(fory.serialize(new DemoMessage(1L, "wrong command")));
            assertThrows(ClassCastException.class, () -> handler.onMessage(null, packet));
        }
    }

    record UnregisteredMessage(long id) {}
}
