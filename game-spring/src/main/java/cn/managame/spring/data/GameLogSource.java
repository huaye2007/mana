package cn.managame.spring.data;

import org.springframework.beans.factory.annotation.Qualifier;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the DataSource that holds append-only logs, which is normally a database of its own. Declaring it
 * also stops state from falling back into the log database and the other way round: once either qualifier is
 * present, each store takes only the source named for it.
 */
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface GameLogSource {
}
