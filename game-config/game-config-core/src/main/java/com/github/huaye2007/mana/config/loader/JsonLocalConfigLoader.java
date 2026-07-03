package com.github.huaye2007.mana.config.loader;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.huaye2007.mana.config.exception.ConfigOperationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads local JSON config files and flattens nested values into dot-separated keys.
 */
public class JsonLocalConfigLoader implements LocalConfigLoader {
    private static final ObjectMapper JSON = new ObjectMapper();

    private String filePath;

    public JsonLocalConfigLoader() {
    }

    public JsonLocalConfigLoader(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public boolean supports(String extension) {
        return "json".equals(extension);
    }

    @Override
    public void init(String filePath) {
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
        try {
            String content = Files.readString(path);
            if (content.isBlank()) {
                return Map.of();
            }
            JsonNode root = JSON.readTree(content);
            if (root == null || !root.isObject()) {
                throw new ConfigOperationException("JSON parse error: root value must be an object");
            }
            Map<String, String> result = new HashMap<>();
            flattenJson("", root, result);
            return result;
        } catch (JsonProcessingException e) {
            throw new ConfigOperationException("Failed to parse JSON config file: " + filePath, e);
        } catch (IOException e) {
            throw new ConfigOperationException("Failed to load JSON config file: " + filePath, e);
        }
    }

    private void flattenJson(String prefix, JsonNode node, Map<String, String> result) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String childPrefix = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
                flattenJson(childPrefix, field.getValue(), result);
            }
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String childPrefix = prefix.isEmpty() ? String.valueOf(i) : prefix + "." + i;
                flattenJson(childPrefix, node.get(i), result);
            }
            return;
        }
        if (!prefix.isEmpty()) {
            result.put(prefix, stringify(node));
        }
    }

    private String stringify(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        return node.asText();
    }
}
