package com.github.huaye2007.mana.config.exception;

public class ConfigOperationException extends RuntimeException {
    public ConfigOperationException(String message) {
        super(message);
    }

    public ConfigOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
