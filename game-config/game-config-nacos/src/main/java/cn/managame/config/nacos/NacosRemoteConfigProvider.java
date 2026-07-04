package cn.managame.config.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import cn.managame.config.exception.ConfigOperationException;
import cn.managame.config.spi.RemoteConfigProvider;
import cn.managame.config.util.PropertyParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static cn.managame.config.spi.support.RemoteConfigProperties.parsePositiveLong;
import static cn.managame.config.spi.support.RemoteConfigProperties.safe;

/**
 * Nacos 远程配置提供者，支持多 group + 多 dataId。
 * <p>
 * 配置方式（Properties 中的 key）：
 * <ul>
 *   <li>多配置（推荐）：{@code dataIds=GROUP1:dataId1,GROUP2:dataId2,dataId3}
 *       <br>省略 group 部分时使用 {@code defaultGroup}（默认 DEFAULT_GROUP）</li>
 *   <li>单配置（兼容旧写法）：{@code dataId=xxx} + {@code group=yyy}</li>
 * </ul>
 * 多个 dataId 的配置按声明顺序合并，后声明的同名 key 覆盖先声明的。
 * <p>
 * push 模式下，每个 dataId 独立缓存，变更时只更新对应缓存再合并回调，避免无意义的网络请求。
 */
public class NacosRemoteConfigProvider implements RemoteConfigProvider {
    private static final Logger log = LoggerFactory.getLogger(NacosRemoteConfigProvider.class);

    /** 每个 dataId 的独立缓存，key = "group@@dataId" */
    private final ConcurrentHashMap<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    /** 解析后的 entry 列表，保持声明顺序（决定合并优先级） */
    private volatile List<GroupDataId> entries;

    /** 缓存的 ConfigService 实例 */
    private volatile ConfigService configService;

    /** listener 回调使用的线程池 */
    private volatile ExecutorService listenerExecutor;
    private final Object listenerLock = new Object();
    private final AtomicLong listenerGeneration = new AtomicLong();
    private final CopyOnWriteArrayList<RegisteredListener> registeredListeners = new CopyOnWriteArrayList<>();

    public NacosRemoteConfigProvider() {
    }

    NacosRemoteConfigProvider(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public String type() {
        return "nacos";
    }

    @Override
    public boolean supportsPush() {
        return true;
    }

    @Override
    public Map<String, String> load(Properties remoteProperties) throws Exception {
        Properties props = safe(remoteProperties);
        long timeoutMs = parsePositiveLong(props, "timeoutMs", 3000L);
        List<GroupDataId> list = resolveEntries(props);
        ConfigService cs = getOrCreateConfigService(props);

        Map<String, String> merged = new HashMap<>();
        for (GroupDataId entry : list) {
            String content = cs.getConfig(entry.dataId(), entry.group(), timeoutMs);
            Map<String, String> parsed = PropertyParser.parse(content);
            cache.put(entry.cacheKey(), parsed);
            merged.putAll(parsed);
        }
        this.entries = list;
        return merged;
    }

    @Override
    public void subscribe(Properties remoteProperties, Consumer<Map<String, String>> callback) throws Exception {
        subscribe(remoteProperties, callback, null);
    }

    @Override
    public void subscribe(Properties remoteProperties,
                          Consumer<Map<String, String>> callback,
                          Consumer<Exception> errorCallback) throws Exception {
        Properties props = safe(remoteProperties);
        long timeoutMs = parsePositiveLong(props, "timeoutMs", 3000L);
        List<GroupDataId> list = resolveEntries(props);
        ConfigService cs = getOrCreateConfigService(props);
        this.entries = list;

        // 初始加载并缓存每个 dataId
        for (GroupDataId entry : list) {
            String content = cs.getConfig(entry.dataId(), entry.group(), timeoutMs);
            cache.put(entry.cacheKey(), PropertyParser.parse(content));
        }
        long generation = listenerGeneration.incrementAndGet();

        ExecutorService executor = getOrCreateListenerExecutor();
        List<RegisteredListener> newRegistrations = new ArrayList<>();
        // 为每个 dataId 注册独立 listener，变更时只更新对应缓存
        try {
            for (GroupDataId entry : list) {
                Listener listener = new Listener() {
                    @Override
                    public Executor getExecutor() {
                        return executor;
                    }

                    @Override
                    public void receiveConfigInfo(String configInfo) {
                        if (!isCurrentListener(generation)) {
                            return;
                        }
                        // 只更新变更的那个 dataId 的缓存
                        try {
                            synchronized (listenerLock) {
                                if (!isCurrentListener(generation)) {
                                    return;
                                }
                                cache.put(entry.cacheKey(), PropertyParser.parse(configInfo));
                                callback.accept(mergeFromCache());
                            }
                        } catch (RuntimeException e) {
                            if (isCurrentListener(generation)) {
                                log.warn("Failed to handle Nacos config push for {}/{}",
                                        entry.group(), entry.dataId(), e);
                                reportPushError(errorCallback, e);
                            }
                        }
                    }
                };
                cs.addListener(entry.dataId(), entry.group(), listener);
                newRegistrations.add(new RegisteredListener(entry.group(), entry.dataId(), listener));
            }
        } catch (Exception e) {
            removeListeners(cs, newRegistrations);
            if (registeredListeners.isEmpty()) {
                shutdownListenerExecutor();
            }
            throw e;
        }

        List<RegisteredListener> previousRegistrations;
        synchronized (listenerLock) {
            if (!isCurrentListener(generation)) {
                removeListeners(cs, newRegistrations);
                if (registeredListeners.isEmpty()) {
                    shutdownListenerExecutor();
                }
                return;
            }
            previousRegistrations = new ArrayList<>(registeredListeners);
            registeredListeners.clear();
            registeredListeners.addAll(newRegistrations);
            callback.accept(mergeFromCache());
        }
        removeListeners(cs, previousRegistrations);
    }

    @Override
    public void close() {
        ConfigService cs = this.configService;
        synchronized (listenerLock) {
            listenerGeneration.incrementAndGet();
        }
        if (cs != null) {
            removeListeners(cs, registeredListeners);
        } else {
            registeredListeners.clear();
        }
        // 关闭 listener 线程池
        shutdownListenerExecutor();

        // 关闭 ConfigService
        if (cs != null) {
            try {
                cs.shutDown();
            } catch (Exception e) {
                log.warn("Failed to shutdown Nacos ConfigService", e);
            }
            this.configService = null;
        }
    }

    private ExecutorService getOrCreateListenerExecutor() {
        ExecutorService executor = listenerExecutor;
        if (executor == null) {
            synchronized (this) {
                executor = listenerExecutor;
                if (executor == null) {
                    executor = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "nacos-config-listener");
                        t.setDaemon(true);
                        return t;
                    });
                    listenerExecutor = executor;
                }
            }
        }
        return executor;
    }

    private void shutdownListenerExecutor() {
        ExecutorService executor = this.listenerExecutor;
        if (executor != null) {
            executor.shutdownNow();
            this.listenerExecutor = null;
        }
    }

    private boolean isCurrentListener(long generation) {
        return generation == listenerGeneration.get();
    }

    private void removeListeners(ConfigService cs, Iterable<RegisteredListener> registrations) {
        for (RegisteredListener registration : registrations) {
            try {
                cs.removeListener(registration.dataId(), registration.group(), registration.listener());
            } catch (Exception e) {
                log.warn("Failed to remove Nacos config listener for {}/{}",
                        registration.group(), registration.dataId(), e);
            }
        }
        if (registrations == registeredListeners) {
            registeredListeners.clear();
        }
    }

    /**
     * 按 entries 声明顺序合并所有缓存，后声明的覆盖先声明的。
     */
    private Map<String, String> mergeFromCache() {
        Map<String, String> merged = new HashMap<>();
        List<GroupDataId> list = this.entries;
        if (list == null) {
            return merged;
        }
        for (GroupDataId entry : list) {
            Map<String, String> part = cache.get(entry.cacheKey());
            if (part != null) {
                merged.putAll(part);
            }
        }
        return merged;
    }

    // ---- 配置解析 ----

    /**
     * 解析 Properties 中的 dataId 配置，支持两种格式：
     * <ol>
     *   <li>dataIds=GROUP:dataId,GROUP:dataId,dataId（推荐）</li>
     *   <li>dataId=xxx + group=yyy（兼容旧写法）</li>
     * </ol>
     */
    private List<GroupDataId> resolveEntries(Properties props) {
        String dataIds = props.getProperty("dataIds");
        if (dataIds != null && !dataIds.isBlank()) {
            return parseDataIds(dataIds, props.getProperty("defaultGroup", "DEFAULT_GROUP"));
        }
        String dataId = props.getProperty("dataId");
        if (dataId == null || dataId.isBlank()) {
            throw new ConfigOperationException("Nacos config requires dataId or dataIds");
        }
        String group = props.getProperty("group", "DEFAULT_GROUP");
        return List.of(new GroupDataId(group.trim(), dataId.trim()));
    }

    private List<GroupDataId> parseDataIds(String dataIds, String defaultGroup) {
        List<GroupDataId> result = new ArrayList<>();
        for (String segment : dataIds.split(",")) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx >= 0) {
                String group = trimmed.substring(0, colonIdx).trim();
                String dataId = trimmed.substring(colonIdx + 1).trim();
                if (group.isEmpty() || dataId.isEmpty()) {
                    throw new ConfigOperationException("Nacos dataIds contains blank group or dataId: " + segment);
                }
                result.add(new GroupDataId(group, dataId));
            } else {
                String group = defaultGroup == null || defaultGroup.isBlank() ? "DEFAULT_GROUP" : defaultGroup.trim();
                result.add(new GroupDataId(group, trimmed));
            }
        }
        if (result.isEmpty()) {
            throw new ConfigOperationException("Nacos config requires dataId or dataIds");
        }
        return result;
    }

    private ConfigService getOrCreateConfigService(Properties remoteProperties) throws Exception {
        if (configService == null) {
            synchronized (this) {
                if (configService == null) {
                    Properties configProperties = new Properties();
                    configProperties.putAll(remoteProperties);
                    configService = NacosFactory.createConfigService(configProperties);
                }
            }
        }
        return configService;
    }

    private void reportPushError(Consumer<Exception> errorCallback, Exception error) {
        if (errorCallback == null) {
            return;
        }
        try {
            errorCallback.accept(error);
        } catch (RuntimeException handlerError) {
            log.warn("Nacos config push error handler threw exception", handlerError);
        }
    }

    private record RegisteredListener(String group, String dataId, Listener listener) {
    }

    private record GroupDataId(String group, String dataId) {
        String cacheKey() {
            return group + "@@" + dataId;
        }
    }
}
