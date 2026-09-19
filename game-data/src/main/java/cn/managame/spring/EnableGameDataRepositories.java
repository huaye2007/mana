package cn.managame.spring;

import java.lang.annotation.*;
import org.springframework.context.annotation.Import;

/** Registers Spring @Repository business interfaces as singleton beans. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(GameDataRepositoriesRegistrar.class)
public @interface EnableGameDataRepositories {
    /** Packages to scan; defaults to the importing configuration's package. */
    String[] basePackages() default {};
    /** Type-safe package markers, combined with basePackages. */
    Class<?>[] basePackageClasses() default {};
    /** Spring bean name of the GameData lifecycle owner. */
    String gameDataRef() default "gameData";
    /** Named DataAccess inside GameData; empty selects its default. */
    String dataAccess() default "";
}
