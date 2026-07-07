package cn.managame.serialization;

import cn.managame.common.exception.GameException;

public class SerializationException extends GameException {

    public SerializationException(String message) {
        super(message);
    }

    public SerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
