package com.github.huaye2007.mana.runtime.runnable;

import com.github.huaye2007.mana.runtime.context.GameCallbackTaskContext;
import com.github.huaye2007.mana.runtime.context.GameTaskContext;
import com.github.huaye2007.mana.runtime.context.GameTaskContextHolder;
import com.github.huaye2007.mana.runtime.context.GameTaskType;
import com.github.huaye2007.mana.runtime.context.Metadata;
import com.github.huaye2007.mana.runtime.exception.GameTaskExceptionHandlers;

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
        } catch (Throwable e) {
            GameTaskExceptionHandlers.handle(gameCallbackTaskContext, e);
        }
    }

    @Override
    public GameTaskContext getGameTaskContext() {
        return gameCallbackTaskContext;
    }
}
