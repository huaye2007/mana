package cn.managame.jpa.core.exception;

import cn.managame.common.exception.GameException;

/**
 * game-jpa 模块顶层异常，继承框架公共 {@link GameException}。
 */
public class GameJpaException extends GameException {

    public GameJpaException(String message) {
        super(message);
    }

    public GameJpaException(String message, Throwable cause) {
        super(message, cause);
    }
}
