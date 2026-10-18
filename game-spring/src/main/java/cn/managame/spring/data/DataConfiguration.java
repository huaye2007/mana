package cn.managame.spring.data;

import cn.managame.data.GameData;
import cn.managame.data.GameDataBuilder;
import cn.managame.data.GroupRepository;
import cn.managame.data.LogRepository;
import cn.managame.data.SingleRepository;
import cn.managame.data.mapper.EntityMapper;
import cn.managame.spring.lifecycle.ShutdownBudget;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

@Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Import(cn.managame.spring.lifecycle.ShutdownConfiguration.class)
class DataConfiguration {
    private record Binding(String beanName, Class<?> type, boolean log) {}

    @Bean
    public static BeanFactoryPostProcessor dataRepositoryBeans() {
        return beans -> {
            for (Binding binding : bindings(beans)) {
                var definition = (AbstractBeanDefinition) beans.getBeanDefinition(binding.beanName());
                if (!definition.isSingleton()) {
                    throw new IllegalArgumentException("Data Repository must be singleton: " + binding.beanName());
                }
                // Keep scanned names/qualifiers and Spring injection, but use Data's initialized instance.
                definition.setInstanceSupplier(() -> beans.getBean(GameData.class).repository(binding.type()));
                String[] dependencies = definition.getDependsOn();
                definition.setDependsOn(Stream.concat(
                        dependencies == null ? Stream.empty() : Arrays.stream(dependencies),
                        Stream.of("gameData")).distinct().toArray(String[]::new));
            }
        };
    }

    @Bean(destroyMethod = "close")
    public GameData gameData(ConfigurableListableBeanFactory beans,
                             ObjectProvider<EntityMapper> stateMapper,
                             ObjectProvider<GameDataConfigurer> configurers,
                             ObjectProvider<ShutdownBudget> budget) {
        var builder = GameDataBuilder.builder();
        Sources sources = sources(beans);
        if (sources.state() != null) builder.mysql(sources.state());
        if (sources.logs() != null) builder.logMysql(sources.logs());
        // A state Mapper Bean, such as a MongoEntityMapper, decides the state backend; logs stay on MySQL per
        // D-LOG-02. Missing backends are not reported here: a configurer below may still supply one, so the
        // builder is left to reject what is still unbound, naming the methods that would bind it.
        EntityMapper mapper = stateMapper.getIfAvailable();
        for (Binding binding : bindings(beans)) {
            if (binding.log()) builder.logRepositories(binding.type());
            else if (mapper != null) builder.repositories(mapper, binding.type());
            else builder.repositories(binding.type());
        }
        // The final persistence pass gets the slice the shutdown budget reserved for it. Applied before the
        // application's configurers, so one that knows its storage better can still override it.
        ShutdownBudget declared = budget.getIfAvailable();
        if (declared != null) builder.shutdownTimeout(declared.flushReserve());
        configurers.orderedStream().forEach(configurer -> configurer.configure(builder));
        return builder.build();
    }

    private record Sources(DataSource state, DataSource logs) {}

    /**
     * State and logs are separate stores. A single unannotated DataSource serves both, which is one database;
     * as soon as either qualifier is present the application has shown it distinguishes them, so each store
     * takes only the source named for it and silence can never put logs in the state database.
     */
    private static Sources sources(ConfigurableListableBeanFactory beans) {
        List<String> state = qualified(beans, GameStateSource.class);
        List<String> logs = qualified(beans, GameLogSource.class);
        for (String name : state) {
            if (logs.contains(name)) {
                throw new IllegalArgumentException("DataSource Bean is both @GameStateSource and @GameLogSource: " + name);
            }
        }
        var rest = new ArrayList<String>();
        for (String name : beans.getBeanNamesForType(DataSource.class, true, false)) {
            if (!state.contains(name) && !logs.contains(name)) rest.add(name);
        }
        DataSource stateSource = source(beans, state.isEmpty() ? rest : state, "state", "@GameStateSource");
        if (!logs.isEmpty()) return new Sources(stateSource, source(beans, logs, "log", "@GameLogSource"));
        return new Sources(stateSource, state.isEmpty() ? stateSource : null);
    }

    private static List<String> qualified(ConfigurableListableBeanFactory beans, Class<? extends Annotation> qualifier) {
        var result = new ArrayList<String>();
        for (String name : beans.getBeanNamesForType(DataSource.class, true, false)) {
            if (beans.findAnnotationOnBean(name, qualifier, false) != null) result.add(name);
        }
        return result;
    }

    private static DataSource source(ConfigurableListableBeanFactory beans, List<String> names, String store, String hint) {
        if (names.isEmpty()) return null;
        String name = names.getFirst();
        if (names.size() > 1) {
            var primary = names.stream().filter(candidate -> beans.getBeanDefinition(candidate).isPrimary()).toList();
            if (primary.size() != 1) {
                throw new IllegalArgumentException("Several DataSource Beans could be the " + store + " store "
                        + names + "; annotate one with " + hint);
            }
            name = primary.getFirst();
        }
        return beans.getBean(name, DataSource.class);
    }

    private static List<Binding> bindings(ConfigurableListableBeanFactory beans) {
        var result = new ArrayList<Binding>();
        for (Class<?> base : List.of(SingleRepository.class, GroupRepository.class, LogRepository.class)) {
            for (String name : beans.getBeanNamesForType(base, true, false)) {
                if (beans.findAnnotationOnBean(name, Repository.class, false) != null) {
                    result.add(new Binding(name, beans.getType(name, false), base == LogRepository.class));
                }
            }
        }
        return result;
    }
}
