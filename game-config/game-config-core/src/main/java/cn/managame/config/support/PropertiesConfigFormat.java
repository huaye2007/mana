package cn.managame.config.support;

import cn.managame.config.spi.ConfigFormat;

import java.util.Locale;
import java.util.Map;

/** Built-in {@code properties} format. Also the fallback when no other format claims a resource. */
public final class PropertiesConfigFormat implements ConfigFormat {
    @Override public String name() { return "properties"; }

    @Override public Map<String, String> parse(String content) { return PropertiesDocument.parse(content); }

    @Override public boolean claims(String resource) {
        if (resource == null) return false;
        String lower = resource.toLowerCase(Locale.ROOT);
        return lower.endsWith(".properties") || lower.endsWith(".props") || lower.endsWith(".conf");
    }
}
