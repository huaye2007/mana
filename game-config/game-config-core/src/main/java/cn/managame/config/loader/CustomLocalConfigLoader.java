package cn.managame.config.loader;

import cn.managame.config.exception.ConfigOperationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取自定义格式的本地配置文件（扩展名 .conf / .cfg / .ini）。
 * <p>
 * 支持的格式规则：
 * <ul>
 *   <li>每行一个 key-value，分隔符支持 '=' 和 ':'</li>
 *   <li>'#' 或 '//' 开头的行为注释</li>
 *   <li>支持 [section] 分组，key 会自动加上 section 前缀，如 [server] port=8080 → server.port=8080</li>
 *   <li>空行忽略</li>
 *   <li>key 和 value 自动 trim</li>
 * </ul>
 */
public class CustomLocalConfigLoader implements LocalConfigLoader {
    private String filePath;

    public CustomLocalConfigLoader() {
    }

    public CustomLocalConfigLoader(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public boolean supports(String extension) {
        return "conf".equals(extension) || "cfg".equals(extension) || "ini".equals(extension);
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
            List<String> lines = Files.readAllLines(path);
            return parse(lines);
        } catch (IOException e) {
            throw new ConfigOperationException("Failed to load custom config file: " + filePath, e);
        }
    }

    private Map<String, String> parse(List<String> lines) {
        Map<String, String> result = new HashMap<>();
        String currentSection = "";
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            // section header
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length() - 1).trim();
                continue;
            }
            // key-value: 先找 '='，找不到再找 ':'
            int sep = line.indexOf('=');
            if (sep < 0) {
                sep = line.indexOf(':');
            }
            if (sep <= 0) {
                continue;
            }
            String key = line.substring(0, sep).trim();
            String value = line.substring(sep + 1).trim();
            if (!currentSection.isEmpty()) {
                key = currentSection + "." + key;
            }
            result.put(key, value);
        }
        return result;
    }
}
