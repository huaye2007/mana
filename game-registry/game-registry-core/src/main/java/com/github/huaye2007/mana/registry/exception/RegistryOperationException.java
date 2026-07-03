package com.github.huaye2007.mana.registry.exception;

public class RegistryOperationException extends RegistryException {
    public RegistryOperationException(String message) {
        super(message);
    }

    public RegistryOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
