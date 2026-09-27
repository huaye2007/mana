package cn.managame.runtime.context;

public class DefaultTimerContext extends DefaultContext implements TimerContext {
    public DefaultTimerContext(int domain, long key) { super(domain, key); }
}
