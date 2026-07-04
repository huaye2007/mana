package cn.managame.jpa.core.write;

import java.util.Objects;

/**
 * 写通道：一个写实体（按内部写实体名标识）的「路由器 + 落库器 + 模式」绑定。
 * <p>
 * 在 bootstrap 期由各存储缓存/日志工厂构建并注册进 {@link WriteChannelRegistry}；提交期
 * 异步队列据此把写任务路由到物理目标的缓冲对象、刷盘期回调对应落库器。两种模式：
 * <ul>
 *   <li>{@link Merge}：实体缓存，按 id 合并到最终态（SAVE/DELETE），落库走 {@link BatchFlusher}。</li>
 *   <li>{@link Append}：日志等 append-only，不合并，落库走 {@link AppendFlusher}。</li>
 * </ul>
 */
public sealed interface WriteChannel permits WriteChannel.Merge, WriteChannel.Append {

    /** 内部写实体名（队列分桶与通道查找的 key）。 */
    String entityName();

    /** 提交期路由器。 */
    WriteRouter router();

    /** 合并通道（实体缓存）。 */
    record Merge(String entityName, WriteRouter router, BatchFlusher flusher) implements WriteChannel {
        public Merge {
            Objects.requireNonNull(entityName, "entityName");
            Objects.requireNonNull(router, "router");
            Objects.requireNonNull(flusher, "flusher");
        }
    }

    /** 追加通道（日志等 append-only）。 */
    record Append(String entityName, WriteRouter router, AppendFlusher flusher) implements WriteChannel {
        public Append {
            Objects.requireNonNull(entityName, "entityName");
            Objects.requireNonNull(router, "router");
            Objects.requireNonNull(flusher, "flusher");
        }
    }
}
