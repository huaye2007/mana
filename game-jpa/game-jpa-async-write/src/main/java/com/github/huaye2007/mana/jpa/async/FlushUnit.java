package com.github.huaye2007.mana.jpa.async;

import com.github.huaye2007.mana.jpa.core.write.WriteTask;

import java.util.List;
import java.util.function.Consumer;

/**
 * 一个刷盘单元：同一物理表、同一 op、不超过 maxBatchSize 的一批写任务，外加一个已绑定好
 * 目标 op/ctx/落库器的执行闭包。
 * <p>
 * 调度器对每个单元在 worker 上执行 {@link #flush(List)}：整批失败时降级为对单条调用 {@link #flush(List)}，
 * 单条失败再由调度器按失败类型分流。单元持有所属 {@link TableBuffer} 引用，供重试时回灌。
 */
final class FlushUnit {

    private final TableBuffer buffer;
    private final List<WriteTask> tasks;
    private final Consumer<List<WriteTask>> flushFn;

    FlushUnit(TableBuffer buffer, List<WriteTask> tasks, Consumer<List<WriteTask>> flushFn) {
        this.buffer = buffer;
        this.tasks = tasks;
        this.flushFn = flushFn;
    }

    TableBuffer buffer() {
        return buffer;
    }

    List<WriteTask> tasks() {
        return tasks;
    }

    int size() {
        return tasks.size();
    }

    /** 对给定子集执行落库（整批或单条）。 */
    void flush(List<WriteTask> subset) {
        flushFn.accept(subset);
    }
}
