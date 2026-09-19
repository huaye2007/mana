package cn.managame.spring;

import cn.managame.core.GameData;
import cn.managame.core.repository.SingleRepository;
import cn.managame.spring.fixtures.basic.Repositories.*;
import cn.managame.spring.fixtures.group.GroupRows;
import cn.managame.spring.fixtures.invalid.RawRows;
import cn.managame.support.DataTestSupport.Access;
import cn.managame.support.DataTestSupport.Row;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringRepositoryTest {
    @Configuration(proxyBeanMethods = false)
    @EnableGameDataRepositories(basePackageClasses = WalletRows.class)
    static class BasicConfig {
        @Bean(destroyMethod = "") Access access() { return new Access(); }
        @Bean GameData gameData(Access access) { return new GameData(access); }
    }
    static class Consumer {
        final WalletRows constructor;
        @Autowired private EventRepository logs;
        @Autowired private OtherRows field;
        @Autowired @Qualifier("walletRepository") private SingleRepository<Row, Long> qualified;
        @Autowired private SingleRepository<Row, Long> primary;
        Consumer(WalletRows constructor) { this.constructor = constructor; }
    }
    @Configuration(proxyBeanMethods = false)
    @EnableGameDataRepositories(basePackageClasses = WalletRows.class)
    static class OverlappingConfig { }

    @Test void springInjectsConstructorFieldsQualifiersAndPrimaryAndDrainsOnClose() {
        Access store;
        try (var context = new AnnotationConfigApplicationContext(BasicConfig.class, OverlappingConfig.class, Consumer.class)) {
            GameData data = context.getBean(GameData.class);
            var service = context.getBean(Consumer.class);
            store = context.getBean(Access.class);
            assertSame(data.repository(WalletRows.class), service.constructor);
            assertSame(data.repository(EventRepository.class), service.logs);
            service.logs.append(new cn.managame.support.DataTestSupport.Event(23, java.time.LocalDate.of(2026, 9, 1)));
            service.logs.flush();
            assertSame(data.repository(OtherRows.class), service.field);
            assertSame(service.constructor, service.qualified);
            assertSame(service.constructor, service.primary);
            assertSame(service.constructor, context.getBean("walletRepository"));
            assertTrue(context.getBeansOfType(UnmarkedRows.class).isEmpty());
            assertTrue(context.getBeansOfType(OrdinaryRepository.class).isEmpty());
            Row row = new Row(17);
            service.constructor.insert(row);
            assertSame(row, service.field.get(17L).orElseThrow());
        }
        assertEquals(List.of(23L, 17L), store.saved);
        assertTrue(store.closed);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableGameDataRepositories(basePackages = "cn.managame.spring.fixtures.group",
            gameDataRef = "archiveData", dataAccess = "archive")
    static class GroupConfig {
        @Bean(destroyMethod = "") Access archive() { return new Access(); }
        @Bean GameData archiveData(Access archive) {
            return GameData.builder().dataAccess("main", new Access()).dataAccess("archive", archive).build();
        }
    }
    @Test void groupRepositoriesUseTheConfiguredOwnerAndNamedDataSource() {
        try (var context = new AnnotationConfigApplicationContext(GroupConfig.class)) {
            var data = context.getBean("archiveData", GameData.class);
            var repository = context.getBean(GroupRows.class);
            assertSame(data.repository("archive", GroupRows.class), repository);
            repository.getGroup(1L);
            repository.insert(new Row(23));
            data.flush();
            assertEquals(List.of(23L), context.getBean(Access.class).saved);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableGameDataRepositories(basePackageClasses = RawRows.class)
    @Import(BasicConfig.class)
    static class InvalidConfig { }
    @Test void invalidGenericRepositoryFailsAtStartupEvenWithoutConsumers() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(InvalidConfig.class);
            assertThrows(BeansException.class, context::refresh);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableGameDataRepositories(basePackageClasses = WalletRows.class, gameDataRef = "absent")
    static class MissingOwner { }
    @Configuration(proxyBeanMethods = false)
    @EnableGameDataRepositories(basePackageClasses = GroupRows.class, dataAccess = "absent")
    @Import(BasicConfig.class)
    static class MissingAccess { }
    @Test void missingOwnerOrDataSourceFailsAtStartup() {
        for (Class<?> configuration : List.of(MissingOwner.class, MissingAccess.class)) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.register(configuration);
                assertThrows(BeansException.class, context::refresh);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableGameDataRepositories(basePackages = "cn.managame.spring.fixtures.collision")
    static class DuplicateNames { }
    @Test void beanNamesCannotSilentlyOverwriteOtherRepositoriesOrExistingBeans() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(DuplicateNames.class);
            assertThrows(BeansException.class, context::refresh);
        }
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("walletRepository", String.class, () -> "existing");
            context.register(BasicConfig.class);
            assertThrows(BeansException.class, context::refresh);
        }
    }
}
