package cn.managame.runtime.command;

import cn.managame.runtime.annotation.GameController;
import cn.managame.runtime.annotation.GameMethod;
import cn.managame.runtime.invoke.Invokers;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Startup-built command registry. Registration is atomic per controller. */
public final class CommandRegistry {

    private static final CommandRegistry INSTANCE = new CommandRegistry();

    private final Map<Integer, CommandMeta> commands = new ConcurrentHashMap<>();
    private volatile boolean frozen;

    public static CommandRegistry getInstance() {
        return INSTANCE;
    }

    public synchronized void register(Object controller) {
        if (frozen) {
            throw new IllegalStateException("command registry is frozen");
        }
        Objects.requireNonNull(controller, "controller");
        Class<?> clazz = controller.getClass();
        GameController gameController = clazz.getAnnotation(GameController.class);
        if (gameController == null) {
            throw new IllegalArgumentException(clazz.getName() + " is not annotated with @GameController");
        }

        Map<Integer, CommandMeta> pending = new HashMap<>();
        for (Method method : clazz.getDeclaredMethods()) {
            GameMethod annotation = method.getAnnotation(GameMethod.class);
            if (annotation == null) {
                continue;
            }
            int command = annotation.value();
            CommandMeta existing = commands.get(command);
            if (existing == null) {
                existing = pending.get(command);
            }
            if (existing != null) {
                throw new IllegalStateException("Duplicate command: " + command
                        + " in " + clazz.getName() + "#" + method.getName()
                        + " and " + existing.getMethod().getDeclaringClass().getName()
                        + "#" + existing.getMethod().getName());
            }

            Class<?>[] parameterTypes = method.getParameterTypes();
            validateSignature(clazz, method, parameterTypes);
            Invokers.requireInvokable(method);
            Method routerKeyMethod = resolveRouterKeyMethod(
                    clazz, method, annotation.routerKeyMethod(), parameterTypes[1]);
            pending.put(command, new CommandMeta(command, controller, method,
                    gameController.group(), parameterTypes, routerKeyMethod));
        }

        commands.putAll(pending);
    }

    public CommandMeta getCommandMeta(int command) {
        return commands.get(command);
    }

    /** Prevents accidental runtime mutation after startup registration. */
    public synchronized void freeze() {
        frozen = true;
    }

    public boolean isFrozen() {
        return frozen;
    }

    private static void validateSignature(Class<?> clazz, Method method, Class<?>[] parameterTypes) {
        String methodName = clazz.getName() + "#" + method.getName();
        if (parameterTypes.length != 2) {
            throw new IllegalStateException("@GameMethod method " + methodName
                    + " must have exactly 2 parameters");
        }
        if (method.getReturnType() != void.class) {
            throw new IllegalStateException("@GameMethod method " + methodName + " must return void");
        }
        Class<?> first = parameterTypes[0];
        if (first.isPrimitive() && first != long.class) {
            throw new IllegalStateException("@GameMethod first parameter must be long/Long or a session reference: "
                    + methodName);
        }
        if (parameterTypes[1].isPrimitive()) {
            throw new IllegalStateException("@GameMethod message parameter must be a reference type: " + methodName);
        }
    }

    private static Method resolveRouterKeyMethod(Class<?> clazz, Method method,
                                                  String routerKeyMethodName, Class<?> messageType) {
        if (routerKeyMethodName.isEmpty()) {
            return null;
        }
        Method routerKeyMethod;
        try {
            routerKeyMethod = messageType.getMethod(routerKeyMethodName);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("@GameMethod " + clazz.getName() + "#" + method.getName()
                    + " routerKeyMethod not found: " + messageType.getName()
                    + "#" + routerKeyMethodName + "()", e);
        }
        Class<?> returnType = routerKeyMethod.getReturnType();
        if (returnType != long.class && returnType != Long.class
                && returnType != int.class && returnType != Integer.class) {
            throw new IllegalStateException("@GameMethod " + clazz.getName() + "#" + method.getName()
                    + " routerKeyMethod must return long/int, got: " + returnType.getName());
        }
        return routerKeyMethod;
    }
}
