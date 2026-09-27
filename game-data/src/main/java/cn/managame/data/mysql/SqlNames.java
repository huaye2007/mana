package cn.managame.data.mysql;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

final class SqlNames {
    private SqlNames() {}
    static String identifier(String name) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || name.length() > 64)
            throw new IllegalArgumentException("Invalid MySQL identifier: " + name);
        return name;
    }
    static String quote(String name) { return "`" + identifier(name) + "`"; }
    static String snake(String name) {
        return name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }
    static String generatedIndex(String name) {
        if (name.length() <= 64) return identifier(name);
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
            return identifier(name.substring(0, 47) + "_" + hash);
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
