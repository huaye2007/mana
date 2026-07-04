package cn.managame.config.api;

import java.util.Map;
import java.util.Set;

public class ConfigChangeEvent {
    private final Map<String, String> oldConfig;
    private final Map<String, String> newConfig;
    private final Set<String> changedKeys;
    private final Map<String, ChangeType> changeDetails;

    public ConfigChangeEvent(Map<String, String> oldConfig, Map<String, String> newConfig,
                             Set<String> changedKeys, Map<String, ChangeType> changeDetails) {
        this.oldConfig = oldConfig == null ? Map.of() : Map.copyOf(oldConfig);
        this.newConfig = newConfig == null ? Map.of() : Map.copyOf(newConfig);
        this.changedKeys = changedKeys == null ? Set.of() : Set.copyOf(changedKeys);
        this.changeDetails = changeDetails == null ? Map.of() : Map.copyOf(changeDetails);
    }

    public Map<String, String> getOldConfig() {
        return oldConfig;
    }

    public Map<String, String> getNewConfig() {
        return newConfig;
    }

    public Set<String> getChangedKeys() {
        return changedKeys;
    }

    /**
     * 每个变更 key 对应的变更类型（ADDED / UPDATED / DELETED）。
     */
    public Map<String, ChangeType> getChangeDetails() {
        return changeDetails;
    }

    /**
     * 获取指定 key 的变更类型，如果该 key 未变更则返回 null。
     */
    public ChangeType getChangeType(String key) {
        return changeDetails.get(key);
    }
}
