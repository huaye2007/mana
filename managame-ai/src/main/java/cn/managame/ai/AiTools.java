package cn.managame.ai;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;

/**
 * Immutable tool catalog for one audience, built from handler instances or contract types (interfaces/classes).
 * No handler instance is invoked or retained: every call decodes untrusted AI arguments and delegates to a trusted
 * application {@link Entry} together with the authenticated caller context.
 */
public final class AiTools {
    /**
     * Wire-serializable tool contract descriptor (holds no {@link Class} references), suitable for game servers
     * to export and register dynamically with standalone AI or MCP gateway processes over RPC or HTTP.
     */
    public record Descriptor(String name, String description, List<String> audiences,
                             String inputSchema, String outputSchema,
                             boolean requireApproval, boolean readOnly, boolean idempotent) {
        public Descriptor {
            audiences = List.copyOf(audiences);
        }
    }

    /** External tool metadata computed once at registration. The target message is the method's last parameter. */
    public record Tool(String name, String description, List<String> audiences, Class<?> messageType,
                       String inputSchema, String outputSchema,
                       boolean requireApproval, boolean readOnly, boolean idempotent) {
        public Tool {
            audiences = List.copyOf(audiences);
        }

        /** Pure wire-serializable view of this tool without its Java message class. */
        public Descriptor descriptor() {
            return new Descriptor(name, description, audiences, inputSchema, outputSchema,
                    requireApproval, readOnly, idempotent);
        }
    }

    /**
     * Trusted application entry receiving the authenticated {@code caller} separately from decoded AI arguments.
     * Implementations are typically stateless singletons dispatching onto {@code GameRuntime}.
     */
    public interface Entry<C> {
        CompletionStage<?> dispatch(C caller, Tool tool, Object message);
        CompletionStage<?> propose(C caller, Tool tool, Object message);
    }

    private final String audience;
    private final Map<String, Tool> tools;
    private final List<Descriptor> descriptors;
    private final AiJson json;

    private AiTools(Builder builder) {
        audience = builder.audience;
        json = builder.json;
        Map<String, Tool> found = new TreeMap<>();
        for (Class<?> type : builder.types) {
            for (Method method : type.getMethods()) {
                AiTool annotation = method.getAnnotation(AiTool.class);
                if (annotation == null || method.isBridge() || method.isSynthetic()) continue;
                int count = method.getParameterCount();
                if (Modifier.isStatic(method.getModifiers()) || (count != 1 && count != 2)
                        || !annotation.name().matches("[A-Za-z0-9_.-]{1,128}") || annotation.description().isBlank())
                    throw new IllegalArgumentException("Invalid AI tool: " + method);
                List<String> audiences = parseAudiences(annotation.audience(), method);
                if (audience != null && !audiences.contains(audience)) continue;
                Class<?> messageType = method.getParameterTypes()[count - 1];
                String inputSchema = json.inputSchema(messageType);
                String outputSchema = resolveOutputSchema(annotation, method);
                Tool tool = new Tool(annotation.name(), annotation.description(), audiences, messageType,
                        inputSchema, outputSchema, annotation.requireApproval(), annotation.readOnly(), annotation.idempotent());
                if (found.putIfAbsent(tool.name(), tool) != null)
                    throw new IllegalArgumentException("Duplicate AI tool: " + tool.name());
            }
        }
        tools = Collections.unmodifiableMap(new LinkedHashMap<>(found));
        descriptors = found.values().stream().map(Tool::descriptor).toList();
    }

    private static List<String> parseAudiences(String[] raw, Method method) {
        if (raw.length == 0) return List.of();
        List<String> list = new ArrayList<>(raw.length);
        for (String item : raw) {
            if (item == null || item.isBlank()) throw new IllegalArgumentException("Blank audience on AI tool: " + method);
            if (!list.contains(item)) list.add(item);
        }
        return List.copyOf(list);
    }

    private String resolveOutputSchema(AiTool annotation, Method method) {
        Class<?> declared = annotation.output();
        if (declared != void.class) {
            String schema = json.outputSchema(declared);
            if (schema == null) throw new IllegalArgumentException("Unsupported AI tool output type: " + declared);
            return schema;
        }
        Class<?> returned = method.getReturnType();
        if (returned == void.class || returned == Void.class
                || CompletionStage.class.isAssignableFrom(returned)
                || returned.getName().equals("cn.managame.runtime.dispatch.HandleResult")) {
            return null;
        }
        return json.outputSchema(returned);
    }

    public static Builder builder() { return new Builder(); }

    /** The audience filter configured for this catalog, or {@code null} when unfiltered. */
    public String audience() { return audience; }

    public Collection<Tool> tools() { return tools.values(); }

    /** Wire-serializable tool descriptors for reporting this catalog to remote AI/MCP gateways. */
    public List<Descriptor> descriptors() { return descriptors; }

    public Tool tool(String name) { return tools.get(name); }

    /** Encodes a tool result (or {@link #descriptors()}) using this catalog's {@link AiJson} codec. */
    public String encodeResult(Object result) { return json.encode(result); }

    /**
     * Decode JSON string arguments then dispatch or propose with the trusted {@code caller}.
     * Approval-required tools only reach {@link Entry#propose}. Invalid arguments fail before either entry.
     */
    public <C> CompletionStage<?> call(String name, String arguments, C caller, Entry<C> entry) {
        Objects.requireNonNull(entry, "entry");
        Tool tool = requireTool(name);
        Object message = json.decode(arguments, tool.messageType());
        return route(caller, tool, message, entry);
    }

    /** Decode structured map arguments (for example from MCP) without re-encoding to JSON string. */
    public <C> CompletionStage<?> call(String name, Map<String, ?> arguments, C caller, Entry<C> entry) {
        Objects.requireNonNull(entry, "entry");
        Tool tool = requireTool(name);
        Object message = json.decode(arguments, tool.messageType());
        return route(caller, tool, message, entry);
    }

    private Tool requireTool(String name) {
        Tool tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("Unknown AI tool: " + name);
        return tool;
    }

    private static <C> CompletionStage<?> route(C caller, Tool tool, Object message, Entry<C> entry) {
        return tool.requireApproval() ? entry.propose(caller, tool, message) : entry.dispatch(caller, tool, message);
    }

    public static final class Builder {
        private final List<Class<?>> types = new ArrayList<>();
        private AiJson json = AiJson.jackson();
        private String audience;

        /** Restrict this catalog to {@link AiTool} methods declaring {@code audience}. */
        public Builder audience(String audience) {
            if (audience == null || audience.isBlank()) throw new IllegalArgumentException("Blank audience");
            this.audience = audience;
            return this;
        }

        public Builder json(AiJson json) { this.json = Objects.requireNonNull(json, "json"); return this; }

        /** Register handler instances (only their classes are inspected; instances are not retained). */
        public Builder register(Collection<?> handlers) {
            for (Object handler : handlers) types.add(Objects.requireNonNull(handler, "handler").getClass());
            return this;
        }

        /** Register contract interfaces or classes directly, without instantiating handlers. */
        public Builder registerTypes(Collection<Class<?>> contractTypes) {
            for (Class<?> type : contractTypes) types.add(Objects.requireNonNull(type, "type"));
            return this;
        }

        public AiTools build() { return new AiTools(this); }
    }
}
