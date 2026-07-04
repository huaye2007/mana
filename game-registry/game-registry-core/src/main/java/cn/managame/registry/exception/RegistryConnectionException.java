package cn.managame.registry.exception;

public class RegistryConnectionException extends RegistryException {
    public RegistryConnectionException(String message) {
        super(message);
    }

    public RegistryConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
