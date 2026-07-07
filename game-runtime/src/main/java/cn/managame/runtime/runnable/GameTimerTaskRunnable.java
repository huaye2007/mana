package cn.managame.runtime.runnable;

import cn.managame.runtime.context.GameTaskContext;
import cn.managame.runtime.context.GameTaskContextHolder;
import cn.managame.runtime.context.GameTaskType;
import cn.managame.runtime.context.GameTimerTaskContext;
import cn.managame.common.context.Metadata;
import cn.managame.runtime.exception.GameTaskExceptionHandlers;

public class GameTimerTaskRunnable implements IGameTaskRunnable{

    private GameTimerTaskContext gameTimerTaskContext;

    private Runnable runnable;

    public GameTimerTaskRunnable(byte group, long routerKey, byte busType, long busId, Metadata[] metadatas, Runnable runnable){
        this.gameTimerTaskContext = new GameTimerTaskContext(GameTaskType.TIMER,group,routerKey,busType,busId,metadatas);
        this.runnable  = runnable;
    }

    @Override
    public void run() {
        try{
            GameTaskContextHolder.runWith(gameTimerTaskContext, runnable);
        } catch (Throwable e) {
            GameTaskExceptionHandlers.handle(gameTimerTaskContext, e);
        }
    }

    @Override
    public GameTaskContext getGameTaskContext() {
        return gameTimerTaskContext;
    }
}
