package cn.managame.jpa.async;

/**
 * 刷盘 worker 的线程模型。
 * <ul>
 *   <li>{@link #VIRTUAL}（默认）：有界虚拟 worker 池。</li>
 *   <li>{@link #PLATFORM}：有界平台 worker 池。</li>
 * </ul>
 * 两种模式的并发度都由 flushThreadCount/flushMaxConcurrency 决定；同一物理表仍保持单在途批次。
 */
public enum FlushThreadMode {
    VIRTUAL,
    PLATFORM
}
