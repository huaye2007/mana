package cn.managame.config.support;

import cn.managame.config.ConfigException;
import cn.managame.config.ConfigOptions;
import cn.managame.config.spi.ConfigFormat;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFormatsTest {

    @Test void resolvesBuiltInFormatsByResourceName() {
        assertEquals("json", ConfigFormats.forResource("application.json").name());
        assertEquals("json", ConfigFormats.forResource("/remote/key/App.JSON").name());
        assertEquals("yaml", ConfigFormats.forResource("application.yml").name());
        assertEquals("yaml", ConfigFormats.forResource("config/Application.YAML").name());
        assertEquals("properties", ConfigFormats.forResource("application.properties").name());
        // Anything unclaimed falls back to properties rather than failing.
        assertEquals("properties", ConfigFormats.forResource("/etcd/key/without/extension").name());
        assertEquals("properties", ConfigFormats.forResource("").name());
    }

    @Test void yamlAndJsonProduceIdenticalKeysForTheSameStructure() {
        Map<String, String> fromYaml = ConfigFormats.byName("yaml").parse("""
                game:
                  server:
                    port: 7000
                    enabled: true
                  name: mana
                regions:
                  - cn
                  - us
                servers:
                  - host: 127.0.0.1
                    ports: [8080, 8081]
                nullable: null
                """);
        Map<String, String> fromJson = ConfigFormats.byName("json").parse("""
                {
                  "game": {"server": {"port": 7000, "enabled": true}, "name": "mana"},
                  "regions": ["cn", "us"],
                  "servers": [{"host": "127.0.0.1", "ports": [8080, 8081]}],
                  "nullable": null
                }
                """);

        // Both run through the same flattener, so moving a document between formats is not a code
        // change for whoever reads it.
        assertEquals(fromJson, fromYaml);
        assertEquals("7000", fromYaml.get("game.server.port"));
        assertEquals("[\"cn\",\"us\"]", fromYaml.get("regions"));
        assertEquals("us", fromYaml.get("regions[1]"));
        assertEquals("8081", fromYaml.get("servers[0].ports[1]"));
        assertEquals("null", fromYaml.get("nullable"));
    }

    @Test void yamlRejectsBadDocumentsAndArbitraryTypes() {
        ConfigFormat yaml = ConfigFormats.byName("yaml");
        assertEquals(Map.of(), yaml.parse(""));
        assertEquals(Map.of(), yaml.parse("# only a comment"));
        assertThrows(ConfigException.class, () -> yaml.parse("- 1\n- 2"));
        assertThrows(ConfigException.class, () -> yaml.parse("a: [1, 2"));
        // SafeConstructor resolves only standard tags, so a document cannot name a class to build.
        assertThrows(ConfigException.class, () -> yaml.parse("a: !!java.net.URL [http://example.com]"));
    }

    @Test void formatPropertyPinsOneFormatForEveryResource() {
        ConfigOptions pinned = ConfigOptions.builder("etcd").property("format", "json").build();
        assertEquals("json", ConfigFormats.of(pinned, "/config/base").name());

        ConfigOptions unpinned = ConfigOptions.builder("etcd").build();
        assertEquals("properties", ConfigFormats.of(unpinned, "/config/base").name());
    }

    @Test void unknownFormatNamesWhatIsRegistered() {
        ConfigException error = assertThrows(ConfigException.class, () -> ConfigFormats.byName("toml"));
        assertTrue(error.getMessage().contains("toml"), error.getMessage());
        assertTrue(error.getMessage().contains("json"), error.getMessage());
        assertTrue(error.getMessage().contains("yaml"), error.getMessage());
        assertTrue(error.getMessage().contains("properties"), error.getMessage());
    }

    @Test void jsonFlattensObjectsAndArrays() {
        ConfigFormat json = ConfigFormats.byName("json");
        Map<String, String> values = json.parse("""
                {
                  "game": {"server": {"port": 7000, "enabled": true}, "name": "mana"},
                  "regions": ["cn", "us"],
                  "servers": [{"host": "127.0.0.1", "ports": [8080, 8081]}],
                  "nullable": null
                }
                """);

        assertEquals("7000", values.get("game.server.port"));
        assertEquals("true", values.get("game.server.enabled"));
        assertEquals("mana", values.get("game.name"));
        assertEquals("[\"cn\",\"us\"]", values.get("regions"));
        assertEquals("cn", values.get("regions[0]"));
        assertEquals("127.0.0.1", values.get("servers[0].host"));
        assertEquals("8081", values.get("servers[0].ports[1]"));
        assertEquals("null", values.get("nullable"));
    }

    @Test void blankDocumentIsEmptyAndBadDocumentFails() {
        ConfigFormat json = ConfigFormats.byName("json");
        // A deleted remote key arrives as empty content; that is an empty document, not an error.
        assertEquals(Map.of(), json.parse(""));
        assertEquals(Map.of(), json.parse(null));
        assertThrows(ConfigException.class, () -> json.parse("[1, 2, 3]"));
        assertThrows(ConfigException.class, () -> json.parse("{not json"));

        ConfigFormat properties = ConfigFormats.byName("properties");
        assertEquals(Map.of(), properties.parse(""));
        assertEquals(Map.of("a", "1"), properties.parse("a=1"));
    }
}
