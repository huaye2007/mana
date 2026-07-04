package cn.managame.config.loader;

import cn.managame.config.exception.ConfigOperationException;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class PropertiesLocalConfigLoader implements LocalConfigLoader {
    private final String filePath;

    public PropertiesLocalConfigLoader(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public Map<String, String> load() {
        if (filePath == null || filePath.isBlank()) {
            return Map.of();
        }
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            return Map.of();
        }
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new ConfigOperationException("Failed to load local config file: " + filePath, e);
        }
        Map<String, String> result = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            result.put(key, properties.getProperty(key));
        }
        return result;
    }
}
