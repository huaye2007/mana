package cn.managame.jpa.async;

/**
 * 刷盘 worker 的线程模型。
 * <ul>
 *   <li>{@link #VIRTUAL}（默认）：每个刷盘单元一个虚拟线程，阻塞 JDBC/Mongo 不占用平台线程，
 *       天然按物理表并发；实际并发由后端连接池上限收敛。</li>
 *   <li>{@link #PLATFORM}：有界平台线程池（线程数 = flushThreadCount），规模可控、行为可预测。</li>
 * </ul>
 * 周期触发始终是单线程定时器，与本设置无关。
 */
public enum FlushThreadMode {
    VIRTUAL,
    PLATFORM
}
