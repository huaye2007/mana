package cn.managame.ai.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a game server method exposed to external AI services through {@link cn.managame.ai.AiTools}.
 *
 * <p>A tool method takes either no parameter or one argument (typically a record, POJO or raw {@link String}),
 * optionally preceded by {@link cn.managame.ai.AiToolContext}. It returns the result directly (any DTO or
 * {@link cn.managame.ai.AiToolResult}), or is {@code void} when it takes {@link cn.managame.ai.AiToolContext} and
 * answers asynchronously via {@code reply} / {@code fail}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AiTool {
    /** Tool name exposed to callers. Defaults to the Java method name when blank. */
    String name() default "";

    /** Optional human-readable description of what the tool does. */
    String description() default "";

    /** Logical group (for example {@code "npc"} or {@code "gm"}) used by interceptors to filter or authorize access. */
    String group() default "";

    /** Marks that the tool does not mutate game state, for interceptors and auditing. */
    boolean readOnly() default false;
}
