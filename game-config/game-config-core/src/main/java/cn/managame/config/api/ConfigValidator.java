package cn.managame.config.api;

import java.util.Map;

/**
 * 配置校验器。在配置变更生效前进行校验，校验失败抛出异常则变更不会生效。
 */
@FunctionalInterface
public interface ConfigValidator {

    /**
     * 校验即将生效的配置快照。
     *
     * @param candidateConfig 即将生效的完整配置
     * @throws IllegalArgumentException 校验失败时抛出，变更将被拒绝
     */
    void validate(Map<String, String> candidateConfig) throws IllegalArgumentException;
}
