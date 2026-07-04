package cn.managame.config.loader;

import cn.managame.config.exception.ConfigOperationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;

/**
 * 根据文件路径的扩展名自动选择合适的 LocalConfigLoader 实现。
 * 使用 Java SPI 机制发现所有已注册的 loader，匹配失败时回退到 PropertiesLocalConfigLoader。
 * <p>
 * SPI 发现结果（loader 原型）只扫描一次并缓存，避免在热加载场景下每个文件、每轮 reload
 * 都重新扫描 classpath。每次 {@link #create} 仍返回独立的新实例，以保证 {@code init(filePath)}
 * 写入的状态互不干扰。
 */
public final class LocalConfigLoaderFactory {
    private static volatile List<LocalConfigLoader> prototypes;

    private LocalConfigLoaderFactory() {
    }

    public static LocalConfigLoader create(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return new PropertiesLocalConfigLoader(filePath);
        }
        String extension = extractExtension(filePath);
        for (LocalConfigLoader prototype : prototypes()) {
            if (prototype.supports(extension)) {
                LocalConfigLoader loader = newInstance(prototype);
                loader.init(filePath);
                return loader;
            }
        }
        return new PropertiesLocalConfigLoader(filePath);
    }

    private static List<LocalConfigLoader> prototypes() {
        List<LocalConfigLoader> resolved = prototypes;
        if (resolved == null) {
            synchronized (LocalConfigLoaderFactory.class) {
                resolved = prototypes;
                if (resolved == null) {
                    List<LocalConfigLoader> discovered = new ArrayList<>();
                    for (LocalConfigLoader loader : ServiceLoader.load(LocalConfigLoader.class)) {
                        discovered.add(loader);
                    }
                    resolved = List.copyOf(discovered);
                    prototypes = resolved;
                }
            }
        }
        return resolved;
    }

    private static LocalConfigLoader newInstance(LocalConfigLoader prototype) {
        try {
            // SPI 已约束实现必须有公开无参构造，这里据此创建全新的独立实例。
            return prototype.getClass().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ConfigOperationException(
                    "Failed to instantiate local config loader: " + prototype.getClass().getName(), e);
        }
    }

    private static String extractExtension(String filePath) {
        int lastDot = filePath.lastIndexOf('.');
        if (lastDot < 0 || lastDot == filePath.length() - 1) {
            return "";
        }
        return filePath.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }
}
