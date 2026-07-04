package cn.managame.serialization.fory;

import cn.managame.serialization.ISerializer;
import cn.managame.serialization.SerializationException;
import cn.managame.serialization.SerializationType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;

/**
 * Serializer backed by Apache Fory (formerly Apache Fury).
 *
 * <p>默认 {@code requireClassRegistration=true}：不可信连接（如外网包体）反序列化时只能实例化
 * 已登记的类型，挡住任意类反序列化这一攻击面。代价是所有要走 Fory 的业务类型必须在收发流量前
 * 通过 {@link #register} 或 {@link Builder#register} 登记，否则序列化/反序列化抛异常。</p>
 *
 * <p>默认 {@code refTracking=true}：支持共享/循环引用的对象图（游戏状态快照常见），避免重复展开
 * 或栈溢出；少量开销换取正确性。仅在确认无共享引用、追求极致吞吐时关掉。</p>
 */
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
        return fory.serialize(value);
    }

    @Override
    public <T> T deserialize(byte[] payload, Class<T> type) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(type, "type");
        try {
            return fory.deserialize(payload, type);
        } catch (RuntimeException e) {
            throw new SerializationException("Failed to deserialize Fory payload as " + type.getName(), e);
        }
    }

    /**
     * 启动期把业务类型登记进底层 Fory（默认要求类注册，未登记的类型无法收发）。
     * 仅供宿主在开始服务流量前单线程调用；{@code typeId} 自动分配，按登记顺序递增。
     *
     * <p>Fory 在首次 {@code serialize/deserialize} 之后会冻结注册，此后再调用本方法会抛异常——
     * 务必在任何收发之前完成所有登记。</p>
     */
    public ForySerializer register(Class<?> type) {
        fory.register(Objects.requireNonNull(type, "type"));
        return this;
    }

    /** 同 {@link #register(Class)}，但指定稳定的正整数 {@code typeId}（更小的载荷、跨进程稳定）。 */
    public ForySerializer register(Class<?> type, int typeId) {
        if (typeId <= 0) {
            throw new IllegalArgumentException("typeId must be positive");
        }
        fory.register(Objects.requireNonNull(type, "type"), typeId);
        return this;
    }

    public ThreadSafeFory unwrap() {
        return fory;
    }

    public static final class Builder {

        private final List<ClassRegistration> registrations = new ArrayList<>();
        // 默认安全：不可信边界只能反序列化已登记类型，挡住任意类反序列化攻击面。
        private boolean requireClassRegistration = true;
        // 默认开启引用追踪：正确处理共享/循环引用的对象图（游戏状态快照场景）。
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
            if (typeId <= 0) {
                throw new IllegalArgumentException("typeId must be positive");
            }
            registrations.add(new ClassRegistration(Objects.requireNonNull(type, "type"), typeId));
            return this;
        }

        public ForySerializer build() {
            var builder = Fory.builder()
                .withXlang(false)
                .requireClassRegistration(requireClassRegistration)
                .withRefTracking(refTracking)
                .withAsyncCompilation(asyncCompilation);
            if (classLoader != null) {
                builder.withClassLoader(classLoader);
            }

            ThreadSafeFory fory = builder.buildThreadSafeFory();
            for (ClassRegistration registration : registrations) {
                if (registration.typeId == null) {
                    fory.register(registration.type);
                } else {
                    fory.register(registration.type, registration.typeId);
                }
            }
            return new ForySerializer(fory);
        }
    }

    private record ClassRegistration(Class<?> type, Integer typeId) {
    }
}
