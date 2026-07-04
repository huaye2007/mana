package cn.managame.config.util;

import cn.managame.config.exception.ConfigOperationException;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 属性格式解析工具。将 key=value 格式的文本解析为 Map。
 * <p>
 * 规则：
 * <ul>
 *   <li>每行一个 key-value，分隔符为 '='</li>
 *   <li>'#' 开头的行为注释</li>
 *   <li>空行忽略</li>
 *   <li>key 和 value 自动 trim</li>
 * </ul>
 */
public final class PropertyParser {

    private PropertyParser() {
    }

    /**
     * 解析 properties 格式的文本内容。
     *
     * @param content 文本内容，可为 null
     * @return 解析后的 key-value Map，不会返回 null
     */
    public static Map<String, String> parse(String content) {
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(content));
        } catch (IOException e) {
            throw new ConfigOperationException("Failed to parse properties content", e);
        }
        Map<String, String> map = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key != null && !key.isBlank()) {
                String value = properties.getProperty(key);
                map.put(key.trim(), value == null ? "" : value.trim());
            }
        }
        return map;
    }
}
