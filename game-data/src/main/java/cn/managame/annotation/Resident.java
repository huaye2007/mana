package cn.managame.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Loads the complete table/collection into memory when the repository is initialized.
 * Loading is all-or-fail: an @Resident repository is not usable until the complete scan succeeds.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Resident {
}
