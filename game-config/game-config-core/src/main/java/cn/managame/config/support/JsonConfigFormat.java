package cn.managame.config.support;

import cn.managame.config.ConfigException;
import cn.managame.config.spi.ConfigFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;

/**
 * Built-in {@code json} format. Selected for {@code .json} resources, or pinned with
 * {@code property("format", "json")}.
 *
 * <p>Lives beside {@link PropertiesConfigFormat} and {@link YamlConfigFormat} in core rather than in
 * one backend, because a document format is not a property of where the document is stored: the same
 * JSON reads the same whether it comes from a file, Nacos or Etcd.</p>
 *
 * <p>A document must have an object root. Nested objects expand to dotted keys, so
 * {@code {"game":{"server":{"port":8080}}}} reads as {@code game.server.port}. Arrays are kept both
 * as their compact JSON text under the base key and as indexed keys {@code regions[0]},
 * {@code servers[0].host}, which {@link cn.managame.config.ConfigSnapshot#getList(String)
 * ConfigSnapshot.getList} reads back as a list. Nested arrays expand the same way.</p>
 */
public final class JsonConfigFormat implements ConfigFormat {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String name() { return "json"; }

    @Override public boolean claims(String resource) {
        return resource != null && resource.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    @Override public Map<String, String> parse(String content) {
        if (content == null || content.isBlank()) return Map.of();
        try {
            return DocumentFlattener.flatten(MAPPER.readTree(content), "JSON");
        } catch (JsonProcessingException e) {
            throw new ConfigException("invalid JSON document", e);
        }
    }
}
