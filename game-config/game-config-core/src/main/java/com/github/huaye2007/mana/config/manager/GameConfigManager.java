package com.github.huaye2007.mana.config.manager;

import com.github.huaye2007.mana.config.api.ChangeType;
import com.github.huaye2007.mana.config.api.ConfigChangeEvent;
import com.github.huaye2007.mana.config.api.ConfigChangeListener;
import com.github.huaye2007.mana.config.api.ConfigValidator;
import com.github.huaye2007.mana.config.exception.ConfigOperationException;
import com.github.huaye2007.mana.config.factory.GameConfigOptions;
import com.github.huaye2007.mana.config.source.ConfigSource;
import com.github.huaye2007.mana.config.source.RemoteConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * 多源分层配置管理器。
 * <p>
 * 按优先级合并多个 {@link ConfigSource}（索引越小优先级越高），支持热加载轮询、
 * 远程推送、运行期覆盖（override）、变更监听与配置校验。
 * <p>
 * 并发模型：所有状态变更在单个 {@code lock} 下串行执行；读取走 volatile 快照、无锁。
 * 监听器通知在释放锁之后进行，避免慢监听器阻塞 reload/override 链路。
 */
public class GameConfigManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GameConfigManager.class);

    private final List<ConfigSource> sources;
    private final boolean failFast;
    private final boolean hotReloadEnabled;
    private final long refreshIntervalMillis;
    private final Consumer<Exception> errorHandler;
    private final List<ConfigValidator> validators;
    private final ConfigChangeNotifier changeNotifier;
    private final ScheduledExecutorService executor;

    private final Object lock = new Object();
    private final AtomicBoolean closeStarted = new AtomicBoolean();

    /** 每个源最近一次成功加载的快照，用于源临时不可用时兜底（非 failFast）。lock 保护。 */
    private final List<Map<String, String>> lastGoodPerSource;

    /** 运行期覆盖层，优先级最高，整体作为不可变快照原子替换。 */
    private volatile Map<String, String> overrides = Map.of();
    /** 不含覆盖层的源合并快照。 */
    private volatile Map<String, String> sourceSnapshot = Map.of();
    /** 对外可见配置：sourceSnapshot 叠加 overrides。 */
    private volatile Map<String, String> current = Map.of();

    private volatile boolean started;
    private volatile boolean closed;
    /** 最近一次失败信息；null 表示健康。 */
    private volatile String lastError;

    public GameConfigManager(GameConfigOptions options) {
        Objects.requireNonNull(options, "options must not be null");
        this.sources = options.getSources();
        this.failFast = options.isFailFast();
        this.hotReloadEnabled = options.isHotReloadEnabled();
        this.refreshIntervalMillis = options.getRefreshIntervalMillis();
        this.errorHandler = options.getErrorHandler();
        this.validators = options.getValidators();
        this.changeNotifier = new ConfigChangeNotifier(options.getListenerExecutor());
        this.lastGoodPerSource = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            lastGoodPerSource.add(Map.of());
        }
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "game-config-reload");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        ConfigChangeEvent event;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("GameConfigManager has been closed and cannot be restarted");
            }
            if (started) {
                return;
            }
            try {
                subscribeRemotes();
                event = reloadLocked();
                if (closed) {
                    // close() arrived while the initial load was running; abort startup.
                    return;
                }
                if (hotReloadEnabled) {
                    long interval = Math.max(refreshIntervalMillis, 500L);
                    executor.scheduleWithFixedDelay(this::safeReload, interval, interval, TimeUnit.MILLISECONDS);
                    log.info("Hot reload enabled with interval {}ms", interval);
                }
                started = true;
            } catch (RuntimeException e) {
                closeAfterStartFailure(e);
                throw e;
            }
        }
        if (event != null) {
            changeNotifier.notifyListeners(event);
        }
        log.info("GameConfigManager started with {} source(s)", sources.size());
    }

    /** 在订阅注册阶段为推送型远程源接入回调并启动订阅。failFast 时订阅失败直接抛出。 */
    private void subscribeRemotes() {
        for (int i = 0; i < sources.size(); i++) {
            if (!(sources.get(i) instanceof RemoteConfigSource remote)) {
                continue;
            }
            int index = i;
            remote.setOnPushUpdate(this::handlePushUpdate);
            remote.setOnPushError(error -> handlePushError(index, error));
            try {
                remote.startSubscriptionIfSupported();
            } catch (RuntimeException e) {
                if (failFast) {
                    throw e;
                }
                handleRecoverableError(e);
            }
        }
    }

    /**
     * 重新加载并合并所有源；保留运行期覆盖。可能产生网络 IO，不应过于频繁调用。
     */
    public void reloadNow() {
        ConfigChangeEvent event;
        synchronized (lock) {
            if (closed) {
                return;
            }
            event = reloadLocked();
        }
        if (event != null) {
            changeNotifier.notifyListeners(event);
        }
    }

    private ConfigChangeEvent reloadLocked() {
        if (closed) {
            return null;
        }
        try {
            retrySubscriptions();
            List<Map<String, String>> freshPerSource = new ArrayList<>(sources.size());
            Map<String, String> merged = resolveSources(freshPerSource);
            if (closed) {
                // close() arrived during the (possibly slow) load; do not publish a stale result.
                return null;
            }
            ConfigChangeEvent event = applySourceSnapshot(merged);
            // Only adopt freshly loaded values as last-known-good after validation passes,
            // so an invalid reload can never poison the fallback snapshot.
            commitLastGood(freshPerSource);
            lastError = null;
            return event;
        } catch (RuntimeException e) {
            if (closed) {
                return null;
            }
            lastError = errorMessage(e);
            throw e;
        }
    }

    /** 后续 reload 中为尚未订阅成功的推送源重试订阅（启动期由 subscribeRemotes 处理）。 */
    private void retrySubscriptions() {
        if (!started || closed) {
            return;
        }
        for (ConfigSource source : sources) {
            if (source instanceof RemoteConfigSource remote && remote.isPushMode()) {
                try {
                    remote.startSubscriptionIfSupported();
                } catch (RuntimeException e) {
                    handleRecoverableError(e);
                }
            }
        }
    }

    /**
     * 按优先级加载并合并所有源；源失败时（非 failFast）回退到该源最近一次成功快照。
     * {@code freshPerSource} 收集每个源本轮新加载的快照（失败回退的位置填 null），
     * 供校验通过后提交为 last-known-good。
     */
    private Map<String, String> resolveSources(List<Map<String, String>> freshPerSource) {
        List<Map<String, String>> loaded = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            try {
                Map<String, String> values = sanitize(sources.get(i).load());
                loaded.add(values);
                freshPerSource.add(values);
            } catch (RuntimeException e) {
                if (failFast) {
                    throw e;
                }
                handleRecoverableError(e);
                loaded.add(lastGoodPerSource.get(i));
                freshPerSource.add(null);
            }
        }
        Map<String, String> resolved = new HashMap<>();
        for (int i = loaded.size() - 1; i >= 0; i--) {
            resolved.putAll(loaded.get(i));
        }
        return resolved.isEmpty() ? Map.of() : Map.copyOf(resolved);
    }

    private void commitLastGood(List<Map<String, String>> freshPerSource) {
        for (int i = 0; i < freshPerSource.size(); i++) {
            Map<String, String> fresh = freshPerSource.get(i);
            if (fresh != null) {
                lastGoodPerSource.set(i, fresh);
            }
        }
    }

    /** 应用新的源快照，叠加覆盖层并校验；仅当对外配置实际变化时返回变更事件，否则返回 null。 */
    private ConfigChangeEvent applySourceSnapshot(Map<String, String> newSourceSnapshot) {
        if (newSourceSnapshot.equals(sourceSnapshot)) {
            return null;
        }
        Map<String, String> oldConfig = current;
        Map<String, String> merged = mergeOverrides(newSourceSnapshot, overrides);
        if (merged.equals(oldConfig)) {
            sourceSnapshot = newSourceSnapshot;
            return null;
        }
        runValidators(merged);
        sourceSnapshot = newSourceSnapshot;
        current = merged;
        log.debug("Config reloaded, {} keys in snapshot", merged.size());
        return buildChangeEvent(oldConfig, merged);
    }

    private void handlePushUpdate() {
        safeReload();
    }

    private void handlePushError(int sourceIndex, Exception error) {
        lastError = errorMessage(error);
        RuntimeException runtimeError = error instanceof RuntimeException e
                ? e
                : new ConfigOperationException("Remote config push failed", error);
        log.warn("Remote config push failed for source[{}]", sourceIndex, runtimeError);
        handleRecoverableError(runtimeError);
    }

    // ---- 运行期覆盖 API ----

    public void put(String key, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        applyOverrideMutation(candidate -> {
            if (value == null) {
                candidate.remove(key);
            } else {
                candidate.put(key, value);
            }
        });
    }

    public void putAll(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        applyOverrideMutation(candidate -> {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    continue;
                }
                if (entry.getValue() == null) {
                    candidate.remove(key);
                } else {
                    candidate.put(key, entry.getValue());
                }
            }
        });
    }

    public void removeOverride(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        applyOverrideMutation(candidate -> candidate.remove(key));
    }

    public void clearOverrides() {
        applyOverrideMutation(Map::clear);
    }

    /**
     * 对单个 key 的当前值应用更新函数。返回 null 表示移除该覆盖。
     */
    public void update(String key, UnaryOperator<String> updater) {
        if (key == null || key.isBlank() || updater == null) {
            return;
        }
        applyOverrideMutation(candidate -> {
            String newValue = updater.apply(current.get(key));
            if (newValue == null) {
                candidate.remove(key);
            } else {
                candidate.put(key, newValue);
            }
        });
    }

    public Map<String, String> getOverrides() {
        return overrides;
    }

    private void applyOverrideMutation(Consumer<Map<String, String>> mutator) {
        ConfigChangeEvent event = null;
        synchronized (lock) {
            if (closed) {
                return;
            }
            Map<String, String> candidate = new HashMap<>(overrides);
            mutator.accept(candidate);
            Map<String, String> oldConfig = current;
            Map<String, String> merged = mergeOverrides(sourceSnapshot, candidate);
            runValidators(merged);
            overrides = Map.copyOf(candidate);
            if (!merged.equals(oldConfig)) {
                current = merged;
                event = buildChangeEvent(oldConfig, merged);
            }
        }
        if (event != null) {
            changeNotifier.notifyListeners(event);
        }
    }

    // ---- 读取 API ----

    public String get(String key) {
        return current.get(key);
    }

    public String get(String key, String defaultValue) {
        String v = current.get(key);
        return v != null ? v : defaultValue;
    }

    public boolean containsKey(String key) {
        return key != null && current.containsKey(key);
    }

    public int getInt(String key, int defaultValue) {
        String v = rawValue(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            warnBadValue(key, v, "integer");
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        String v = rawValue(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            warnBadValue(key, v, "long");
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        String v = rawValue(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            warnBadValue(key, v, "double");
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = rawValue(key);
        if (v == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(v) || "1".equals(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v) || "0".equals(v)) {
            return false;
        }
        warnBadValue(key, v, "boolean");
        return defaultValue;
    }

    /** 返回去空白后的原始值；缺失或空白返回 null。 */
    private String rawValue(String key) {
        if (key == null) {
            return null;
        }
        String v = current.get(key);
        if (v == null) {
            return null;
        }
        String trimmed = v.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void warnBadValue(String key, String value, String type) {
        log.warn("Config key '{}' has non-{} value '{}', using default", key, type, value);
    }

    public Map<String, String> snapshot() {
        return current;
    }

    public Set<String> keys() {
        return current.keySet();
    }

    public Map<String, String> snapshotByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return current;
        }
        Map<String, String> filtered = new HashMap<>();
        for (Map.Entry<String, String> entry : current.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered.isEmpty() ? Map.of() : Map.copyOf(filtered);
    }

    /** 是否健康：已启动、未关闭、且最近一次加载/推送无失败。 */
    public boolean isHealthy() {
        return started && !closed && lastError == null;
    }

    // ---- 监听器 ----

    public void addChangeListener(ConfigChangeListener listener) {
        changeNotifier.add(listener);
    }

    public void removeChangeListener(ConfigChangeListener listener) {
        changeNotifier.remove(listener);
    }

    // ---- 生命周期 ----

    @Override
    public void close() {
        if (!closeStarted.compareAndSet(false, true)) {
            return;
        }
        // Publish closed before contending for the lock so an in-flight reload sees it and
        // skips publishing, instead of close() blocking behind a slow load.
        closed = true;
        synchronized (lock) {
            started = false;
            executor.shutdownNow();
        }
        for (ConfigSource source : sources) {
            if (source instanceof RemoteConfigSource remote) {
                try {
                    remote.getProvider().close();
                } catch (Exception e) {
                    log.warn("Failed to close remote config provider: {}", remote.getProvider().type(), e);
                }
            }
        }
        log.info("GameConfigManager closed");
    }

    private void closeAfterStartFailure(RuntimeException startFailure) {
        try {
            close();
        } catch (RuntimeException cleanupError) {
            startFailure.addSuppressed(cleanupError);
        }
    }

    // ---- 内部辅助 ----

    private void safeReload() {
        try {
            reloadNow();
        } catch (Exception e) {
            if (closed) {
                return;
            }
            log.warn("Error during config reload", e);
            invokeErrorHandler(e);
        }
    }

    private void handleRecoverableError(RuntimeException e) {
        log.warn("Recoverable config load error", e);
        invokeErrorHandler(e);
    }

    private void invokeErrorHandler(Exception e) {
        if (errorHandler == null) {
            return;
        }
        try {
            errorHandler.accept(e);
        } catch (Exception handlerError) {
            log.warn("Config error handler threw exception", handlerError);
        }
    }

    private void runValidators(Map<String, String> candidate) {
        for (ConfigValidator validator : validators) {
            validator.validate(candidate);
        }
    }

    private static Map<String, String> mergeOverrides(Map<String, String> base, Map<String, String> overlay) {
        if (overlay.isEmpty()) {
            return base;
        }
        Map<String, String> merged = new HashMap<>(base);
        merged.putAll(overlay);
        return Map.copyOf(merged);
    }

    private static Map<String, String> sanitize(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitized = new HashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getName() : message;
    }

    private static ConfigChangeEvent buildChangeEvent(Map<String, String> oldConfig, Map<String, String> newConfig) {
        Set<String> keys = new HashSet<>(oldConfig.keySet());
        keys.addAll(newConfig.keySet());
        Set<String> changedKeys = new HashSet<>();
        Map<String, ChangeType> changeDetails = new HashMap<>();
        for (String key : keys) {
            String oldValue = oldConfig.get(key);
            String newValue = newConfig.get(key);
            if (oldValue == null && newValue != null) {
                changedKeys.add(key);
                changeDetails.put(key, ChangeType.ADDED);
            } else if (oldValue != null && newValue == null) {
                changedKeys.add(key);
                changeDetails.put(key, ChangeType.DELETED);
            } else if (oldValue != null && !oldValue.equals(newValue)) {
                changedKeys.add(key);
                changeDetails.put(key, ChangeType.UPDATED);
            }
        }
        return new ConfigChangeEvent(oldConfig, newConfig, changedKeys, changeDetails);
    }
}
