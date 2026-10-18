package cn.managame.spring.data;

import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the DataSource that holds game state. Needed only when the application also declares a
 * {@link GameLogSource}, or when several DataSource Beans exist; a single unannotated DataSource serves both
 * stores.
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface GameStateSource {
}
