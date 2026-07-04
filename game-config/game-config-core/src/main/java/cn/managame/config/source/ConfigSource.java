package cn.managame.config.source;

import java.util.Map;

/**
 * 统一的配置源抽象。每种配置来源（classpath、本地文件、远程、JVM、命令行、环境变量、默认值）
 * 各自实现此接口，自行持有所需参数，自行负责加载逻辑。
 */
public interface ConfigSource {

    /**
     * 配置源名称，用于日志和调试。
     */
    String name();

    /**
     * 加载并返回此配置源的所有 key-value。
     * 每次调用都应返回最新数据（支持热加载场景）。
     */
    Map<String, String> load();
}
