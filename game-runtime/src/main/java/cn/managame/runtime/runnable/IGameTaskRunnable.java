package cn.managame.runtime.runnable;

import cn.managame.runtime.context.GameTaskContext;

public interface IGameTaskRunnable extends Runnable {

    GameTaskContext getGameTaskContext();

}
