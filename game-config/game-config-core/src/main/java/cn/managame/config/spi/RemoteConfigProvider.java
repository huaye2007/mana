package cn.managame.config.spi;

import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

public interface RemoteConfigProvider extends AutoCloseable {
    String type();

    default Iterable<String> aliases() {
        return java.util.List.of();
    }

    /**
     * Loads remote config using provider-specific connection properties.
     */
    Map<String, String> load(Properties remoteProperties) throws Exception;

    /**
     * Whether this provider can push changes without waiting for polling.
     */
    default boolean supportsPush() {
        return false;
    }

    /**
     * Subscribes to remote config changes. The callback receives a complete
     * latest config snapshot.
     * <p>
     * Push-aware providers SHOULD invoke {@code callback} once synchronously
     * with the initial snapshot before returning, so the manager can avoid an
     * extra polling load() on startup.
     */
    default void subscribe(Properties remoteProperties, Consumer<Map<String, String>> callback) throws Exception {
        // Default no-op for providers without push support.
    }

    /**
     * Subscribes to remote config changes and reports asynchronous push failures.
     * Existing providers can keep overriding the two-argument method; push-aware
     * providers should prefer this overload so manager health can degrade
     * immediately when parsing, reload, or callback handling fails.
     * <p>
     * Push-aware providers SHOULD invoke {@code callback} once synchronously
     * with the initial snapshot before returning, so the manager can avoid an
     * extra polling load() on startup.
     */
    default void subscribe(Properties remoteProperties,
                           Consumer<Map<String, String>> callback,
                           Consumer<Exception> errorCallback) throws Exception {
        subscribe(remoteProperties, latestConfig -> {
            try {
                callback.accept(latestConfig);
            } catch (RuntimeException e) {
                notifySubscribeError(errorCallback, e);
                throw e;
            }
        });
    }

    /**
     * Releases resources held by this provider, such as network clients,
     * listeners, watchers, or executors.
     */
    @Override
    default void close() {
        // Default no-op for stateless providers.
    }

    private static void notifySubscribeError(Consumer<Exception> errorCallback, Exception error) {
        if (errorCallback == null) {
            return;
        }
        try {
            errorCallback.accept(error);
        } catch (RuntimeException handlerError) {
            error.addSuppressed(handlerError);
        }
    }
}
