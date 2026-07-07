package cn.managame.runtime.context;

import cn.managame.common.context.Metadata;

public class GameCallbackTaskContext extends GameTaskContext{
    public GameCallbackTaskContext(GameTaskType taskType, byte group, long routerKey, byte busType, long busId, Metadata[] metadatas) {
        super(taskType, group, routerKey, busType, busId, metadatas);
    }
}
