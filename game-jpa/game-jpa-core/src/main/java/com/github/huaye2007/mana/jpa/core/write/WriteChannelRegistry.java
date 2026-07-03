package com.github.huaye2007.mana.jpa.core.write;

/**
 * 写通道注册表 SPI。
 * <p>
 * 各存储缓存/日志工厂在 bootstrap 期通过本接口注册 {@link WriteChannel}，无需依赖异步写的
 * 具体实现。默认实现：{@code com.github.huaye2007.mana.jpa.async.AsyncWriteQueue}（它同时是 {@link WriteTaskSubmitter}，
 * 因为提交期路由需要通道里的 {@link WriteRouter}）。
 */
public interface WriteChannelRegistry {

    /**
     * 注册写通道。同一 {@code entityName} 重复注册时后者覆盖前者。
     */
    void register(WriteChannel channel);
}
