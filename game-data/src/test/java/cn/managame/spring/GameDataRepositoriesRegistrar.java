package cn.managame.spring;

import cn.managame.core.repository.GroupRepository;
import cn.managame.core.log.LogRepository;
import cn.managame.core.repository.SingleRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Repository;
import org.springframework.util.ClassUtils;

/** Test/example support only; not part of the published library. Scans metadata at startup; ordinary Spring repository classes are left to component scanning. */
public final class GameDataRepositoriesRegistrar implements ImportBeanDefinitionRegistrar {
    private static final String BINDING = GameDataRepositoriesRegistrar.class.getName() + ".binding";
    private final Environment environment;
    private final ResourceLoader resourceLoader;

    public GameDataRepositoriesRegistrar(Environment environment, ResourceLoader resourceLoader) {
        this.environment = environment;
        this.resourceLoader = resourceLoader;
    }

    @Override public void registerBeanDefinitions(AnnotationMetadata importing, BeanDefinitionRegistry registry) {
        var options = importing.getAnnotations().get(EnableGameDataRepositories.class);
        String owner = options.getString("gameDataRef");
        String access = options.getString("dataAccess");
        if (owner.isBlank() || (!access.isEmpty() && access.isBlank())) {
            throw new BeanDefinitionStoreException("GameData bean name and explicit DataAccess name must not be blank");
        }
        Set<String> packages = new LinkedHashSet<>();
        for (String base : options.getStringArray("basePackages")) {
            packages.add(environment.resolveRequiredPlaceholders(base));
        }
        for (Class<?> marker : options.getClassArray("basePackageClasses")) {
            packages.add(ClassUtils.getPackageName(marker));
        }
        if (packages.isEmpty()) packages.add(ClassUtils.getPackageName(importing.getClassName()));
        if (packages.stream().anyMatch(String::isBlank)) {
            throw new BeanDefinitionStoreException("Repository scanning requires a non-empty base package");
        }
        var scanner = new ClassPathScanningCandidateComponentProvider(false, environment) {
            @Override protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isIndependent() && definition.getMetadata().isInterface();
            }
        };
        scanner.setResourceLoader(resourceLoader);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Repository.class));
        var single = new AssignableTypeFilter(SingleRepository.class);
        var group = new AssignableTypeFilter(GroupRepository.class);
        var log = new AssignableTypeFilter(LogRepository.class);
        scanner.addExcludeFilter((metadata, factory) -> !single.match(metadata, factory)
                && !group.match(metadata, factory) && !log.match(metadata, factory));
        for (String base : packages) {
            for (var candidate : scanner.findCandidateComponents(base)) {
                Class<?> type;
                try { type = ClassUtils.forName(candidate.getBeanClassName(), resourceLoader.getClassLoader()); }
                catch (ClassNotFoundException | LinkageError failure) {
                    throw new BeanDefinitionStoreException("Cannot load repository " + candidate.getBeanClassName(), failure);
                }
                String name = AnnotationBeanNameGenerator.INSTANCE.generateBeanName(candidate, registry);
                var binding = List.of(type, owner, access);
                if (registry.isBeanNameInUse(name)) {
                    if (registry.containsBeanDefinition(name)
                            && binding.equals(registry.getBeanDefinition(name).getAttribute(BINDING))) continue;
                    throw new BeanDefinitionStoreException("Repository bean name already in use: " + name);
                }
                var definition = new RootBeanDefinition(GameDataRepositoryFactoryBean.class);
                definition.getConstructorArgumentValues().addIndexedArgumentValue(0, type);
                definition.getConstructorArgumentValues().addIndexedArgumentValue(1, new RuntimeBeanReference(owner));
                definition.getConstructorArgumentValues().addIndexedArgumentValue(2, access);
                definition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, type);
                definition.setAttribute(BINDING, binding);
                definition.setPrimary(((AnnotatedBeanDefinition) candidate).getMetadata().hasAnnotation(Primary.class.getName()));
                definition.setResourceDescription(candidate.getResourceDescription());
                registry.registerBeanDefinition(name, definition);
            }
        }
    }
}
