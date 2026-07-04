package cn.managame.config.source;

import cn.managame.config.exception.ConfigOperationException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads all regular config files from a local directory in path order.
 */
public class LocalDirectoryConfigSource implements ConfigSource {
    private final String directoryPath;
    private final boolean recursive;
    private final Set<String> allowedExtensions;

    public LocalDirectoryConfigSource(String directoryPath) {
        this(directoryPath, false);
    }

    public LocalDirectoryConfigSource(String directoryPath, boolean recursive) {
        this(directoryPath, recursive, Set.of());
    }

    public LocalDirectoryConfigSource(String directoryPath, boolean recursive, Set<String> allowedExtensions) {
        this.directoryPath = directoryPath;
        this.recursive = recursive;
        this.allowedExtensions = normalizeExtensions(allowedExtensions);
    }

    @Override
    public String name() {
        return recursive ? "LOCAL_DIRECTORY_RECURSIVE" : "LOCAL_DIRECTORY";
    }

    @Override
    public Map<String, String> load() {
        if (directoryPath == null || directoryPath.isBlank()) {
            return Map.of();
        }
        Path directory = Path.of(directoryPath);
        if (!Files.exists(directory)) {
            return Map.of();
        }
        if (!Files.isDirectory(directory)) {
            throw new ConfigOperationException("Local config path is not a directory: " + directoryPath);
        }

        Map<String, String> result = new HashMap<>();
        try (Stream<Path> paths = recursive ? Files.walk(directory) : Files.list(directory)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isAllowedConfigFile)
                    .sorted(Comparator.comparing(path -> directory.relativize(path).toString()))
                    .forEach(path -> result.putAll(new LocalFileConfigSource(path.toString()).load()));
        } catch (IOException e) {
            throw new ConfigOperationException("Failed to load local config directory: " + directoryPath, e);
        }
        return ConfigSourceMaps.immutableCopy(result);
    }

    private boolean isAllowedConfigFile(Path path) {
        return allowedExtensions.isEmpty() || allowedExtensions.contains(extractExtension(path));
    }

    private static String extractExtension(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return "";
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalizeExtensions(Set<String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        for (String extension : extensions) {
            if (extension == null || extension.isBlank()) {
                continue;
            }
            String value = extension.trim().toLowerCase(Locale.ROOT);
            if (value.startsWith(".")) {
                value = value.substring(1);
            }
            if (!value.isBlank()) {
                normalized.add(value);
            }
        }
        return normalized.isEmpty() ? Set.of() : Set.copyOf(normalized);
    }
}
