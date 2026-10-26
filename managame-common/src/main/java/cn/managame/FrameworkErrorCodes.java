package cn.managame;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/** Shared framework error codes (OGBS Core). Zero is success; every failure code is positive. */
public final class FrameworkErrorCodes {
    public static final int SUCCESS = 0;

    // RPC 2001..2999
    public static final int RPC_PEER_NOT_FOUND = 2001;
    public static final int RPC_UNAVAILABLE = 2002;
    public static final int RPC_TIMEOUT = 2003;
    public static final int RPC_PEER_REMOVED = 2004;
    public static final int RPC_NODE_CLOSED = 2005;
    public static final int RPC_HANDLER_ERROR = 2006;
    public static final int RPC_PROTOCOL_ERROR = 2007;

    // Runtime 3001..3999
    public static final int RUNTIME_CLOSED = 3001;
    public static final int HANDLER_NOT_FOUND = 3002;
    public static final int HANDLER_CONTEXT_MISMATCH = 3003;
    public static final int ROUTE_DOMAIN_MISMATCH = 3004;
    public static final int INVALID_ROUTE_KEY = 3005;
    public static final int ROUTE_EXECUTOR_OVERLOADED = 3006;
    public static final int ROUTE_EXECUTOR_CLOSED = 3007;
    public static final int ROUTE_CALL_EXECUTION_ERROR = 3008;
    public static final int RUNTIME_EXECUTION_ERROR = 3009;
    public static final int ROUTE_CALLBACK_DISPATCH_FAILED = 3010;

    // Data 4001..4999
    public static final int DATA_SAVE_FAILED = 4001;
    public static final int DATA_LOG_SAVE_FAILED = 4002;

    private static final Map<Integer, String> NAMES = names();

    private FrameworkErrorCodes() {}

    /**
     * Constant name of a framework code, for logs and diagnostics; {@code "UNKNOWN(<code>)"} for an
     * application-defined or unallocated code. Never use the name for program logic.
     */
    public static String name(int code) {
        String name = NAMES.get(code);
        return name != null ? name : "UNKNOWN(" + code + ")";
    }

    /** Name and number, for example {@code "RPC_TIMEOUT(2003)"}. */
    public static String describe(int code) {
        String name = NAMES.get(code);
        return name != null ? name + "(" + code + ")" : "UNKNOWN(" + code + ")";
    }

    private static Map<Integer, String> names() {
        Map<Integer, String> names = new HashMap<>();
        for (Field field : FrameworkErrorCodes.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != int.class || !Modifier.isStatic(modifiers) || !Modifier.isPublic(modifiers)) continue;
            try {
                names.put(field.getInt(null), field.getName());
            } catch (IllegalAccessException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return Map.copyOf(names);
    }
}
