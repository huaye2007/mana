package cn.managame.config.provider;

import cn.managame.config.ConfigLayer;
import cn.managame.config.spi.ConfigProvider;
import cn.managame.config.spi.ConfigSource;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reads process environment variables as a config layer.
 *
 * <p>Variable names map to config keys by lowercasing and turning {@code _} into {@code .}, so
 * {@code GAME_DB_URL} reads back as {@code game.db.url}. A doubled {@code __} produces a literal
 * underscore. The {@code prefix} property filters which variables are read; it is a filter, not a
 * namespace to remove, unless {@code strip} is set.</p>
 *
 * <p>Environment variables cannot change inside a live process, so this layer has no watch. It is
 * re-read on every reload, which keeps it consistent with the rest of the stack at no cost.</p>
 */
public final class EnvironmentConfigProvider implements ConfigProvider {
    @Override public String type() { return ConfigLayer.ENVIRONMENT; }

    @Override public ConfigSource create(ConfigLayer layer) {
        return new EnvironmentSource(layer, System::getenv);
    }

    static final class EnvironmentSource implements ConfigSource {
        private final String prefix;
        private final boolean strip;
        private final Supplier<Map<String, String>> environment;

        EnvironmentSource(ConfigLayer layer, Supplier<Map<String, String>> environment) {
            prefix = layer.property("prefix", "");
            strip = layer.booleanProperty("strip", false);
            this.environment = environment;
        }

        @Override public Map<String, String> load() {
            Map<String, String> mapped = new LinkedHashMap<>();
            environment.get().forEach((name, value) -> {
                if (!matchesPrefix(name)) return;
                String source = strip ? name.substring(prefix.length()) : name;
                if (!source.isEmpty()) mapped.put(toKey(source), value);
            });
            return Map.copyOf(mapped);
        }

        private boolean matchesPrefix(String name) {
            return prefix.isEmpty()
                    || name.regionMatches(true, 0, prefix, 0, prefix.length());
        }

        /** {@code GAME_DB_URL} to {@code game.db.url}; {@code A__B} keeps the underscore as {@code a_b}. */
        static String toKey(String name) {
            StringBuilder key = new StringBuilder(name.length());
            for (int index = 0; index < name.length(); index++) {
                char current = name.charAt(index);
                if (current != '_') {
                    key.append(Character.toLowerCase(current));
                } else if (index + 1 < name.length() && name.charAt(index + 1) == '_') {
                    key.append('_');
                    index++;
                } else {
                    key.append('.');
                }
            }
            return key.toString();
        }

        @Override public void ping() { }

        @Override public AutoCloseable watch(Consumer<Map<String, String>> onUpdate, Consumer<Throwable> onError) {
            return () -> { };
        }

        @Override public String toString() {
            return "EnvironmentSource[prefix=" + prefix.toUpperCase(Locale.ROOT) + "]";
        }
    }
}
