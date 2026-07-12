package cn.managame.serialization;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SerializerManagerTest {

    @Test
    void defaultSingletonHasAllBuiltInSerializers() {
        SerializerManager manager = SerializerManager.getInstance();
        for (SerializationType type : SerializationType.values()) {
            ISerializer serializer = manager.getISerializer(type.typeId());
            assertNotNull(serializer, "missing default serializer for " + type);
            assertSame(type, serializer.type());
        }
    }

    @Test
    void unknownSerialTypeResolvesToNull() {
        assertNull(SerializerManager.getInstance().getISerializer((byte) 0));
        assertNull(SerializerManager.getInstance().getISerializer((byte) 99));
    }

    @Test
    void registerOverridesByType() {
        SerializerManager manager = new SerializerManager();
        ISerializer custom = new ISerializer() {
            @Override
            public SerializationType type() {
                return SerializationType.JSON;
            }

            @Override
            public <T> byte[] serialize(T value) {
                return new byte[0];
            }

            @Override
            public <T> T deserialize(byte[] array, Class<T> type) {
                return null;
            }
        };
        manager.register(custom);
        assertSame(custom, manager.getISerializer(SerializationType.JSON.typeId()));
    }

    @Test
    void independentDefaultRegistryDoesNotMutateSingleton() {
        SerializerManager manager = SerializerManager.createDefault();
        ISerializer singletonJson = SerializerManager.getInstance()
            .requireSerializer(SerializationType.JSON);
        ISerializer replacement = stub(SerializationType.JSON);

        manager.register(replacement);

        assertSame(replacement, manager.requireSerializer(SerializationType.JSON));
        assertSame(singletonJson, SerializerManager.getInstance().requireSerializer(SerializationType.JSON));
    }

    @Test
    void requireSerializerFailsClearlyForMissingType() {
        SerializerManager manager = new SerializerManager();

        assertThrows(NoSuchElementException.class,
            () -> manager.requireSerializer(SerializationType.JSON));
        assertThrows(NoSuchElementException.class, () -> manager.requireSerializer((byte) 99));
    }

    @Test
    void registrationAndLookupAreSafeAcrossThreads() throws Exception {
        SerializerManager manager = new SerializerManager();
        ISerializer first = stub(SerializationType.JSON);
        ISerializer second = stub(SerializationType.JSON);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 10_000; i++) {
                    manager.register((i & 1) == 0 ? first : second);
                }
                return null;
            });
            var reader = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 10_000; i++) {
                    ISerializer serializer = manager.getSerializer(SerializationType.JSON);
                    if (serializer != null && serializer != first && serializer != second) {
                        throw new AssertionError("observed partially published serializer");
                    }
                }
                return null;
            });
            start.countDown();
            writer.get(5, TimeUnit.SECONDS);
            reader.get(5, TimeUnit.SECONDS);
        }
        assertSame(second, manager.requireSerializer(SerializationType.JSON));
    }

    private static ISerializer stub(SerializationType type) {
        return new ISerializer() {
            @Override
            public SerializationType type() {
                return type;
            }

            @Override
            public <T> byte[] serialize(T value) {
                return new byte[0];
            }

            @Override
            public <T> T deserialize(byte[] payload, Class<T> valueType) {
                return null;
            }
        };
    }
}
