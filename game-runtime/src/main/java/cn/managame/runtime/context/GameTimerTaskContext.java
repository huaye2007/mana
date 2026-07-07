package cn.managame.runtime.context;

import cn.managame.common.context.Metadata;

public class GameTimerTaskContext extends GameTaskContext{
    public GameTimerTaskContext(GameTaskType taskType, byte group, long routerKey, byte busType, long busId, Metadata[] metadatas) {
        super(taskType, group, routerKey, busType, busId, metadatas);
    }
}
