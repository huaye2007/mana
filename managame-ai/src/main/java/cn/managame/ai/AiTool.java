package cn.managame.ai;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Publishes a method's message contract. The application dispatches the decoded request on its normal route. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AiTool {
    String name();
    String description();
    /**
     * Audiences allowed to discover and invoke this tool (for example {@code "ops"}, {@code "player"}, {@code "agent"}).
     * A catalog built with {@link AiTools.Builder#audience(String)} only includes tools declaring that audience.
     */
    String[] audience() default {};
    /**
     * Explicit output contract type for generating {@code outputSchema}, useful when the handler returns
     * {@code HandleResult} or {@code void}. When omitted, non-void concrete return types are described if supported.
     */
    Class<?> output() default void.class;
    /** When true, {@link AiTools#call} only delegates to {@link AiTools.Entry#propose}. */
    boolean requireApproval() default false;
    /** Hint that this tool only reads state and produces no side effects. */
    boolean readOnly() default false;
    /** Hint that repeating the same arguments produces no additional side effects. */
    boolean idempotent() default false;
}
