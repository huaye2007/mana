package cn.managame.registry.support;

import cn.managame.common.lang.Strings;
import cn.managame.registry.api.ServiceInstance;
import cn.managame.registry.exception.RegistryOperationException;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public final class RegistryValidators {
    private RegistryValidators() {
    }

    /**
     * Returns {@code true} if {@code serviceName} passes {@link #validateServiceName}, otherwise logs
     * a warning through {@code logger} and returns {@code false}. Used by providers to skip malformed
     * names coming back from a registry without aborting the whole query/watch.
     */
    public static boolean isValidServiceName(Logger logger, String serviceName) {
        try {
            validateServiceName(serviceName);
            return true;
        } catch (RegistryOperationException e) {
            logger.warn("Ignoring invalid service name {}", serviceName);
            return false;
        }
    }

    public static void validateEndpoints(String endpoints) {
        normalizeEndpoints(endpoints);
    }

    public static String normalizeEndpoints(String endpoints) {
        requireNonBlank(endpoints, "endpoints");
        List<String> normalized = new ArrayList<>();
        for (String endpoint : endpoints.split(",")) {
            String trimmed = endpoint.trim();
            if (Strings.isBlank(trimmed)) {
                throw new RegistryOperationException("endpoints must not contain blank entries");
            }
            normalized.add(trimmed);
        }
        return String.join(",", normalized);
    }

    public static void validateBasePath(String basePath) {
        normalizeBasePath(basePath);
    }

    public static String normalizeBasePath(String basePath) {
        requireNonBlank(basePath, "basePath");
        String normalized = basePath.trim();
        if (!normalized.startsWith("/")) {
            throw new RegistryOperationException("basePath must start with /");
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public static void validateLeaseTtlSeconds(long leaseTtlSeconds) {
        if (leaseTtlSeconds <= 0) {
            throw new RegistryOperationException("leaseTtlSeconds must be > 0");
        }
    }

    public static void validateServiceName(String serviceName) {
        requireNonBlank(serviceName, "serviceName");
        if (!serviceName.equals(serviceName.trim())) {
            throw new RegistryOperationException("serviceName must not contain leading or trailing whitespace");
        }
        if (serviceName.contains("/")) {
            throw new RegistryOperationException("serviceName must not contain /");
        }
    }

    public static void validateListener(Object listener) {
        if (listener == null) {
            throw new RegistryOperationException("listener must not be null");
        }
    }

    public static void validateInstance(ServiceInstance instance) {
        if (instance == null) {
            throw new RegistryOperationException("serviceInstance must not be null");
        }
        validateServiceName(instance.getName());
        validateInstanceId(instance.getId());
        requireNonBlank(instance.getAddress(), "address");
        if (!instance.getAddress().equals(instance.getAddress().trim())) {
            throw new RegistryOperationException("address must not contain leading or trailing whitespace");
        }
        if (Strings.isBlank(instance.getId()) && instance.getAddress().contains("/")) {
            throw new RegistryOperationException("address must not contain / when id is blank");
        }
        if (instance.getPort() <= 0 || instance.getPort() > 65535) {
            throw new RegistryOperationException("port must be between 1 and 65535");
        }
        if (!Double.isFinite(instance.getWeight()) || instance.getWeight() < 0) {
            throw new RegistryOperationException("weight must be finite and >= 0");
        }
        if (instance.getRegistrationTimeUTC() < 0) {
            throw new RegistryOperationException("registrationTimeUTC must be >= 0");
        }
        if (instance.getMetadata() != null) {
            instance.getMetadata().forEach((key, value) -> {
                if (key == null || value == null) {
                    throw new RegistryOperationException("metadata keys and values must not be null");
                }
            });
        }
    }

    private static void validateInstanceId(String id) {
        if (id != null && !id.isBlank()) {
            if (!id.equals(id.trim())) {
                throw new RegistryOperationException("id must not contain leading or trailing whitespace");
            }
            if (id.contains("/")) {
                throw new RegistryOperationException("id must not contain /");
            }
        }
    }

    public static void requireNonBlank(String value, String fieldName) {
        if (Strings.isBlank(value)) {
            throw new RegistryOperationException(fieldName + " must not be blank");
        }
    }
}
