package com.github.huaye2007.mana.registry.factory;

import com.github.huaye2007.mana.registry.api.Discovery;
import com.github.huaye2007.mana.registry.api.Registry;
import com.github.huaye2007.mana.registry.exception.RegistryOperationException;

public class RegistryBundle implements AutoCloseable {
    private final Registry registry;
    private final Discovery discovery;
    private boolean started;
    private boolean closed;

    public RegistryBundle(Registry registry, Discovery discovery) {
        if (registry == null) {
            throw new RegistryOperationException("Registry bundle registry must not be null");
        }
        if (discovery == null) {
            throw new RegistryOperationException("Registry bundle discovery must not be null");
        }
        this.registry = registry;
        this.discovery = discovery;
    }

    public Registry getRegistry() {
        return registry;
    }

    public Discovery getDiscovery() {
        return discovery;
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized void start() {
        if (closed) {
            throw new RegistryOperationException("Registry bundle has been closed");
        }
        if (started) {
            return;
        }
        boolean registryStartAttempted = false;
        boolean discoveryStartAttempted = false;
        try {
            registryStartAttempted = true;
            registry.start();
            if (registry != discovery) {
                discoveryStartAttempted = true;
                discovery.start();
            }
            started = true;
        } catch (RuntimeException e) {
            RuntimeException closeFailure = closeEndpoints(discoveryStartAttempted, registryStartAttempted);
            closed = true;
            if (closeFailure != null) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        started = false;
        RuntimeException failure = closeEndpoints(registry != discovery, true);
        if (failure != null) {
            throw failure;
        }
    }

    private RuntimeException closeEndpoints(boolean closeDiscovery, boolean closeRegistry) {
        RuntimeException failure = null;
        if (closeDiscovery && registry != discovery) {
            try {
                discovery.close();
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        if (closeRegistry) {
            try {
                registry.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        return failure;
    }

}
