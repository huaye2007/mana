package cn.managame.config;

import java.util.List;
import java.util.Objects;

/** Validates a complete candidate snapshot before it becomes visible to readers. */
@FunctionalInterface
public interface ConfigValidator {
    void validate(ConfigSnapshot candidate);

    static ConfigValidator none() {
        return candidate -> { };
    }

    /**
     * Fails validation when any of {@code keys} is missing or blank.
     *
     * <p>Applied to the initial snapshot too, so a process cannot start without its mandatory
     * settings, and applied to every update, so a bad publish keeps the last known good snapshot
     * instead of blanking a required key at runtime.</p>
     */
    static ConfigValidator requireKeys(Iterable<String> keys) {
        List<String> required = java.util.stream.StreamSupport.stream(keys.spliterator(), false)
                .map(key -> Objects.requireNonNull(key, "required key"))
                .toList();
        if (required.isEmpty()) return none();
        return candidate -> {
            List<String> missing = required.stream()
                    .filter(key -> { String value = candidate.get(key); return value == null || value.isBlank(); })
                    .toList();
            if (!missing.isEmpty()) throw new ConfigException("missing required config: " + missing);
        };
    }

    /** Runs this validator, then {@code next}. The first failure wins. */
    default ConfigValidator and(ConfigValidator next) {
        Objects.requireNonNull(next, "next");
        return candidate -> { validate(candidate); next.validate(candidate); };
    }
}
