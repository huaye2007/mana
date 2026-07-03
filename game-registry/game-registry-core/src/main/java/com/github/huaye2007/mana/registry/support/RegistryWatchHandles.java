package com.github.huaye2007.mana.registry.support;

import com.github.huaye2007.mana.registry.exception.RegistryOperationException;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public final class RegistryWatchHandles {

    private RegistryWatchHandles() {
    }

    public static AutoCloseable register(
            ConcurrentMap<Long, AutoCloseable> handles,
            AtomicLong idSequence,
            Object lifecycleLock,
            BooleanSupplier closed,
            AutoCloseable closeable,
            String closedMessage,
            String closeFailureMessage
    ) {
        if (closeable == null) {
            return () -> {
            };
        }
        long id = idSequence.incrementAndGet();
        synchronized (lifecycleLock) {
            if (!closed.getAsBoolean()) {
                handles.put(id, closeable);
                return watchedHandle(handles, id);
            }
        }
        RegistryOperationException failure = closeRejected(closeable, closeFailureMessage);
        if (failure != null) {
            throw failure;
        }
        throw new RegistryOperationException(closedMessage);
    }

    private static AutoCloseable watchedHandle(ConcurrentMap<Long, AutoCloseable> handles, long id) {
        return () -> {
            AutoCloseable handle = handles.remove(id);
            if (handle != null) {
                handle.close();
            }
        };
    }

    private static RegistryOperationException closeRejected(AutoCloseable closeable, String message) {
        try {
            closeable.close();
            return null;
        } catch (Exception e) {
            return new RegistryOperationException(message, e);
        }
    }
}
