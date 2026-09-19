package cn.managame.core.key;

/** Uses the same native-value/colon-separated-string rules as group keys. */
public final class MapKeys {
    private MapKeys() { }

    public static Object of(Object... values) {
        return GroupKeys.of(values);
    }
}
