package cn.managame.config.factory;

import cn.managame.config.manager.GameConfigManager;
import cn.managame.config.source.ConfigSource;
import cn.managame.config.source.DefaultConfigSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameConfigOptionsTest {
    @Test
    void shouldFilterNullSourcesWhenSettingSources() {
        ConfigSource source = new DefaultConfigSource(Map.of("mode", "prod"));
        GameConfigOptions options = new GameConfigOptions();
        List<ConfigSource> sources = new ArrayList<>();
        sources.add(source);
        sources.add(null);

        options.setSources(sources);

        assertEquals(1, options.getSources().size());
        assertEquals(source, options.getSources().get(0));
    }

    @Test
    void shouldTreatNullSourceListAsEmpty() {
        GameConfigOptions options = new GameConfigOptions();
        options.addSource(new DefaultConfigSource(Map.of("mode", "prod")));

        options.setSources(null);

        assertTrue(options.getSources().isEmpty());
    }

    @Test
    void shouldReturnSourceListCopy() {
        ConfigSource source = new DefaultConfigSource(Map.of("mode", "prod"));
        GameConfigOptions options = new GameConfigOptions();
        options.addSource(source);

        List<ConfigSource> returned = options.getSources();
        returned.clear();

        assertEquals(1, options.getSources().size());
    }

    @Test
    void shouldDefaultToHotReloadEnabledWithoutFailFast() {
        GameConfigOptions options = new GameConfigOptions();

        assertTrue(options.isHotReloadEnabled());
        assertTrue(!options.isFailFast());
    }

    @Test
    void shouldExposeListenerExecutorGetterAndSetter() {
        GameConfigOptions options = new GameConfigOptions();
        assertEquals(null, options.getListenerExecutor());

        java.util.concurrent.Executor executor = Runnable::run;
        options.setListenerExecutor(executor);

        assertEquals(executor, options.getListenerExecutor());
    }

    @Test
    void shouldAddValidatorsInBulkAndIgnoreNulls() {
        AtomicInteger calls = new AtomicInteger();
        GameConfigOptions options = new GameConfigOptions();

        options.addValidators(
                config -> calls.incrementAndGet(),
                null,
                config -> calls.addAndGet(10));
        options.addValidators((Iterable<cn.managame.config.api.ConfigValidator>) null);
        options.addValidators(List.of(config -> calls.addAndGet(100)));

        assertEquals(3, options.getValidators().size());
        options.getValidators().forEach(validator -> validator.validate(Map.of()));
        assertEquals(111, calls.get());
    }

    @Test
    void shouldReturnValidatorListCopy() {
        GameConfigOptions options = new GameConfigOptions();
        options.addValidator(config -> { });

        List<cn.managame.config.api.ConfigValidator> returned = options.getValidators();
        returned.clear();

        assertEquals(1, options.getValidators().size());
    }

    @Test
    void managerShouldRejectNullOptions() {
        NullPointerException error = assertThrows(
                NullPointerException.class,
                () -> new GameConfigManager(null));

        assertEquals("options must not be null", error.getMessage());
    }
}
