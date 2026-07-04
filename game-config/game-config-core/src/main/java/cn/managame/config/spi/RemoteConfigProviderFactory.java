package cn.managame.config.spi;

import cn.managame.config.exception.ConfigOperationException;

import java.util.Locale;
import java.util.ServiceLoader;

public final class RemoteConfigProviderFactory {
    private RemoteConfigProviderFactory() {
    }

    /**
     * 根据类型通过 SPI 发现并创建远程配置 Provider。
     * <p>
     * 每次调用都会返回一个<strong>新的</strong> Provider 实例（不做缓存/单例）。
     * Provider 是有状态的（持有连接、监听线程等），其生命周期由调用方负责：
     * 通常交给 {@link cn.managame.config.source.RemoteConfigSource} 持有，并在
     * {@code GameConfigManager.close()} 时调用 {@code provider.close()} 释放。
     * 不要假设同一 {@code remoteType} 多次调用会共享同一实例。
     *
     * @param remoteType 远程配置类型（如 nacos、apollo、consul、etcd），不区分大小写
     * @return 对应的 Provider 新实例
     */
    public static RemoteConfigProvider create(String remoteType) {
        if (remoteType == null || remoteType.isBlank()) {
            throw new ConfigOperationException("remoteType must not be blank");
        }
        String key = normalize(remoteType);
        return doCreate(key);
    }

    private static RemoteConfigProvider doCreate(String expectedType) {
        ServiceLoader<RemoteConfigProvider> providers = ServiceLoader.load(RemoteConfigProvider.class);
        for (RemoteConfigProvider provider : providers) {
            if (matches(provider, expectedType)) {
                return provider;
            }
        }
        throw new ConfigOperationException(
                "No remote config provider found for type: " + expectedType
                        + ". Ensure the matching game-config provider module is on the runtime classpath.");
    }

    static boolean matches(RemoteConfigProvider provider, String expectedType) {
        String providerType = normalize(provider.type());
        if (providerType != null && providerType.equals(expectedType)) {
            return true;
        }
        Iterable<String> aliases = provider.aliases();
        if (aliases == null) {
            return false;
        }
        for (String alias : aliases) {
            String normalizedAlias = normalize(alias);
            if (normalizedAlias != null && normalizedAlias.equals(expectedType)) {
                return true;
            }
        }
        return false;
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
