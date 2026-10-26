package cn.managame.runtime.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method a Dispatcher calls for messages of its parameter's exact class: {@code (Message)}, or
 * {@code (HandleContext, Message)} to receive the context. A non-null result is the answer; void or null means
 * no answer, or one given later through the context.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface HandleMethod {}
