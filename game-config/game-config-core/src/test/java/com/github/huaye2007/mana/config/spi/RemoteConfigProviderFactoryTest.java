package com.github.huaye2007.mana.config.spi;

import com.github.huaye2007.mana.config.exception.ConfigOperationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteConfigProviderFactoryTest {
    @Test
    void shouldNormalizeRemoteType() {
        assertNull(RemoteConfigProviderFactory.normalize(null));
        assertNull(RemoteConfigProviderFactory.normalize(" "));
        assertEquals("nacos", RemoteConfigProviderFactory.normalize(" Nacos "));
    }

    @Test
    void shouldMatchTypeIgnoringCaseAndWhitespace() {
        RemoteConfigProvider provider = provider(" Nacos ", List.of());

        assertTrue(RemoteConfigProviderFactory.matches(provider, "nacos"));
    }

    @Test
    void shouldMatchAliasIgnoringCaseAndWhitespace() {
        RemoteConfigProvider provider = provider("local", List.of(" File "));

        assertTrue(RemoteConfigProviderFactory.matches(provider, "file"));
    }

    @Test
    void shouldIgnoreBlankTypeAndAliases() {
        RemoteConfigProvider provider = provider(" ", List.of(" ", "other"));

        assertFalse(RemoteConfigProviderFactory.matches(provider, "nacos"));
    }

    @Test
    void shouldIgnoreNullAliasesIterable() {
        RemoteConfigProvider provider = provider("local", null);

        assertFalse(RemoteConfigProviderFactory.matches(provider, "file"));
    }

    @Test
    void shouldExplainMissingProviderDependency() {
        ConfigOperationException error = assertThrows(
                ConfigOperationException.class,
                () -> RemoteConfigProviderFactory.create("missing-provider"));

        assertTrue(error.getMessage().contains("runtime classpath"));
    }

    private RemoteConfigProvider provider(String type, Iterable<String> aliases) {
        return new RemoteConfigProvider() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Iterable<String> aliases() {
                return aliases;
            }

            @Override
            public Map<String, String> load(Properties remoteProperties) {
                return Map.of();
            }
        };
    }
}
