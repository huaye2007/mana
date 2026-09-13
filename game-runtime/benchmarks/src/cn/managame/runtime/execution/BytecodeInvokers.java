package cn.managame.runtime.execution;

import cn.managame.runtime.context.CommandHandlerInvocation;

import java.lang.classfile.*;
import java.lang.constant.*;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;
import static cn.managame.runtime.execution.HandlerBindings.*;

/** Benchmark-only bytecode emitter. Not included in the runtime artifact. */
public final class BytecodeInvokers {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final ClassDesc INVOCATION = desc(CommandHandlerInvocation.class);
    private static final ClassDesc INVOKER = desc(HandlerInvokers.CommandInvoker.class);
    private static final ClassDesc FUNCTION = desc(Function.class);
    private static final MethodTypeDesc CALL = MethodTypeDesc.of(CD_void, INVOCATION, CD_Object);

    static ClassDesc desc(Class<?> type) { return ClassDesc.ofDescriptor(type.descriptorString()); }

    static HandlerInvokers.CommandInvoker command(Object bean, Method method,
            List<ArgumentSlot> slots, int[] arguments, int routeSlot) {
        if (method.getReturnType() != void.class || java.lang.reflect.Modifier.isStatic(method.getModifiers()))
            throw new IllegalArgumentException("Only instance void methods are supported by this experiment");
        ClassDesc self = ClassDesc.of("cn.managame.runtime.execution.GeneratedCommand" + IDS.incrementAndGet());
        ClassDesc owner = desc(method.getDeclaringClass());
        Class<?>[] parameters = method.getParameterTypes();
        // Same slot identity, same first-use order and exactly-once resolution as HandlerInvokers.
        Map<Integer, Integer> locals = new LinkedHashMap<>();
        for (int slot : arguments) if (slot != routeSlot) locals.computeIfAbsent(slot, ignored -> 3 + locals.size());
        byte[] bytes = ClassFile.of().build(self, cb -> {
            cb.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_SUPER).withSuperclass(CD_Object).withInterfaceSymbols(INVOKER);
            cb.withField("bean", owner, ACC_PRIVATE | ACC_FINAL);
            for (int slot : locals.keySet()) cb.withField("resolver" + slot, FUNCTION, ACC_PRIVATE | ACC_FINAL);
            cb.withMethodBody("<init>", MethodTypeDesc.of(CD_void, CD_Object, FUNCTION.arrayType()), ACC_PUBLIC, code -> {
                code.aload(0).invokespecial(CD_Object, "<init>", MethodTypeDesc.of(CD_void));
                code.aload(0).aload(1).checkcast(owner).putfield(self, "bean", owner);
                for (int slot : locals.keySet())
                    code.aload(0).aload(2).loadConstant(slot).aaload().putfield(self, "resolver" + slot, FUNCTION);
                code.return_();
            });
            cb.withMethodBody("invoke", CALL, ACC_PUBLIC, code -> {
                for (var entry : locals.entrySet()) {
                    code.aload(0).getfield(self, "resolver" + entry.getKey(), FUNCTION).aload(1)
                            .invokeinterface(FUNCTION, "apply", MethodTypeDesc.of(CD_Object, CD_Object))
                            .astore(entry.getValue());
                }
                code.aload(0).getfield(self, "bean", owner);
                for (int i = 0; i < arguments.length; i++) {
                    code.aload(arguments[i] == routeSlot ? 2 : locals.get(arguments[i]));
                    cast(code, parameters[i]);
                }
                MethodTypeDesc signature = MethodTypeDesc.of(CD_void, Arrays.stream(parameters)
                        .map(BytecodeInvokers::desc).toArray(ClassDesc[]::new));
                if (method.getDeclaringClass().isInterface()) code.invokeinterface(owner, method.getName(), signature);
                else code.invokevirtual(owner, method.getName(), signature);
                code.return_();
            });
        });
        try {
            String dump = System.getProperty("bytecode.dump");
            if (dump != null) {
                Path file = Path.of(dump, self.displayName() + ".class");
                Files.createDirectories(file.getParent());
                Files.write(file, bytes);
            }
            var lookup = MethodHandles.lookup().defineHiddenClass(bytes, true, MethodHandles.Lookup.ClassOption.NESTMATE);
            Function<?, ?>[] resolvers = slots.stream().map(ArgumentSlot::resolver).toArray(Function[]::new);
            // Construction is outside measurement. The generated invoke method contains no MethodHandle call.
            return (HandlerInvokers.CommandInvoker) lookup.findConstructor(lookup.lookupClass(),
                    MethodType.methodType(void.class, Object.class, Function[].class))
                    .invoke(bean, resolvers);
        } catch (Throwable error) { throw new IllegalArgumentException("Cannot generate invoker for " + method, error); }
    }

    private static void cast(CodeBuilder code, Class<?> type) {
        if (!type.isPrimitive()) { code.checkcast(desc(type)); return; }
        Class<?> boxed = HandlerBindings.boxed(type);
        String unbox = type.getName() + "Value";
        if (type == char.class) unbox = "charValue";
        code.checkcast(desc(boxed)).invokevirtual(desc(boxed), unbox, MethodTypeDesc.of(desc(type)));
    }
}
