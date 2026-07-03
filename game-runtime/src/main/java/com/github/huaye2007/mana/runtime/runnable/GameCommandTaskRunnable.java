package com.github.huaye2007.mana.runtime.runnable;

import com.github.huaye2007.mana.runtime.command.CommandMeta;
import com.github.huaye2007.mana.runtime.context.GameCommandTaskContext;
import com.github.huaye2007.mana.runtime.context.GameTaskContext;
import com.github.huaye2007.mana.runtime.context.GameTaskContextHolder;
import com.github.huaye2007.mana.runtime.context.GameTaskType;
import com.github.huaye2007.mana.runtime.context.Metadata;

/**
 * <p>执行期间把上下文绑定到 {@link GameTaskContextHolder}，使 command handler 内部
 * 通过 {@code EventBus.publishEvent(event)} 发布的事件也能命中"同组同 routerKey 内联"
 * 优化；handler 仍可通过方法第一个参数显式拿到 context。</p>
 */
public class GameCommandTaskRunnable implements IGameTaskRunnable{

    private final GameCommandTaskContext gameCommandTaskContext;

    private final CommandMeta commandMeta;

    private final Object para;

    public GameCommandTaskRunnable(CommandMeta commandMeta, long routerKey, byte busType, long busId,int seq, Metadata[] metadatas, Object para,Object session){
        this.commandMeta = commandMeta;
        this.gameCommandTaskContext = new GameCommandTaskContext(GameTaskType.COMMAND,
                commandMeta.getGroup(), routerKey, busType, busId,seq, metadatas, commandMeta.getCommand(),session);
        this.para = para;
    }


    @Override
    public void run() {
        Class para1 = commandMeta.getParamTypes()[0];
        if(para1.equals(Long.class)){
            GameTaskContextHolder.runWith(gameCommandTaskContext,
                    () -> commandMeta.invoke(gameCommandTaskContext,gameCommandTaskContext.getBusId(), para));
        }
        else{
            GameTaskContextHolder.runWith(gameCommandTaskContext,
                    () -> commandMeta.invoke(gameCommandTaskContext,gameCommandTaskContext.getSession(), para));
        }

    }

    @Override
    public GameTaskContext getGameTaskContext() {
        return gameCommandTaskContext;
    }
}
