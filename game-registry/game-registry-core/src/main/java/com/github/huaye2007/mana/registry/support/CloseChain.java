package com.github.huaye2007.mana.registry.support;

import com.github.huaye2007.mana.registry.exception.RegistryOperationException;

/**
 * Accumulates failures while closing several resources so that one failing {@code close()} does not
 * stop the rest from being released. The first failure becomes the thrown exception; subsequent
 * failures are attached as suppressed exceptions.
 *
 * <pre>{@code
 * CloseChain chain = new CloseChain();
 * chain.step("Failed to close watch", watch::close);
 * chain.step("Failed to close client", client::close);
 * chain.throwIfFailed();
 * }</pre>
 */
public final class CloseChain {

    private RegistryOperationException failure;

    public CloseChain step(String message, CloseStep step) {
        try {
            step.close();
        } catch (Exception e) {
            RegistryOperationException wrapped = new RegistryOperationException(message, e);
            if (failure == null) {
                failure = wrapped;
            } else {
                failure.addSuppressed(wrapped);
            }
        }
        return this;
    }

    public void throwIfFailed() {
        if (failure != null) {
            throw failure;
        }
    }

    @FunctionalInterface
    public interface CloseStep {
        void close() throws Exception;
    }
}
