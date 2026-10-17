package cn.managame.data.mysql;

import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

final class SqlNames {
    private SqlNames() {}
    /** {@code [A-Za-z_][A-Za-z0-9_]*}, at most 64 characters; checked without a regular expression (hot for log tables). */
    static String identifier(String name) {
        boolean valid = !name.isEmpty() && name.length() <= 64;
        for (int i = 0; valid && i < name.length(); i++) {
            char c = name.charAt(i);
            valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || (i > 0 && c >= '0' && c <= '9');
        }
        if (!valid) throw new IllegalArgumentException("Invalid MySQL identifier: " + name);
        return name;
    }
    static String quote(String name) { return "`" + identifier(name) + "`"; }
    static String snake(String name) {
        return name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
    static String generatedIndex(String name) {
        if (name.length() <= 64) return identifier(name);
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
            return identifier(name.substring(0, 47) + "_" + hash);
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
