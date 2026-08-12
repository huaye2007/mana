package cn.managame.config.support;

import cn.managame.config.ConfigException;
import cn.managame.config.ConfigOptions;
import cn.managame.config.spi.ConfigFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the {@link ConfigFormat} for a resource.
 *
 * <p>Selection order: the {@code format} property pins one format for every resource of a
 * center; otherwise each resource picks the first discovered format that {@linkplain
 * ConfigFormat#claims claims} it; otherwise {@code properties}.</p>
 *
 * <p>{@code properties}, {@code yaml} and {@code json} are built in; anything else is registered by
 * the application through {@link ServiceLoader}. Every backend resolves formats the same way, so a
 * format is a property of the document, not of the backend it is stored in.</p>
 */
public final class ConfigFormats {
    /** Provider property that pins one format for all resources. */
    public static final String FORMAT_PROPERTY = "format";

    private static final ConfigFormat PROPERTIES = new PropertiesConfigFormat();
    private static final List<ConfigFormat> DISCOVERED = discover();
    private static final Map<String, ConfigFormat> BY_RESOURCE = new ConcurrentHashMap<>();

    private ConfigFormats() { }

    /**
     * Registered formats in precedence order.
     *
     * <p>Third-party formats come first so one can claim an extension ahead of a built-in; the
     * built-ins follow, with {@code properties} last because it is also the fallback. Built-ins are
     * registered directly rather than through {@code META-INF/services}: {@link ServiceLoader} is the
     * extension path, not the wiring for classes core already ships.</p>
     */
    private static List<ConfigFormat> discover() {
        List<ConfigFormat> formats = new ArrayList<>();
        ServiceLoader.load(ConfigFormat.class).forEach(formats::add);
        formats.add(new YamlConfigFormat());
        formats.add(new JsonConfigFormat());
        formats.add(PROPERTIES);
        return List.copyOf(formats);
    }

    /** Returns the format registered under {@code name}, failing when no provider supplies it. */
    public static ConfigFormat byName(String name) {
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (ConfigFormat format : DISCOVERED) {
            if (format.name().toLowerCase(Locale.ROOT).equals(wanted)) return format;
        }
        throw new ConfigException("config format is not available: " + name
                + " (available: " + DISCOVERED.stream().map(ConfigFormat::name).toList() + ")");
    }

    /** Resolves the format for one resource, honouring a pinned {@code format} property. */
    public static ConfigFormat of(ConfigOptions options, String resource) {
        String pinned = options.property(FORMAT_PROPERTY, null);
        return pinned == null || pinned.isBlank() ? forResource(resource) : byName(pinned);
    }

    /** Resolves the format for a resource by name, falling back to {@code properties}. */
    public static ConfigFormat forResource(String resource) {
        if (resource == null || resource.isEmpty()) return PROPERTIES;
        return BY_RESOURCE.computeIfAbsent(resource, name -> {
            for (ConfigFormat format : DISCOVERED) {
                if (format != PROPERTIES && format.claims(name)) return format;
            }
            return PROPERTIES;
        });
    }

    /** Parses {@code content} with the format resolved for {@code resource}. */
    public static Map<String, String> parse(ConfigOptions options, String resource, String content) {
        return of(options, resource).parse(content);
    }
}
