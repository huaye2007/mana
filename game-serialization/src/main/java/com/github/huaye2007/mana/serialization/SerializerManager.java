package com.github.huaye2007.mana.serialization;

import com.github.huaye2007.mana.serialization.fury.FurySerializer;
import com.github.huaye2007.mana.serialization.json.JacksonJsonSerializer;
import com.github.huaye2007.mana.serialization.protobuf.ProtobufSerializer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 序列化器注册表。{@link #getInstance()} 返回进程级共享单例，已预注册 JSON / Protobuf / Fury
 * 三种默认序列化方式；宿主可在启动时通过 {@link #register} 追加或按 serialType 覆盖默认实现。
 * 使用 {@link ConcurrentHashMap} 以支持共享单例上的并发注册/读取。
 *
 * <p>注意：默认 Fury 实例 {@code requireClassRegistration=true}（安全默认），宿主必须在开始服务
 * 流量前把所有走 Fury 的业务类型登记进去，例如：
 * <pre>{@code
 * if (getInstance().getISerializer(SerializationType.FURY.typeId()) instanceof FurySerializer fury) {
 *     fury.register(LoginReq.class);
 * }
 * }</pre>
 */
public class SerializerManager {

    private static final SerializerManager INSTANCE = createDefault();

    private final Map<SerializationType, ISerializer> serializerMap = new ConcurrentHashMap<>();

    /** 进程级共享单例，已预注册 JSON / Protobuf / Fury 三种默认序列化方式。 */
    public static SerializerManager getInstance() {
        return INSTANCE;
    }

    private static SerializerManager createDefault() {
        SerializerManager manager = new SerializerManager();
        manager.register(JacksonJsonSerializer.create());
        manager.register(ProtobufSerializer.create());
        manager.register(FurySerializer.create());
        return manager;
    }

    public void register(ISerializer serializer) {
        serializerMap.put(serializer.type(), serializer);
    }

    public ISerializer getISerializer(byte serialType) {
        SerializationType type = SerializationType.getSerializationType(serialType);
        return type == null ? null : serializerMap.get(type);
    }
}
