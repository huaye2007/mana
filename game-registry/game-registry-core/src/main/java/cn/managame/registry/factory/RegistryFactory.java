package cn.managame.registry.factory;

import cn.managame.registry.exception.RegistryOperationException;
import cn.managame.registry.spi.RegistryProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.SortedSet;
import java.util.TreeSet;

public final class RegistryFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryFactory.class);
    private static final List<RegistryProvider> PROVIDERS = loadProviders();

    private RegistryFactory() {
    }

    public static List<String> availableTypes() {
        SortedSet<String> availableTypes = new TreeSet<>();
        for (RegistryProvider provider : PROVIDERS) {
            String type = safeType(provider);
            if (!type.isEmpty()) {
                availableTypes.add(type);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(availableTypes));
    }

    public static boolean isAvailable(String type) {
        if (RegistryProvider.normalize(type).isEmpty()) {
            return false;
        }
        for (RegistryProvider provider : PROVIDERS) {
            if (safeSupports(provider, type)) {
                return true;
            }
        }
        return false;
    }

    public static RegistryBundle create(RegistryConfig config) {
        if (config == null) {
            throw new RegistryOperationException("Registry config must not be null");
        }
        String requestedType = config.getTypeName();
        if (RegistryProvider.normalize(requestedType).isEmpty()) {
            throw new RegistryOperationException("Registry type must not be blank");
        }

        for (RegistryProvider provider : PROVIDERS) {
            if (safeSupports(provider, requestedType)) {
                RegistryBundle bundle = provider.create(config.copy());
                if (bundle == null) {
                    throw new RegistryOperationException(
                            "Registry provider returned null bundle for type: " + provider.type());
                }
                return bundle;
            }
        }
        throw new RegistryOperationException(
                "No registry provider found for type: " + requestedType
                        + ", available types: " + availableTypes());
    }

    private static List<RegistryProvider> loadProviders() {
        List<RegistryProvider> providers = new ArrayList<>();
        Iterator<RegistryProvider> iterator = ServiceLoader.load(RegistryProvider.class).iterator();
        while (true) {
            try {
                if (!iterator.hasNext()) {
                    return providers;
                }
                providers.add(iterator.next());
            } catch (ServiceConfigurationError e) {
                LOGGER.warn("Ignoring invalid registry provider declaration, reason: {}", e.toString());
            }
        }
    }

    private static String safeType(RegistryProvider provider) {
        try {
            return RegistryProvider.normalize(provider.type());
        } catch (RuntimeException e) {
            LOGGER.warn(
                    "Ignoring registry provider with failing type(): {}, reason: {}",
                    provider.getClass().getName(),
                    e.toString()
            );
            return "";
        }
    }

    private static boolean safeSupports(RegistryProvider provider, String requestedType) {
        try {
            return provider.supports(requestedType);
        } catch (RuntimeException e) {
            LOGGER.warn(
                    "Ignoring registry provider {} while checking support for type {}, reason: {}",
                    provider.getClass().getName(),
                    requestedType,
                    e.toString()
            );
            return false;
        }
    }
}
