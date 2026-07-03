package com.github.huaye2007.mana.jpa.core.exception;

/**
 * 框架顶层异常。
 */
public class GameJpaException extends RuntimeException {

    public GameJpaException(String message) {
        super(message);
    }

    public GameJpaException(String message, Throwable cause) {
        super(message, cause);
    }
}
