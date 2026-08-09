package cn.managame.config;

/**
 * One layer's contribution to a key.
 *
 * @param layer the layer name, as reported by {@link ConfigLayer#name()}
 * @param value the value that layer supplies for the key
 */
public record ConfigOrigin(String layer, String value) {
    public ConfigOrigin {
        java.util.Objects.requireNonNull(layer, "layer");
        java.util.Objects.requireNonNull(value, "value");
    }

    @Override public String toString() { return layer + "=" + value; }
}
