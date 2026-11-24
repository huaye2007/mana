package cn.managame.ai.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a game server method as an MCP (Model Context Protocol) tool when its declaring object is registered with
 * {@link cn.managame.ai.mcp.McpTools}. Can be added directly to existing business methods (such as service methods or
 * {@code @HttpMethod} handlers):
 *
 * <pre>{@code
 * @McpTool(name = "query_player_bag", description = "Query items in a player's bag", group = "npc", readOnly = true)
 * public BagView queryBag(@McpParam("Player role id") long roleId,
 *         @McpParam(value = "Optional item category", required = false) Integer category) { ... }
 * }</pre>
 *
 * <p>Supported signatures: zero arguments, a single DTO record/POJO (whose fields become top-level JSON Schema
 * properties), or one or more parameters (named via {@link McpParam#name()} or {@code -parameters}), each optionally
 * preceded by {@link cn.managame.ai.mcp.McpToolContext}. A {@code void} method taking {@code McpToolContext} answers
 * asynchronously via {@code context.reply} or {@code context.fail}; a {@code void} method without it answers
 * synchronously with {@code "ok"}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpTool {
    /** Tool name exposed in MCP {@code tools/list} and {@code tools/call}; defaults to the Java method name. */
    String name() default "";

    /** Human- and model-readable description of what the tool does and when to call it. Required and non-blank. */
    String description();

    /** Optional logical group (for example {@code "npc"}, {@code "gm"}, {@code "ops"}) used by interceptors. */
    String group() default "";

    /** Marks the tool as non-mutating: sets MCP {@code annotations.readOnlyHint=true} and is visible to interceptors. */
    boolean readOnly() default false;
}
