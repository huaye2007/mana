package cn.managame.runtime.command;

import cn.managame.runtime.context.GameCommandTaskContext;
import cn.managame.runtime.exception.GameTaskExceptionHandlers;
import cn.managame.runtime.invoke.Invokers;
import java.lang.reflect.Method;

public final class CommandMeta {

    private final int command;
    private final Object controllerInstance;
    private final Method method;
    private final Invokers.CommandInvoker invoker;
    private final byte group;
    private final Method routerKeyMethod;
    private final Invokers.RouterKeyExtractor routerKeyExtractor;
    private final Class<?>[] paramTypes;


    CommandMeta(int command, Object controllerInstance, Method method, byte group, Class<?>[] paramTypes, Method routerKeyMethod) {
        this.command = command;
        this.controllerInstance = controllerInstance;
        this.method = method;
        this.invoker = Invokers.commandInvoker(method);
        this.group = group;
        this.paramTypes = paramTypes.clone();
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
        return paramTypes.clone();
    }

    public boolean hasRouterKeyMethod() {
        return routerKeyMethod != null;
    }

    /**
     * 从消息对象中提取路由键。未配置 routerKeyMethod 时返回 defaultKey；配置后提取失败则拒绝请求。
     */
    public long extractRouterKey(Object message, long defaultKey) {
        if (routerKeyExtractor == null) {
            return defaultKey;
        }
        try {
            return routerKeyExtractor.extract(message);
        } catch (Exception e) {
            throw new IllegalArgumentException("extract routerKey failed, command=" + command, e);
        }
    }

    public void invoke(GameCommandTaskContext taskContext,Object para1, Object message) {
        try {
            invoker.invoke(controllerInstance, para1, message);
        } catch (Exception e) {
            GameTaskExceptionHandlers.handle(taskContext, e);
        }
    }

}
