package cn.managame.config.local;

import cn.managame.config.spi.ConfigFormat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A third file type added from outside this module, standing in for whatever a user needs next.
 *
 * <p>Registered through {@code META-INF/services} under test resources; nothing in the provider knows
 * it exists. This is the whole extension contract for reading a new file type.</p>
 */
public final class DotenvConfigFormat implements ConfigFormat {
    @Override public String name() { return "dotenv"; }

    @Override public boolean claims(String resource) {
        return resource != null && resource.toLowerCase(Locale.ROOT).endsWith(".env");
    }

    @Override public Map<String, String> parse(String content) {
        if (content == null || content.isBlank()) return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int separator = trimmed.indexOf('=');
            if (separator <= 0) continue;
            values.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
        }
        return Map.copyOf(values);
    }
}
