package cn.managame.config.spi;

import java.util.Map;

/**
 * Parses one config document into flat key/value pairs.
 *
 * <p>This is the extension point for document types. {@code properties} and {@code json} are built
 * in; to read anything else, implement this interface and register it with
 * {@link java.util.ServiceLoader}. Implementations are selected either by the {@code format} layer
 * property or by the resource name, and apply to every backend rather than to one of them.</p>
 */
public interface ConfigFormat {
    /** Format id used by the {@code format} layer property, for example {@code properties} or {@code json}. */
    String name();

    /** Parses a complete document. A blank document is an empty map, never an error. */
    Map<String, String> parse(String content);

    /**
     * Reports whether this format claims a resource by name, normally by file extension.
     *
     * <p>Only consulted when the layer does not pin a format explicitly.</p>
     */
    default boolean claims(String resource) {
        return resource != null && resource.toLowerCase(java.util.Locale.ROOT).endsWith("." + name());
    }
}
