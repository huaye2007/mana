package com.github.huaye2007.mana.jpa.async;

import com.github.huaye2007.mana.jpa.core.write.BatchFlusher;
import com.github.huaye2007.mana.jpa.core.write.WriteDestination;
import com.github.huaye2007.mana.jpa.core.write.WriteTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合并缓冲：实体缓存写回，按 id 合并到最终态（SAVE/DELETE）。
 * <p>
 * 摘取用 {@link ConcurrentHashMap#remove(Object, Object)} 值级 CAS：并发 {@code compute} 合并出新值会让
 * 摘取的 CAS 失败、新值留到下一周期，因此无需双 buffer/写者计数即可保证不丢失。
 */
final class MergeBuffer extends TableBuffer {

    private final ConcurrentHashMap<Object, WriteTask> tasks = new ConcurrentHashMap<>();
    private final BatchFlusher flusher;

    MergeBuffer(WriteDestination dest, BatchFlusher flusher) {
        super(dest);
        this.flusher = flusher;
    }

    boolean containsKey(Object id) {
        return tasks.containsKey(id);
    }

    /** 合并新提交，返回是否新增了 key（用于 pending 计数）。 */
    boolean add(WriteTask task) {
        return merge(task, false);
    }

    @Override
    boolean reAdd(WriteTask task) {
        // 重试回灌：被重试的（较旧）任务合并到任何更新的现有任务“之下”。
        return merge(task, true);
    }

    private boolean merge(WriteTask incoming, boolean afterExisting) {
        boolean[] added = { false };
        tasks.compute(incoming.id(), (id, existing) -> {
            if (existing == null) {
                added[0] = true;
                return incoming.copy();
            }
            WriteTask base = afterExisting ? incoming.copy() : existing.copy();
            WriteTask other = afterExisting ? existing : incoming;
            boolean keep = base.merge(other.op(), other.entity());
            return keep ? base : null;
        });
        return added[0];
    }

    @Override
    List<FlushUnit> drainSlices(int maxBatchSize) {
        List<WriteTask> saves = new ArrayList<>();
        List<WriteTask> deletes = new ArrayList<>();
        for (Map.Entry<Object, WriteTask> entry : tasks.entrySet()) {
            WriteTask task = entry.getValue();
            if (tasks.remove(entry.getKey(), task)) {
                (task.op() == WriteTask.Op.SAVE ? saves : deletes).add(task);
            }
        }
        List<FlushUnit> units = new ArrayList<>();
        sliceInto(units, WriteTask.Op.SAVE, saves, maxBatchSize);
        sliceInto(units, WriteTask.Op.DELETE, deletes, maxBatchSize);
        return units;
    }

    private void sliceInto(List<FlushUnit> units, WriteTask.Op op, List<WriteTask> group, int maxBatchSize) {
        for (int start = 0; start < group.size(); start += maxBatchSize) {
            int end = Math.min(start + maxBatchSize, group.size());
            List<WriteTask> slice = (start == 0 && end == group.size())
                    ? group
                    : new ArrayList<>(group.subList(start, end));
            units.add(new FlushUnit(this, slice, subset -> flusher.flush(op, subset, ctx)));
        }
    }
}
