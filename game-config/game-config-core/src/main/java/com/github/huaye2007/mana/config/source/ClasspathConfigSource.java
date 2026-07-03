package com.github.huaye2007.mana.config.source;

import com.github.huaye2007.mana.config.exception.ConfigOperationException;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 从 classpath 加载 .properties 配置。
 */
public class ClasspathConfigSource implements ConfigSource {
    private final String resourcePath;

    public ClasspathConfigSource(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    @Override
    public String name() {
        return "CLASSPATH";
    }

    @Override
    public Map<String, String> load() {
        if (resourcePath == null || resourcePath.isBlank()) {
            return Map.of();
        }
        ClassLoader classLoader = resolveClassLoader();
        try (InputStream in = classLoader.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return Map.of();
            }
            Properties props = new Properties();
            props.load(in);
            Map<String, String> result = new HashMap<>();
            for (String key : props.stringPropertyNames()) {
                result.put(key, props.getProperty(key));
            }
            return ConfigSourceMaps.immutableCopy(result);
        } catch (IOException e) {
            throw new ConfigOperationException("Failed to load classpath config: " + resourcePath, e);
        }
    }

    private ClassLoader resolveClassLoader() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader == null ? ClasspathConfigSource.class.getClassLoader() : contextClassLoader;
    }
}
