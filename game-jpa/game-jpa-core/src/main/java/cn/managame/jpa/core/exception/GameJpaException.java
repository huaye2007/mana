package cn.managame.jpa.core.exception;

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
