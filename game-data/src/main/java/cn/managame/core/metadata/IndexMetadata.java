package cn.managame.core.metadata;

import cn.managame.annotation.IndexDirection;
import java.util.List;

/** Ordered mapped columns shared by JDBC and document indexes. */
public record IndexMetadata(String name, List<Column> columns, boolean unique, boolean sparse) {
    public record Column(String fieldName, String storeName, IndexDirection direction) { }
    public IndexMetadata {
        columns = List.copyOf(columns);
        if (columns.isEmpty()) throw new IllegalArgumentException("Index requires at least one column");
    }
    public IndexMetadata(String name, String fieldName, String storeName, boolean unique, boolean sparse, IndexDirection direction) {
        this(name, List.of(new Column(fieldName, storeName, direction)), unique, sparse);
    }
    public String fieldName() { return columns.getFirst().fieldName(); }
    public String storeName() { return columns.getFirst().storeName(); }
    public IndexDirection direction() { return columns.getFirst().direction(); }
}
