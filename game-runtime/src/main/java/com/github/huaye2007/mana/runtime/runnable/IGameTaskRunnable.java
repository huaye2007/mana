package com.github.huaye2007.mana.runtime.runnable;

import com.github.huaye2007.mana.runtime.context.GameTaskContext;

public interface IGameTaskRunnable extends Runnable {

    GameTaskContext getGameTaskContext();

}
