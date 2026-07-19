package cn.managame.jpa.core.bootstrap;

/**
 * A composable game-jpa capability applied while building the persistence context.
 *
 * <p>Extensions may contribute metadata resolvers, repository factories, lifecycle
 * hooks, or backend components without coupling the neutral starter to a concrete
 * database implementation.</p>
 */
@FunctionalInterface
public interface GameJpaExtension {

    void configure(PersistenceConfigurer configurer);
}
