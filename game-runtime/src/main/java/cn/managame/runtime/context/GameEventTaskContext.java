package cn.managame.runtime.context;

/**
 * 事件任务上下文。
 *
 * <p>跨组派发时,目标事件上下文会与发布方上下文<b>共享同一份 metadata 数组</b>(不深拷贝),
 * 因此约定<b>事件 metadata 一旦投递即不可变</b>:发布后任何一方都不得再修改。这里直接
 * 关闭 metadata 的写入口,把契约变成 fail-fast,避免两个线程并发改同一份 metadata。</p>
 */
public class GameEventTaskContext extends GameTaskContext{
    public GameEventTaskContext(GameTaskType taskType, byte group, long routerKey, byte busType, long busId, Metadata[] metadatas) {
        super(taskType, group, routerKey, busType, busId, metadatas);
    }

    @Override
    public void addMetadataStrVal(short key, String val) {
        throw new UnsupportedOperationException("event task metadata is immutable after publish");
    }

    @Override
    public void addMetadataLongVal(short key, long val) {
        throw new UnsupportedOperationException("event task metadata is immutable after publish");
    }
}
