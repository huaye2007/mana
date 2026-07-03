package com.github.huaye2007.mana.runtime.command;

import com.github.huaye2007.mana.runtime.context.GameCommandTaskContext;
import com.github.huaye2007.mana.runtime.context.GameTaskContext;
import com.github.huaye2007.mana.runtime.exception.GameTaskExceptionHandlers;
import com.github.huaye2007.mana.runtime.invoke.Invokers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Method;

public final class CommandMeta {

    private final static Logger logger = LoggerFactory.getLogger(CommandMeta.class);

    private final int command;
    private final Object controllerInstance;
    private final Method method;
    private final Invokers.CommandInvoker invoker;
    private final byte group;
    private final Method routerKeyMethod;
    private final Invokers.RouterKeyExtractor routerKeyExtractor;
    private Class<?>[] paramTypes;


    CommandMeta(int command, Object controllerInstance, Method method, byte group, Class<?>[] paramTypes, Method routerKeyMethod) {
        this.command = command;
        this.controllerInstance = controllerInstance;
        this.method = method;
        this.invoker = Invokers.commandInvoker(method);
        this.group = group;
        this.paramTypes = paramTypes;
        this.routerKeyMethod = routerKeyMethod;
        this.routerKeyExtractor = routerKeyMethod != null ? Invokers.routerKeyExtractor(routerKeyMethod) : null;
    }

    public int getCommand() {
        return command;
    }

    public Object getControllerInstance() {
        return controllerInstance;
    }

    public Method getMethod() {
        return method;
    }

    public byte getGroup() {
        return group;
    }

    public Class<?>[] getParamTypes() {
        return paramTypes;
    }

    public boolean hasRouterKeyMethod() {
        return routerKeyMethod != null;
    }

    /**
     * 从消息对象中提取路由键。未配置 routerKeyMethod 或提取失败时返回 defaultKey。
     */
    public long extractRouterKey(Object message, long defaultKey) {
        if (routerKeyExtractor == null) {
            return defaultKey;
        }
        try {
            return routerKeyExtractor.extract(message);
        } catch (Throwable e) {
            logger.error("extract routerKey failed, command={}", command, e);
            return defaultKey;
        }
    }

    public void invoke(GameCommandTaskContext taskContext,Object para1, Object message) {
        try {
            invoker.invoke(controllerInstance, para1, message);
        } catch (Throwable e) {
            GameTaskExceptionHandlers.handle(taskContext, e);
        }
    }

}
