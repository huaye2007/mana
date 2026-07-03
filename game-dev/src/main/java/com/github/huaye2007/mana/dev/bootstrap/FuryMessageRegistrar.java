package com.github.huaye2007.mana.dev.bootstrap;

import com.github.huaye2007.mana.serialization.ISerializer;
import com.github.huaye2007.mana.serialization.SerializationType;
import com.github.huaye2007.mana.serialization.SerializerManager;
import com.github.huaye2007.mana.serialization.fury.FurySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把外网消息 DTO 登记进共享默认 Fury。
 *
 * <p>默认 Fury {@code requireClassRegistration=true}（安全默认：不可信连接只能反序列化已登记类型，
 * 挡住任意类反序列化攻击面），因此所有走外网 Fury 的请求/响应业务类型必须在收发流量前登记，
 * 否则序列化/反序列化抛异常。外网 DTO 统一放在 {@code com.github.huaye2007.mana.dev.message} 包，这里扫描该包整批登记。</p>
 *
 * <p>幂等，仅启动期单线程调用：服务端在 {@code Game} 启动时调，独立客户端进程在
 * {@code GameClientMain}/{@code GameClient} 启动时调；集成测试里收发同进程共用一套 Fury，调一次即可。
 * 按类名排序登记，保证同一 JVM 内 typeId 分配稳定。</p>
 */
public final class FuryMessageRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(FuryMessageRegistrar.class);

    /** 外网请求/响应 DTO 所在包。 */
    private static final String MESSAGE_PACKAGE = "com.github.huaye2007.mana.dev.message";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private FuryMessageRegistrar() {
    }

    /** 扫描 {@link #MESSAGE_PACKAGE} 下所有 DTO 并登记进默认 Fury。重复调用是空操作。 */
    public static void registerMessageTypes() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        ISerializer serializer = SerializerManager.getInstance()
                .getISerializer(SerializationType.FURY.typeId());
        if (!(serializer instanceof FurySerializer fury)) {
            throw new IllegalStateException("默认 Fury 序列化器缺失，无法登记外网消息类型");
        }
        List<Class<?>> types = scanMessageTypes();
        for (Class<?> type : types) {
            fury.register(type);
        }
        logger.info("Fury 登记外网消息类型 {} 个: {}", types.size(), types);
    }

    private static List<Class<?>> scanMessageTypes() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);
        List<Class<?>> types = new ArrayList<>();
        for (var def : scanner.findCandidateComponents(MESSAGE_PACKAGE)) {
            String className = def.getBeanClassName();
            try {
                types.add(Class.forName(className));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("无法加载外网消息类型: " + className, e);
            }
        }
        // 同一 JVM 内 typeId 按登记顺序分配，排序保证顺序稳定、与扫描返回顺序无关
        types.sort(Comparator.comparing(Class::getName));
        return types;
    }
}
