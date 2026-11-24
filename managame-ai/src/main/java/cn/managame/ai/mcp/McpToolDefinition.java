package cn.managame.ai.mcp;

import java.util.Objects;

/**
 * Immutable metadata of a registered MCP tool, as exposed by MCP {@code tools/list} and passed to
 * {@link McpToolInterceptor}. {@code inputSchema} is a pre-built JSON Schema object string.
 */
public record McpToolDefinition(String name, String description, String group,
        boolean readOnly, boolean idempotent, String inputSchema) {
    public McpToolDefinition(String name, String description, String group, boolean readOnly, String inputSchema) {
        this(name, description, group, readOnly, false, inputSchema);
    }

    public McpToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(inputSchema, "inputSchema");
        if (name.isBlank() || name.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Invalid tool name: '" + name + "'");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("Tool '" + name + "' requires a non-blank description");
        }
        if (inputSchema.isBlank()) {
            throw new IllegalArgumentException("Tool '" + name + "' requires a non-blank inputSchema");
        }
    }
}
