package cn.managame.ai.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a parameter of an {@link McpTool} method, or a record component / field of its DTO argument, for the
 * generated MCP JSON Schema ({@code inputSchema}).
 */
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface McpParam {
    /** Description shown to the model in JSON Schema {@code description}. */
    String value() default "";

    /** Overrides the JSON property name; defaults to the parameter, record component, or field name. */
    String name() default "";

    /** Whether the property is required; primitive parameters and fields are always required. */
    boolean required() default true;
}
