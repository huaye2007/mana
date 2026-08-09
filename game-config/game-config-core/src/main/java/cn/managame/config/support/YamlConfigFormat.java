package cn.managame.config.support;

import cn.managame.config.ConfigException;
import cn.managame.config.spi.ConfigFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.Locale;
import java.util.Map;

/**
 * Built-in {@code yaml} format, for {@code .yml} and {@code .yaml} resources.
 *
 * <p>Produces exactly the same keys as {@link JsonConfigFormat} for the same structure — both run
 * through the same flattener — so {@code game.server.port} reads identically whether it was written
 * as YAML or JSON, and switching between them is not a code change.</p>
 *
 * <p>Parsed with SnakeYAML's {@code SafeConstructor}, which resolves only standard scalar and
 * collection tags. A document cannot name a Java class to instantiate, so a config file — including
 * one fetched from a remote backend — cannot turn a load into arbitrary object construction.</p>
 */
public final class YamlConfigFormat implements ConfigFormat {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override public String name() { return "yaml"; }

    @Override public boolean claims(String resource) {
        if (resource == null) return false;
        String lower = resource.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    @Override public Map<String, String> parse(String content) {
        if (content == null || content.isBlank()) return Map.of();
        Object document;
        try {
            // A new Yaml per call: the instance is not thread-safe, and parsing is a cold path.
            document = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
        } catch (YAMLException e) {
            throw new ConfigException("invalid YAML document", e);
        }
        if (document == null) return Map.of();
        return DocumentFlattener.flatten(MAPPER.valueToTree(document), "YAML");
    }
}
