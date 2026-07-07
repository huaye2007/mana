package cn.managame.common.io;

import cn.managame.common.exception.GameException;

import java.util.function.BiFunction;

/**
 * 依次关闭多个资源并聚合失败：任一 {@code close()} 抛异常都不会中断其余资源的释放。
 * 第一个失败作为最终抛出的异常，后续失败作为 suppressed 挂在其上。
 *
 * <p>默认把失败包成 {@link GameException}；需要保留模块自有异常类型时，用
 * {@link #CloseChain(BiFunction)} 传入工厂（如 {@code RegistryOperationException::new}），
 * 抛出的类型即保持不变。</p>
 *
 * <pre>{@code
 * CloseChain chain = new CloseChain(RegistryOperationException::new);
 * chain.step("Failed to close watch", watch::close);
 * chain.step("Failed to close client", client::close);
 * chain.throwIfFailed();
 * }</pre>
 */
public final class CloseChain {

    private final BiFunction<String, Throwable, ? extends RuntimeException> exceptionFactory;
    private RuntimeException failure;

    /** 失败包成 {@link GameException}。 */
    public CloseChain() {
        this(GameException::new);
    }

    /**
     * @param exceptionFactory 把 {@code (message, cause)} 包成模块自有运行时异常的工厂，不可为 null
     */
    public CloseChain(BiFunction<String, Throwable, ? extends RuntimeException> exceptionFactory) {
        if (exceptionFactory == null) {
            throw new IllegalArgumentException("exceptionFactory must not be null");
        }
        this.exceptionFactory = exceptionFactory;
    }

    public CloseChain step(String message, CloseStep step) {
        try {
            step.close();
        } catch (Exception e) {
            RuntimeException wrapped = exceptionFactory.apply(message, e);
            if (failure == null) {
                failure = wrapped;
            } else {
                failure.addSuppressed(wrapped);
            }
        }
        return this;
    }

    public void throwIfFailed() {
        if (failure != null) {
            throw failure;
        }
    }

    @FunctionalInterface
    public interface CloseStep {
        void close() throws Exception;
    }
}
