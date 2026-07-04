package cn.managame.jpa.core.repository;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;

/**
 * Repository 类型解析工具。
 * 从参数化的 Repository 接口中提取实体类型（第一个泛型参数）。
 */
public final class RepositoryTypes {

    private RepositoryTypes() {}

    /**
     * 从 repositoryType 的泛型接口中解析实体类型。
     * 遍历 repositoryType 实现的所有参数化接口，找到 rawType 匹配 baseInterface 的接口，
     * 取其第一个泛型参数作为实体类型。
     *
     * @param repositoryType Repository 接口的 Class
     * @param baseInterface  基础 Repository 接口（如 RdbRepository.class）
     * @return 实体类型的 Class
     * @throws IllegalArgumentException 无法解析时抛出
     */
    public static Class<?> resolveEntityType(Class<?> repositoryType, Class<?> baseInterface) {
        Class<?> resolved = resolveEntityType(repositoryType, baseInterface, Map.of());
        if (resolved != null) {
            return resolved;
        }
        throw new IllegalArgumentException(
                "Cannot resolve entity type from: " + repositoryType.getName());
    }

    private static Class<?> resolveEntityType(Type type,
                                              Class<?> baseInterface,
                                              Map<TypeVariable<?>, Type> variables) {
        if (type instanceof ParameterizedType pt) {
            Type rawType = pt.getRawType();
            if (!(rawType instanceof Class<?> rawClass)) {
                return null;
            }

            Map<TypeVariable<?>, Type> nextVariables = bindVariables(rawClass, pt, variables);
            if (rawClass == baseInterface) {
                Class<?> entityType = toClass(resolveType(pt.getActualTypeArguments()[0], variables), nextVariables);
                if (entityType != null) {
                    return entityType;
                }
            }
            return resolveFromClass(rawClass, baseInterface, nextVariables);
        }

        if (type instanceof Class<?> cls) {
            return resolveFromClass(cls, baseInterface, variables);
        }
        return null;
    }

    private static Class<?> resolveFromClass(Class<?> type,
                                             Class<?> baseInterface,
                                             Map<TypeVariable<?>, Type> variables) {
        for (Type iface : type.getGenericInterfaces()) {
            Class<?> resolved = resolveEntityType(iface, baseInterface, variables);
            if (resolved != null) {
                return resolved;
            }
        }

        Type superclass = type.getGenericSuperclass();
        if (superclass != null && superclass != Object.class) {
            return resolveEntityType(superclass, baseInterface, variables);
        }
        return null;
    }

    private static Map<TypeVariable<?>, Type> bindVariables(Class<?> rawClass,
                                                            ParameterizedType parameterizedType,
                                                            Map<TypeVariable<?>, Type> inherited) {
        Map<TypeVariable<?>, Type> result = new HashMap<>(inherited);
        TypeVariable<?>[] parameters = rawClass.getTypeParameters();
        Type[] arguments = parameterizedType.getActualTypeArguments();
        for (int i = 0; i < parameters.length && i < arguments.length; i++) {
            result.put(parameters[i], resolveType(arguments[i], inherited));
        }
        return result;
    }

    private static Type resolveType(Type type, Map<TypeVariable<?>, Type> variables) {
        Type current = type;
        while (current instanceof TypeVariable<?> variable && variables.containsKey(variable)) {
            Type resolved = variables.get(variable);
            if (resolved == current) {
                break;
            }
            current = resolved;
        }
        return current;
    }

    private static Class<?> toClass(Type type, Map<TypeVariable<?>, Type> variables) {
        Type resolved = resolveType(type, variables);
        if (resolved instanceof Class<?> cls) {
            return cls;
        }
        if (resolved instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> cls) {
            return cls;
        }
        return null;
    }
}
