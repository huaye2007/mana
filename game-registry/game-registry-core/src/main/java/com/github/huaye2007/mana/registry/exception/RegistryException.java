package com.github.huaye2007.mana.registry.exception;

public class RegistryException extends RuntimeException {
    public RegistryException(String message) {
        super(message);
    }

    public RegistryException(String message, Throwable cause) {
        super(message, cause);
    }
}
