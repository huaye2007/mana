package cn.managame.config.support;

import cn.managame.config.ConfigException;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public final class PropertiesDocument {
    private PropertiesDocument() { }

    public static Map<String, String> parse(String content) {
        if (content == null || content.isBlank()) return Map.of();
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(content));
        } catch (java.io.IOException e) {
            throw new ConfigException("invalid properties document", e);
        }
        Map<String, String> result = new LinkedHashMap<>();
        properties.stringPropertyNames().forEach(key -> result.put(key, properties.getProperty(key)));
        return Map.copyOf(result);
    }
}
