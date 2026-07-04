package cn.managame.config.exception;

public class ConfigOperationException extends RuntimeException {
    public ConfigOperationException(String message) {
        super(message);
    }

    public ConfigOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
