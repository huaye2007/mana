package cn.managame.config;

/** Validates a complete candidate snapshot before it becomes visible to readers. */
@FunctionalInterface
public interface ConfigValidator {
    void validate(ConfigSnapshot candidate);

    static ConfigValidator none() {
        return candidate -> { };
    }
}
