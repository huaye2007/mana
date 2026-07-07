package cn.managame.runtime.context;

import cn.managame.common.context.Metadata;

public class GameCommandTaskContext extends GameTaskContext{
    private int command;

    private int seq;

    private Object session;

    public GameCommandTaskContext(GameTaskType taskType, byte group, long routerKey, byte busType, long busId,int seq, Metadata[] metadatas,int command,Object session) {
        super(taskType, group, routerKey, busType, busId, metadatas);
        this.command = command;
        this.seq = seq;
        this.session = session;
    }

    public int getCommand(){
        return command;
    }

    public Object getSession() {
        return session;
    }

    public int getSeq(){
        return seq;
    }
}
