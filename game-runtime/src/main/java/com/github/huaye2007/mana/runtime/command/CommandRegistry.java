package com.github.huaye2007.mana.runtime.command;

import com.github.huaye2007.mana.runtime.annotation.GameController;
import com.github.huaye2007.mana.runtime.annotation.GameMethod;
import com.github.huaye2007.mana.runtime.context.GameTaskContext;
import com.github.huaye2007.mana.runtime.invoke.Invokers;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public final class CommandRegistry {

    private final static CommandRegistry INSTANCE = new CommandRegistry();

    private final Map<Integer, CommandMeta> commands = new ConcurrentHashMap<>();

    public static CommandRegistry getInstance(){
        return INSTANCE;
    }

    public void register(Object controller) {
        Class<?> clazz = controller.getClass();
        GameController gc = clazz.getAnnotation(GameController.class);
        if (gc == null) {
            throw new IllegalArgumentException(clazz.getName() + " is not annotated with @GameController");
        }

        byte group = gc.group();

        for (Method method : clazz.getDeclaredMethods()) {
            GameMethod cmd = method.getAnnotation(GameMethod.class);
            if (cmd == null) {
                continue;
            }

            int command = cmd.value();
            CommandMeta commandMeta = commands.get(command);
            if (commandMeta != null) {
                throw new IllegalStateException("Duplicate command: " + command +
                        " in " + clazz.getName() + "#" + method.getName() +
                        " and " + commandMeta.getMethod().getDeclaringClass().getName() +
                        "#" + commandMeta.getMethod().getName());
            }

            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length != 2) {
                throw new IllegalStateException("@GameMethod method " + clazz.getName() + "#" + method.getName() +
                        " must have exactly 2 parameters");
            }

            Invokers.requireInvokable(method);

            Method routerKeyMethod = resolveRouterKeyMethod(clazz, method, cmd.routerKeyMethod(), paramTypes[1]);

            CommandMeta meta = new CommandMeta(command, controller, method, group, paramTypes, routerKeyMethod);
            commands.put(command, meta);
        }
    }

    public CommandMeta getCommandMeta(int command) {
        return commands.get(command);
    }

    private static Method resolveRouterKeyMethod(Class<?> clazz, Method method, String routerKeyMethodName, Class<?> paraCls) {
        if (routerKeyMethodName.isEmpty()) {
            return null;
        }
        Method routerKeyMethod;
        try {
            routerKeyMethod = paraCls.getMethod(routerKeyMethodName);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("@GameMethod " + clazz.getName() + "#" + method.getName() +
                    " routerKeyMethod not found: " + paraCls.getName() + "#" + routerKeyMethodName + "()");
        }
        Class<?> returnType = routerKeyMethod.getReturnType();
        if (returnType != long.class && returnType != Long.class
                && returnType != int.class && returnType != Integer.class) {
            throw new IllegalStateException("@GameMethod " + clazz.getName() + "#" + method.getName() +
                    " routerKeyMethod must return long/int, got: " + returnType.getName());
        }
        return routerKeyMethod;
    }

}
