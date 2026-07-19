package cn.managame.runtime.runnable;

import cn.managame.runtime.context.GameCallbackTaskContext;
import cn.managame.runtime.context.GameTaskContext;
import cn.managame.runtime.context.GameTaskContextHolder;
import cn.managame.runtime.context.GameTaskType;
import cn.managame.common.context.Metadata;
import cn.managame.runtime.exception.GameTaskExceptionHandlers;

public class GameCallbackTaskRunnable implements IGameTaskRunnable{

    private GameCallbackTaskContext gameCallbackTaskContext;

    private Runnable runnable;

    public GameCallbackTaskRunnable(byte group, long routerKey, byte busType, long busId, Metadata[] metadatas, Runnable runnable){
        this.gameCallbackTaskContext = new GameCallbackTaskContext(GameTaskType.CALLBACK,group,routerKey,busType,busId,metadatas);
        this.runnable = runnable;
    }


    @Override
    public void run() {
        try{
            GameTaskContextHolder.runWith(gameCallbackTaskContext, runnable);
        } catch (Exception e) {
            GameTaskExceptionHandlers.handle(gameCallbackTaskContext, e);
        }
    }

    @Override
    public GameTaskContext getGameTaskContext() {
        return gameCallbackTaskContext;
    }
}
