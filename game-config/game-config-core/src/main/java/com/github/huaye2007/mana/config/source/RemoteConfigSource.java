package com.github.huaye2007.mana.config.source;

import com.github.huaye2007.mana.config.exception.ConfigOperationException;
import com.github.huaye2007.mana.config.spi.RemoteConfigProvider;

import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 远程配置源。持有自己的 provider 和连接属性。
 * <p>
 * 支持两种模式：
 * <ul>
 *   <li>轮询模式（默认）：每次 {@link #load()} 调用 {@code provider.load()}。</li>
 *   <li>推送模式：provider 支持 push 时，通过 {@code subscribe} 接收变更并缓存；
 *       订阅成功后 {@link #load()} 直接返回缓存快照，不再回源——推送通道即真相来源，
 *       断连由 provider 通过错误回调上报。</li>
 * </ul>
 */
public class RemoteConfigSource implements ConfigSource {
    private final RemoteConfigProvider provider;
    private final Properties remoteProperties;
    /** push 模式下缓存最新配置。 */
    private final AtomicReference<Map<String, String>> pushCache = new AtomicReference<>();
    private volatile boolean subscribed;
    private volatile Runnable onPushUpdate;
    private volatile Consumer<Exception> onPushError;

    public RemoteConfigSource(RemoteConfigProvider provider, Properties remoteProperties) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.remoteProperties = copyProperties(remoteProperties);
    }

    /**
     * 设置 push 更新回调，远程源主动推送变更时触发（通常由 GameConfigManager 设置以触发 reload）。
     */
    public void setOnPushUpdate(Runnable onPushUpdate) {
        this.onPushUpdate = onPushUpdate;
    }

    public void setOnPushError(Consumer<Exception> onPushError) {
        this.onPushError = onPushError;
    }

    /**
     * 如果 provider 支持 push，启动订阅；幂等，重复调用只订阅一次。应在 manager start 时调用。
     * <p>
     * provider.subscribe 在注册 listener 后允许同步回调一次初始快照；此时 subscribed 仍为
     * false，回调只填充缓存不触发 onPushUpdate，manager 后续的 reload 直接命中缓存。
     */
    public synchronized boolean startSubscriptionIfSupported() {
        if (subscribed || !provider.supportsPush()) {
            return subscribed;
        }
        try {
            provider.subscribe(remoteProperties, latestConfig -> {
                pushCache.set(copyConfig(latestConfig));
                if (subscribed && onPushUpdate != null) {
                    onPushUpdate.run();
                }
            }, this::handlePushError);
            subscribed = true;
            return true;
        } catch (Exception e) {
            throw new ConfigOperationException("Failed to subscribe remote config push", e);
        }
    }

    public boolean isPushMode() {
        return provider.supportsPush();
    }

    /**
     * 获取底层 provider，供 Manager 在 close 时调用 {@code provider.close()}。
     */
    public RemoteConfigProvider getProvider() {
        return provider;
    }

    @Override
    public String name() {
        String type = provider.type();
        return type == null || type.isBlank() ? "REMOTE" : "REMOTE:" + type.trim();
    }

    @Override
    public Map<String, String> load() {
        if (subscribed) {
            Map<String, String> cached = pushCache.get();
            if (cached != null) {
                return cached;
            }
        }
        try {
            Map<String, String> copied = copyConfig(provider.load(remoteProperties));
            if (subscribed) {
                pushCache.set(copied);
            }
            return copied;
        } catch (Exception e) {
            throw new ConfigOperationException("Failed to load remote config", e);
        }
    }

    private static Properties copyProperties(Properties source) {
        Properties copy = new Properties();
        if (source != null) {
            copy.putAll(source);
        }
        return copy;
    }

    private static Map<String, String> copyConfig(Map<String, String> source) {
        return ConfigSourceMaps.immutableCopy(source);
    }

    private void handlePushError(Exception error) {
        Consumer<Exception> handler = onPushError;
        if (handler != null) {
            handler.accept(error);
            return;
        }
        throw error instanceof ConfigOperationException configError
                ? configError
                : new ConfigOperationException("Remote config push failed", error);
    }
}
