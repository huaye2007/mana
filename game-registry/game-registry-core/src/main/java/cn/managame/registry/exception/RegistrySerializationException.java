package cn.managame.registry.exception;

public class RegistrySerializationException extends RegistryException {
    public RegistrySerializationException(String message) {
        super(message);
    }

    public RegistrySerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
