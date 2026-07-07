package cn.managame.config.exception;

import cn.managame.common.exception.GameException;

public class ConfigOperationException extends GameException {
    public ConfigOperationException(String message) {
        super(message);
    }

    public ConfigOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
