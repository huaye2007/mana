package cn.managame.registry.exception;

import cn.managame.common.exception.GameException;

public class RegistryException extends GameException {
    public RegistryException(String message) {
        super(message);
    }

    public RegistryException(String message, Throwable cause) {
        super(message, cause);
    }
}
