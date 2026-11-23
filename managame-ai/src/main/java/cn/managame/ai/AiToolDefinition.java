package cn.managame.ai;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

/**
 * Immutable metadata of one tool registered in {@link AiTools}.
 *
 * @param name         unique tool name
 * @param description  human-readable description (empty string when unset)
 * @param group        logical group (empty string when unset), used by interceptors for visibility or authorization
 * @param readOnly     whether the tool is declared read-only
 * @param method       underlying Java method for {@code @AiTool}-annotated tools, or {@code null} for programmatic tools
 * @param argumentType parameter type decoded from the request payload, or {@code null} for zero-argument tools
 */
public record AiToolDefinition(
        String name,
        String description,
        String group,
        boolean readOnly,
        Method method,
        Type argumentType) {
    public AiToolDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name must not be blank");
        description = description == null ? "" : description;
        group = group == null ? "" : group;
    }
}
