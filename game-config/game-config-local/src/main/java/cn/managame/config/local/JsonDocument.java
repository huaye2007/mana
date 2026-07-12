package cn.managame.config.local;

import cn.managame.config.ConfigException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class JsonDocument {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonDocument() { }

    static Map<String, String> parse(String content) {
        if (content == null || content.isBlank()) return Map.of();
        try {
            JsonNode root = MAPPER.readTree(content);
            if (!root.isObject()) throw new ConfigException("JSON config root must be an object");
            Map<String, String> result = new LinkedHashMap<>();
            flatten(root, "", result);
            return Map.copyOf(result);
        } catch (JsonProcessingException e) {
            throw new ConfigException("invalid JSON document", e);
        }
    }

    private static void flatten(JsonNode node, String prefix, Map<String, String> result) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            JsonNode value = field.getValue();
            if (value.isObject()) {
                flatten(value, key, result);
            } else if (value.isArray()) {
                flattenArray(value, key, result);
            } else {
                result.put(key, scalarValue(value));
            }
        }
    }

    private static void flattenArray(JsonNode array, String key, Map<String, String> result) {
        result.put(key, array.toString());
        for (int index = 0; index < array.size(); index++) {
            JsonNode value = array.get(index);
            String indexedKey = key + "[" + index + "]";
            if (value.isObject()) {
                flatten(value, indexedKey, result);
            } else if (value.isArray()) {
                flattenArray(value, indexedKey, result);
            } else {
                result.put(indexedKey, scalarValue(value));
            }
        }
    }

    private static String scalarValue(JsonNode value) {
        return value.isTextual() ? value.textValue() : value.toString();
    }
}
