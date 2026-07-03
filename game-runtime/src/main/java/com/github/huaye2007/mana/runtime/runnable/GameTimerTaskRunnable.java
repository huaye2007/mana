package com.github.huaye2007.mana.runtime.runnable;

import com.github.huaye2007.mana.runtime.context.GameTaskContext;
import com.github.huaye2007.mana.runtime.context.GameTaskContextHolder;
import com.github.huaye2007.mana.runtime.context.GameTaskType;
import com.github.huaye2007.mana.runtime.context.GameTimerTaskContext;
import com.github.huaye2007.mana.runtime.context.Metadata;
import com.github.huaye2007.mana.runtime.exception.GameTaskExceptionHandlers;

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
