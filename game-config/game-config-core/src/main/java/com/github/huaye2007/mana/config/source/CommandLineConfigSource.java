package com.github.huaye2007.mana.config.source;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 命令行参数配置源。支持 --key=value 和 -Dkey=value 格式，也可直接传入已解析的 Map。
 */
public class CommandLineConfigSource implements ConfigSource {
    private final List<String> rawArgs;
    private final Map<String, String> parsedArgs;

    public CommandLineConfigSource(List<String> rawArgs, Map<String, String> parsedArgs) {
        this.rawArgs = rawArgs == null ? List.of() : new ArrayList<>(rawArgs);
        this.parsedArgs = ConfigSourceMaps.immutableCopy(parsedArgs);
    }

    public CommandLineConfigSource(List<String> rawArgs) {
        this(rawArgs, Map.of());
    }

    public CommandLineConfigSource(Map<String, String> parsedArgs) {
        this(List.of(), parsedArgs);
    }

    @Override
    public String name() {
        return "COMMAND_LINE";
    }

    @Override
    public Map<String, String> load() {
        Map<String, String> result = new HashMap<>();
        for (String arg : rawArgs) {
            parseArg(arg, result);
        }
        result.putAll(parsedArgs);
        return result;
    }

    private void parseArg(String arg, Map<String, String> target) {
        if (arg == null || arg.isBlank()) {
            return;
        }
        String normalized = arg;
        if (normalized.startsWith("--")) {
            normalized = normalized.substring(2);
        } else if (normalized.startsWith("-D")) {
            normalized = normalized.substring(2);
        }
        int index = normalized.indexOf('=');
        if (index <= 0) {
            return;
        }
        String key = normalized.substring(0, index).trim();
        String value = normalized.substring(index + 1).trim();
        if (!key.isEmpty()) {
            target.put(key, value);
        }
    }
}
