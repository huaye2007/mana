package cn.managame.config.spi.support;

import cn.managame.common.lang.Strings;
import cn.managame.config.exception.ConfigOperationException;

import java.util.Properties;

/**
 * 远程配置 Provider 通用的 {@link Properties} 读取/解析助手。
 * <p>
 * 各 Provider（nacos/etcd/consul/apollo/local）原本各自重复实现这套
 * null 兜底、拷贝、firstNonBlank 以及数字解析逻辑，统一收敛到此处。
 */
public final class RemoteConfigProperties {
    private RemoteConfigProperties() {
    }

    /** null 时返回空 {@link Properties}，避免每个 Provider 重复判空。 */
    public static Properties safe(Properties properties) {
        return properties == null ? new Properties() : properties;
    }

    /** 浅拷贝一份 {@link Properties}，源为 null 时返回空实例。 */
    public static Properties copy(Properties source) {
        Properties copy = new Properties();
        if (source != null) {
            copy.putAll(source);
        }
        return copy;
    }

    /** 返回第一个非空白的值，全为空时返回 null。 */
    public static String firstNonBlank(String... values) {
        return Strings.firstNonBlank(values);
    }

    /** 解析 long，缺省/空白时取 {@code defaultValue}，非法时抛 {@link ConfigOperationException}。 */
    public static long parseLong(Properties props, String key, long defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigOperationException(key + " must be a valid number: " + raw, e);
        }
    }

    /** 解析必须为正数的 long。 */
    public static long parsePositiveLong(Properties props, String key, long defaultValue) {
        long value = parseLong(props, key, defaultValue);
        if (value <= 0) {
            throw new ConfigOperationException(key + " must be positive: " + value);
        }
        return value;
    }

    /** 解析不可为负的 long。 */
    public static long parseNonNegativeLong(Properties props, String key, long defaultValue) {
        long value = parseLong(props, key, defaultValue);
        if (value < 0) {
            throw new ConfigOperationException(key + " must not be negative: " + value);
        }
        return value;
    }

    /** 解析必须为正数的 int，缺省/空白时取 {@code defaultValue}。 */
    public static int parsePositiveInt(Properties props, String key, int defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value <= 0) {
                throw new ConfigOperationException(key + " must be positive: " + raw);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ConfigOperationException(key + " must be a valid number: " + raw, e);
        }
    }
}
