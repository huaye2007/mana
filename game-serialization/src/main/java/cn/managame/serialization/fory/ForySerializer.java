package cn.managame.serialization.fory;

import cn.managame.serialization.ISerializer;
import cn.managame.serialization.SerializationException;
import cn.managame.serialization.SerializationType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;

/** Serializer backed by Apache Fory, with secure class registration enabled by default. */
public final class ForySerializer implements ISerializer {

    private final ThreadSafeFory fory;

    public static ForySerializer create() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public ForySerializer(ThreadSafeFory fory) {
        this.fory = Objects.requireNonNull(fory, "fory");
    }

    @Override
    public SerializationType type() {
        return SerializationType.FORY;
    }

    @Override
    public <T> byte[] serialize(T value) {
        Objects.requireNonNull(value, "value");
        try {
            return fory.serialize(value);
        } catch (RuntimeException e) {
            throw new SerializationException("Failed to serialize Fory value of type "
                + value.getClass().getName(), e);
        }
    }

    @Override
    public <T> T deserialize(byte[] payload, Class<T> type) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(type, "type");
        try {
            return fory.deserialize(payload, type);
        } catch (RuntimeException e) {
            throw new SerializationException("Failed to deserialize Fory payload as "
                + type.getName(), e);
        }
    }

    /** Registers a class before this serializer starts handling traffic. */
    public ForySerializer register(Class<?> type) {
        fory.register(Objects.requireNonNull(type, "type"));
        return this;
    }

    /** Registers a class with a stable positive id. */
    public ForySerializer register(Class<?> type, int typeId) {
        validateTypeId(typeId);
        fory.register(Objects.requireNonNull(type, "type"), typeId);
        return this;
    }

    public ThreadSafeFory unwrap() {
        return fory;
    }

    public static final class Builder {

        private final List<ClassRegistration> registrations = new ArrayList<>();
        private boolean requireClassRegistration = true;
        private boolean refTracking = true;
        private boolean asyncCompilation = true;
        private ClassLoader classLoader;

        private Builder() {
        }

        public Builder requireClassRegistration(boolean requireClassRegistration) {
            this.requireClassRegistration = requireClassRegistration;
            return this;
        }

        public Builder refTracking(boolean refTracking) {
            this.refTracking = refTracking;
            return this;
        }

        public Builder asyncCompilation(boolean asyncCompilation) {
            this.asyncCompilation = asyncCompilation;
            return this;
        }

        public Builder classLoader(ClassLoader classLoader) {
            this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
            return this;
        }

        public Builder register(Class<?> type) {
            registrations.add(new ClassRegistration(Objects.requireNonNull(type, "type"), null));
            return this;
        }

        public Builder register(Class<?> type, int typeId) {
            validateTypeId(typeId);
            registrations.add(new ClassRegistration(Objects.requireNonNull(type, "type"), typeId));
            return this;
        }

        public ForySerializer build() {
            var foryBuilder = Fory.builder()
                .withXlang(false)
                .requireClassRegistration(requireClassRegistration)
                .withRefTracking(refTracking)
                .withAsyncCompilation(asyncCompilation);
            if (classLoader != null) {
                foryBuilder.withClassLoader(classLoader);
            }

            ThreadSafeFory runtime = foryBuilder.buildThreadSafeFory();
            for (ClassRegistration registration : registrations) {
                if (registration.typeId() == null) {
                    runtime.register(registration.type());
                } else {
                    runtime.register(registration.type(), registration.typeId());
                }
            }
            return new ForySerializer(runtime);
        }
    }

    private static void validateTypeId(int typeId) {
        if (typeId <= 0) {
            throw new IllegalArgumentException("typeId must be positive");
        }
    }

    private record ClassRegistration(Class<?> type, Integer typeId) {
    }
}
