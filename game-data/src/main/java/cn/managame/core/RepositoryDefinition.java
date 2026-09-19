package cn.managame.core;

import cn.managame.core.repository.GroupRepository;
import cn.managame.core.metadata.MetadataRegistry;
import cn.managame.core.log.LogRepository;
import cn.managame.core.repository.SingleRepository;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.*;

/** Resolves business interfaces once; CRUD calls dispatch directly to the shared implementation. */
final class RepositoryDefinition {
    enum Mode { SINGLE, GROUP, LOG }
    private static final Object[] NO_ARGUMENTS = new Object[0];
    private final Mode mode;
    private final Class<?> entityType;

    private RepositoryDefinition(Mode mode, Class<?> entityType) {
        this.mode = mode;
        this.entityType = entityType;
    }

    Mode mode() { return mode; }
    Class<?> entityType() { return entityType; }

    static RepositoryDefinition inspect(Class<?> repositoryType, MetadataRegistry registry) {
        Objects.requireNonNull(repositoryType, "repositoryType");
        if (!repositoryType.isInterface() || repositoryType.isSealed()) {
            throw new DataException("Repository must be a non-sealed business interface: " + repositoryType.getName());
        }
        Map<Class<?>, Map<TypeVariable<?>, Type>> bindings = new HashMap<>();
        resolveInterfaces(repositoryType, Map.of(), bindings);
        boolean single = bindings.containsKey(SingleRepository.class);
        boolean group = bindings.containsKey(GroupRepository.class);
        boolean log = bindings.containsKey(LogRepository.class);
        if ((single ? 1 : 0) + (group ? 1 : 0) + (log ? 1 : 0) != 1) {
            throw new DataException("Repository must extend exactly one of SingleRepository, GroupRepository or LogRepository: "
                    + repositoryType.getName());
        }
        Class<?> base = single ? SingleRepository.class : group ? GroupRepository.class : LogRepository.class;
        Map<TypeVariable<?>, Type> baseBindings = bindings.get(base);
        for (TypeVariable<?> variable : base.getTypeParameters()) {
            concreteClass(resolve(variable, baseBindings));
        }
        Class<?> entity = concreteClass(resolve(base.getTypeParameters()[0], baseBindings));
        if (!log) {
            Class<?> declaredId = concreteClass(resolve(base.getTypeParameters()[1], baseBindings));
            Class<?> actualId = MethodType.methodType(registry.get(entity).idProperty().type()).wrap().returnType();
            if (declaredId != actualId) {
                throw new DataException("Repository ID type must match @Id: repository=" + repositoryType.getName()
                        + ", declared=" + declaredId.getName() + ", expected=" + actualId.getName());
            }
        }
        Map<String, Method> operations = new HashMap<>();
        for (Method method : base.getMethods()) operations.put(method.getName(), method);
        for (Method method : repositoryType.getMethods()) {
            if (!Modifier.isAbstract(method.getModifiers()) || isObjectMethod(method)) continue;
            Method operation = operations.get(method.getName());
            if (operation == null || operation.getParameterCount() != method.getParameterCount()) {
                throw unsupported(method);
            }
            Map<TypeVariable<?>, Type> environment = bindings.get(method.getDeclaringClass());
            if (resolvedClass(method.getGenericReturnType(), environment)
                    != resolvedClass(operation.getGenericReturnType(), baseBindings)) throw unsupported(method);
            for (int i = 0; i < method.getParameterCount(); i++) {
                if (resolvedClass(method.getGenericParameterTypes()[i], environment)
                        != resolvedClass(operation.getGenericParameterTypes()[i], baseBindings)) throw unsupported(method);
            }
        }
        return new RepositoryDefinition(single ? Mode.SINGLE : group ? Mode.GROUP : Mode.LOG, entity);
    }

    private static DataException unsupported(Method method) {
        return new DataException("Unsupported abstract repository method; implement business helpers as default methods: "
                + method);
    }

    private static void resolveInterfaces(Type type, Map<TypeVariable<?>, Type> inherited,
                                          Map<Class<?>, Map<TypeVariable<?>, Type>> result) {
        Class<?> raw;
        Map<TypeVariable<?>, Type> bindings = new HashMap<>(inherited);
        if (type instanceof ParameterizedType parameterized) {
            raw = (Class<?>) parameterized.getRawType();
            TypeVariable<?>[] variables = raw.getTypeParameters();
            Type[] arguments = parameterized.getActualTypeArguments();
            for (int i = 0; i < variables.length; i++) bindings.put(variables[i], resolve(arguments[i], inherited));
        } else if (type instanceof Class<?> clazz) {
            raw = clazz;
        } else {
            throw new DataException("Unsupported repository type: " + type);
        }
        result.put(raw, bindings);
        for (Type parent : raw.getGenericInterfaces()) resolveInterfaces(parent, bindings, result);
    }

    private static Type resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
        Set<Type> visited = new HashSet<>();
        while (type instanceof TypeVariable<?> && bindings.containsKey(type) && visited.add(type)) {
            type = bindings.get(type);
        }
        return type;
    }

    private static Class<?> concreteClass(Type type) {
        if (type instanceof Class<?> clazz) return clazz;
        throw new DataException("Repository entity and key types must be concrete classes: " + type);
    }

    private static Class<?> resolvedClass(Type type, Map<TypeVariable<?>, Type> bindings) {
        Type resolved = resolve(type, bindings);
        if (resolved instanceof ParameterizedType parameterized) return (Class<?>) parameterized.getRawType();
        return concreteClass(resolved);
    }

    private static boolean isObjectMethod(Method method) {
        return (method.getName().equals("equals") && Arrays.equals(method.getParameterTypes(), new Class<?>[]{Object.class})
                && method.getReturnType() == boolean.class)
                || (method.getName().equals("hashCode") && method.getParameterCount() == 0 && method.getReturnType() == int.class)
                || (method.getName().equals("toString") && method.getParameterCount() == 0 && method.getReturnType() == String.class);
    }

    @SuppressWarnings("unchecked")
    <R> R create(Class<R> type, Object delegate) {
        SingleRepository<Object, Object> single = mode == Mode.SINGLE ? (SingleRepository<Object, Object>) delegate : null;
        GroupRepository<Object, Object> group = mode == Mode.GROUP ? (GroupRepository<Object, Object>) delegate : null;
        LogRepository<Object> log = mode == Mode.LOG ? (LogRepository<Object>) delegate : null;
        Map<Method, MethodHandle> defaults = new HashMap<>();
        for (Method method : type.getMethods()) {
            if (!method.isDefault()) continue;
            try {
                MethodHandle handle = MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup())
                        .unreflectSpecial(method, method.getDeclaringClass())
                        .asSpreader(Object[].class, method.getParameterCount())
                        .asType(MethodType.methodType(Object.class, Object.class, Object[].class));
                defaults.put(method, handle);
            } catch (IllegalAccessException e) {
                throw new DataException("Cannot bind repository default method: " + method, e);
            }
        }
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (isObjectMethod(method)) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> type.getName() + "[" + mode + ", entity=" + entityType.getName() + "]";
                };
            }
            MethodHandle defaultMethod = defaults.get(method);
            if (defaultMethod != null) {
                Object[] arguments = args == null ? NO_ARGUMENTS : args;
                return (Object) defaultMethod.invokeExact(proxy, arguments);
            }
            if (log != null) {
                return switch (method.getName()) {
                    case "append" -> { log.append(args[0]); yield null; }
                    case "flush" -> { log.flush(); yield null; }
                    default -> throw unsupported(method);
                };
            }
            if (single != null) {
                return switch (method.getName()) {
                    case "get" -> single.get(args[0]);
                    case "insert" -> { single.insert(args[0]); yield null; }
                    case "update" -> { single.update(args[0]); yield null; }
                    case "delete" -> { single.delete(args[0]); yield null; }
                    case "deleteById" -> { single.deleteById(args[0]); yield null; }
                    case "resident" -> single.resident();
                    default -> throw unsupported(method);
                };
            }
            return switch (method.getName()) {
                case "getGroup" -> group.getGroup(args[0]);
                case "insert" -> { group.insert(args[0]); yield null; }
                case "update" -> { group.update(args[0]); yield null; }
                case "delete" -> { group.delete(args[0]); yield null; }
                case "deleteGroup" -> { group.deleteGroup(args[0]); yield null; }
                case "resident" -> group.resident();
                default -> throw unsupported(method);
            };
        }));
    }
}
