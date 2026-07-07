package cn.managame.common.exception;

/**
 * 框架所有模块异常的公共顶层基类。
 * <p>
 * 各模块（registry / config / jpa / rpc / serialization 等）原本各自定义
 * {@code XxxException extends RuntimeException}，形态完全一致。统一继承此基类后，
 * 调用方可用一个 {@code catch (GameException)} 兜住框架抛出的运行时异常，
 * 而各模块仍保留自己的子类型用于精确区分。
 */
public class GameException extends RuntimeException {

    public GameException(String message) {
        super(message);
    }

    public GameException(String message, Throwable cause) {
        super(message, cause);
    }
}
