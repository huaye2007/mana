package com.github.huaye2007.mana.jpa.core.exception;

/**
 * 瞬时性写失败：网络抖动、连接断开、死锁、锁等待超时等。
 * <p>
 * 这类失败重试通常能成功，{@code FlushScheduler} 会重试到 maxRetries 上限。
 * 与之相对，约束冲突 / 字段超长 / 类型错误等确定性失败不属于此类——
 * 重试再多次也不会成功，应直接丢弃。
 */
public class RetriableWriteException extends GameJpaException {

    public RetriableWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
