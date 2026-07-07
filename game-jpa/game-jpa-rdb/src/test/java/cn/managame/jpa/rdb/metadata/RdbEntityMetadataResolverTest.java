package cn.managame.jpa.rdb.metadata;

import cn.managame.jpa.rdb.annotation.Entity;
import cn.managame.jpa.core.annotation.Id;
import cn.managame.jpa.rdb.annotation.Column;
import cn.managame.jpa.rdb.annotation.ColumnType;
import cn.managame.jpa.rdb.annotation.Table;
import cn.managame.jpa.rdb.annotation.Transient;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

public class RdbEntityMetadataResolverTest {

    @Test
    public void ignoresStaticAndTransientFields() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);

        assertEquals(2, metadata.fields().size());
        assertNotNull(metadata.fieldByPropertyName("id"));
        assertNotNull(metadata.fieldByPropertyName("name"));
        assertNull(metadata.fieldByPropertyName("serialVersionUID"));
        assertNull(metadata.fieldByPropertyName("TYPE"));
        assertNull(metadata.fieldByPropertyName("scratch"));
        assertNull(metadata.fieldByPropertyName("ignoredByAnnotation"));
    }

    @Test
    public void acceptsDefaultValueOnPrimitiveField() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(PrimitiveDefault.class);
        RdbFieldMetadata level = metadata.fieldByPropertyName("level");

        assertNotNull(level);
        assertEquals("1", level.defaultValue());
    }

    @Test
    public void appliesDefaultValueToPrimitiveZeroValue() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(PrimitiveDefault.class);
        PrimitiveDefault entity = new PrimitiveDefault();

        RdbDefaultValues.applyInsertDefaults(metadata, entity);

        assertEquals(1, entity.level);
    }

    @Test
    public void inferType_complexFieldDefaultsToJson() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(ColumnTypes.class);

        RdbFieldMetadata bag = metadata.fieldByPropertyName("bag");
        assertTrue(bag.isJsonField(), "complex type without type() should infer JSON");
        assertEquals("", bag.sqlType());

        RdbFieldMetadata plain = metadata.fieldByPropertyName("plain");
        assertFalse(plain.isJsonField(), "scalar type should not infer JSON");
        assertEquals("", plain.sqlType());
    }

    @Test
    public void explicitJsonType_forcesJsonEvenOnScalar() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(ColumnTypes.class);

        // 复杂类型显式 JSON：等价默认行为
        assertTrue(metadata.fieldByPropertyName("explicitJson").isJsonField());
        // 简单类型强制 JSON（场景 C）：String 内容本身是 JSON
        RdbFieldMetadata rawJson = metadata.fieldByPropertyName("rawJson");
        assertTrue(rawJson.isJsonField());
        assertEquals("", rawJson.sqlType());
    }

    @Test
    public void explicitNonJsonType_overridesDdlAndSuppressesJson() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(ColumnTypes.class);

        // 简单类型换 DDL（场景 D）
        RdbFieldMetadata description = metadata.fieldByPropertyName("description");
        assertFalse(description.isJsonField());
        assertEquals("TEXT", description.sqlType());

        // 复杂类型 + 显式非 JSON 类型（场景 B）：抑制 JSON 推断，值绑定交给 TypeConverter
        RdbFieldMetadata coord = metadata.fieldByPropertyName("coord");
        assertFalse(coord.isJsonField(), "explicit non-JSON type must suppress JSON inference");
        assertEquals("VARCHAR", coord.sqlType());
    }

    @Entity
    @Table(name = "column_types")
    private static class ColumnTypes {
        @Id
        @Column
        private long id;

        @Column
        private Map<String, Integer> bag;                       // 复杂 → 自动 JSON

        @Column(type = ColumnType.JSON)
        private Map<String, Integer> explicitJson;              // 复杂 → 显式 JSON

        @Column(type = ColumnType.JSON)
        private String rawJson;                                 // 简单 → 强制 JSON（场景 C）

        @Column(type = ColumnType.TEXT)
        private String description;                             // 简单 → 换 DDL（场景 D）

        @Column(type = ColumnType.VARCHAR, length = 64)
        private Point coord;                                    // 复杂 + 显式类型（场景 B）

        @Column
        private String plain;                                   // 标量默认
    }

    private static final class Point {
        @SuppressWarnings("unused")
        private int x;
        @SuppressWarnings("unused")
        private int y;
    }

    @Entity
    @Table(name = "player")
    private static class Player implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private static final String TYPE = "player";

        @Id
        @Column
        private long id;
        @Column
        private String name;
        private transient String scratch;

        @Transient
        private String ignoredByAnnotation;
    }

    @Entity
    @Table(name = "primitive_default")
    private static class PrimitiveDefault {
        @Id
        @Column
        private long id;

        @Column(defaultValue = "1")
        private int level;
    }

    @Test
    public void ignoresFieldWithoutColumnAnnotation() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(PartiallyMapped.class);

        assertEquals(1, metadata.fields().size());
        assertNotNull(metadata.fieldByPropertyName("id"));
        assertNull(metadata.fieldByPropertyName("notMapped"),
                "field without @Column must be ignored");
    }

    @Entity
    @Table(name = "partially_mapped")
    private static class PartiallyMapped {
        @Id
        @Column
        private long id;

        private String notMapped;   // 无 @Column → 不映射
    }
}
