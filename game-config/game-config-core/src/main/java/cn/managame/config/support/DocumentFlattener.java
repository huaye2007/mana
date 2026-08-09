package cn.managame.config.support;

import cn.managame.config.ConfigException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a structured document into flat config keys.
 *
 * <p>Shared by every structured format so they cannot drift: the same nesting produces the same keys
 * whether it arrived as JSON or YAML, which is what lets a document be moved between formats and
 * backends without touching the code that reads it.</p>
 */
final class DocumentFlattener {
    private DocumentFlattener() { }

    /** Flattens an object root. Nested objects become dotted keys, arrays become indexed keys. */
    static Map<String, String> flatten(JsonNode root, String documentKind) {
        if (root == null || root.isNull()) return Map.of();
        if (!root.isObject()) throw new ConfigException(documentKind + " config root must be an object");
        Map<String, String> result = new LinkedHashMap<>();
        flattenObject(root, "", result);
        return Map.copyOf(result);
    }

    private static void flattenObject(JsonNode node, String prefix, Map<String, String> result) {
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String key = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            append(field.getValue(), key, result);
        }
    }

    /**
     * Keeps an array both as its compact text under the base key and as indexed keys.
     *
     * <p>The indexed form is what {@link cn.managame.config.ConfigSnapshot#getList(String) getList}
     * reads, and it is what lets a later document shorten a list without leaving a stale tail.</p>
     */
    private static void flattenArray(JsonNode array, String key, Map<String, String> result) {
        result.put(key, array.toString());
        for (int index = 0; index < array.size(); index++) {
            append(array.get(index), key + "[" + index + "]", result);
        }
    }

    private static void append(JsonNode value, String key, Map<String, String> result) {
        if (value.isObject()) {
            flattenObject(value, key, result);
        } else if (value.isArray()) {
            flattenArray(value, key, result);
        } else {
            result.put(key, value.isTextual() ? value.textValue() : value.toString());
        }
    }
}
