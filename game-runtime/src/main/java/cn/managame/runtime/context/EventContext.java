package cn.managame.runtime.context;

import cn.managame.runtime.event.Event;

public interface EventContext extends InvocationContext { Event event(); }
