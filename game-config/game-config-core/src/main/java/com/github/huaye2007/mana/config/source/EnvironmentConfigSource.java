package com.github.huaye2007.mana.config.source;

import java.util.Map;

/**
 * 环境变量配置源。
 */
public class EnvironmentConfigSource implements ConfigSource {

    @Override
    public String name() {
        return "ENVIRONMENT";
    }

    @Override
    public Map<String, String> load() {
        return System.getenv();
    }
}
