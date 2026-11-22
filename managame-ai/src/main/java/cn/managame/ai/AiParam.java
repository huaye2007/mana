package cn.managame.ai;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a property of an {@link AiTool} message contract for generated JSON Schema.
 * Record components are required by default unless marked {@code required = false}; ordinary bean properties
 * are required when primitive or annotated with {@code @AiParam(required = true)} / {@code @JsonProperty(required = true)}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface AiParam {
    /** Description placed in the property's JSON Schema. */
    String value() default "";

    /** Whether the property is listed under {@code required} in the JSON Schema. */
    boolean required() default true;
}
