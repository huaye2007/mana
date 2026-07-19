package cn.managame.config.support;

import cn.managame.config.ConfigException;

import java.util.List;
import java.util.Map;

public final class ConfigRevisions {
    private ConfigRevisions() { }

    /** Requires every non-empty document in a multi-resource publication to carry one common revision. */
    public static long commonRevision(List<Map<String, String>> documents, String revisionKey) {
        if (revisionKey == null || revisionKey.isBlank()) {
            throw new IllegalArgumentException("revisionKey must not be blank for multiple resources");
        }
        Long common = null;
        for (Map<String, String> document : documents) {
            long revision = document.isEmpty() ? 0 : parseRevision(document.get(revisionKey), revisionKey);
            if (common == null) common = revision;
            else if (common != revision) {
                throw new ConfigException("config resources have inconsistent " + revisionKey
                        + " values: " + common + " and " + revision);
            }
        }
        return common == null ? 0 : common;
    }

    private static long parseRevision(String value, String revisionKey) {
        if (value == null || value.isBlank()) {
            throw new ConfigException("multi-resource config is missing revision key: " + revisionKey);
        }
        try {
            long revision = Long.parseLong(value.trim());
            if (revision < 0) throw new NumberFormatException("negative revision");
            return revision;
        } catch (NumberFormatException error) {
            throw new ConfigException("config revision must be a non-negative integer: " + value, error);
        }
    }
}
