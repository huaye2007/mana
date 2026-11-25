package cn.managame.ai.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a game server method as an MCP (Model Context Protocol) tool when its declaring object is registered with
 * {@link cn.managame.ai.mcp.McpTools}. Can be added directly to existing business methods (such as service methods or
 * HTTP handlers):
 *
 * <pre>{@code
 * @McpTool(name = "query_player_bag", description = "Query items in a player's bag",
 *         group = "npc", readOnly = true, routeKey = "roleId")
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

    /**
     * Marks a mutating tool as destructive (for example banning a player or deleting items): sets MCP
     * {@code annotations.destructiveHint=true} so agent hosts can gate execution on human confirmation. Defaults to
     * {@code false} (additive / bounded game operations). Ignored when {@link #readOnly()} is {@code true}.
     */
    boolean destructive() default false;

    /**
     * Marks a mutating tool as safe to retry with the same arguments (for example when guarded by a request id): sets
     * MCP {@code annotations.idempotentHint=true}. A mutating tool that is not idempotent and times out after it
     * started is answered with a text telling the model its effect is unknown, so it checks before calling again.
     */
    boolean idempotent() default false;

    /**
     * Names the argument holding the key of the route this tool runs on, for a {@link cn.managame.ai.spi.McpToolExecutor}
     * that dispatches tools onto player/entity routes (such as {@code GameRuntime} or an actor system): a parameter of
     * the method, or a member of its single DTO (a no-argument method of that name, its getter or a field). It must be
     * an integral type or its box. Compiled at registration, so a missing member fails at startup and a call pays one
     * read; the executor reads it from {@link cn.managame.ai.mcp.McpToolCall#routeKey()}. Empty (the default) names
     * none.
     */
    String routeKey() default "";
}
