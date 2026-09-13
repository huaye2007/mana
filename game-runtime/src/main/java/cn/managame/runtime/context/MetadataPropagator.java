package cn.managame.runtime.context;

@FunctionalInterface
public interface MetadataPropagator {
    Metadata propagate(Metadata parent);
    static MetadataPropagator copy() { return parent -> parent; }
}
