package com.github.huaye2007.mana.config.loader;

import java.util.Map;

public interface LocalConfigLoader {
    Map<String, String> load();

    /**
     * 判断此 loader 是否支持给定的文件扩展名。
     * 默认返回 false，子类按需覆盖。
     */
    default boolean supports(String extension) {
        return false;
    }

    /**
     * 通过 SPI 发现后，由工厂调用以注入文件路径。
     * 通过构造函数创建的 loader 无需实现此方法。
     */
    default void init(String filePath) {
    }
}
