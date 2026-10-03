package cn.managame.spring.data;

import cn.managame.data.GameData;
import cn.managame.data.GameDataBuilder;
import cn.managame.data.GroupRepository;
import cn.managame.data.LogRepository;
import cn.managame.data.SingleRepository;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

@Configuration(proxyBeanMethods = false)
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
    public GameData gameData(DataSource source, ConfigurableListableBeanFactory beans, org.springframework.beans.factory.ObjectProvider<GameDataConfigurer> configurers) {
        var builder = GameDataBuilder.builder().mysql(source);
        for (Binding binding : bindings(beans)) {
            if (binding.log()) builder.logRepositories(binding.type());
            else builder.repositories(binding.type());
        }
        configurers.orderedStream().forEach(configurer -> configurer.configure(builder));
        return builder.build();
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
