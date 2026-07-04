package cn.managame.jpa.rdb.mysql;

import cn.managame.jpa.rdb.annotation.Column;
import cn.managame.jpa.rdb.annotation.ColumnType;
import cn.managame.jpa.rdb.annotation.Entity;
import cn.managame.jpa.rdb.annotation.Id;
import cn.managame.jpa.rdb.annotation.Index;
import cn.managame.jpa.rdb.annotation.Table;
import cn.managame.jpa.rdb.annotation.Version;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadata;
import cn.managame.jpa.rdb.metadata.RdbEntityMetadataResolver;
import cn.managame.jpa.rdb.query.RdbQuerySpec;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;

public class MysqlDialectTest {

    @Test
    public void quotesTableAndColumnIdentifiers() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        String sql = new MysqlDialect().buildQuery(metadata,
                new RdbQuerySpec().eq("roleId", 7L).orderByDesc("name"),
                "player_202605");

        assertEquals("SELECT `id`, `role_id`, `name` FROM `player_202605` " +
                "WHERE `role_id` = ? ORDER BY `name` DESC", sql);
    }

    @Test
    public void rejectsUnsafePhysicalTableName() {
        assertThrows(IllegalArgumentException.class, () -> {
            RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
            new MysqlDialect().insert(metadata, "player;drop");
        });
    }

    @Test
    public void schemaGeneratorRejectsUnsafeTableName() {
        assertThrows(IllegalArgumentException.class, () -> {
            RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(UnsafeTable.class);
            new MysqlSchemaGenerator(null).generateCreateTable(metadata);
        });
    }

    @Test
    public void upsertUsesBoundUpdateParametersInsteadOfValuesFunction() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(VersionedPlayer.class);
        String sql = new MysqlDialect().upsert(metadata);

        assertEquals("INSERT INTO `versioned_player` (`id`, `name`, `version`) VALUES (?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE `name` = IF(`version` = ?, ?, `name`), " +
                "`version` = IF(`version` = ?, `version` + 1, `version`)", sql);
        assertFalse(sql.contains("VALUES("));
    }

    @Test
    public void upsertPlacesVersionIncrementClauseLastRegardlessOfFieldOrder() {
        // @Version 声明在数据列之前，但生成的 ON DUPLICATE KEY UPDATE 必须把 version 自增子句排在最后。
        // 否则 MySQL 从左到右求值时，数据列的 IF(version = ?, ...) 会读到已自增的版本号、比较失败，
        // 导致这次写入被静默跳过（只把版本号 +1）。
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(VersionFirstPlayer.class);
        String sql = new MysqlDialect().upsert(metadata);

        String updatePart = sql.substring(sql.indexOf("ON DUPLICATE KEY UPDATE"));
        int versionClause = updatePart.indexOf("`version` = IF(`version` = ?, `version` + 1");
        int nameClause = updatePart.indexOf("`name` = IF(");
        int goldClause = updatePart.indexOf("`gold` = IF(");

        assertTrue(versionClause > 0, "version self-increment clause must be present");
        assertTrue(nameClause > 0 && goldClause > 0, "data column clauses must be present");
        assertTrue(versionClause > nameClause, "version clause must come after the name clause");
        assertTrue(versionClause > goldClause, "version clause must come after the gold clause");
    }

    @Test
    public void offsetWithoutLimitUsesMysqlUnboundedLimit() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(Player.class);
        String sql = new MysqlDialect().buildQuery(metadata, new RdbQuerySpec().offset(20), "player");

        assertEquals("SELECT `id`, `role_id`, `name` FROM `player` " +
                "LIMIT 18446744073709551615 OFFSET 20", sql);
    }

    @Test
    public void schemaGeneratorUsesColumnDefaultValues() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(ColumnDefaults.class);
        String sql = new MysqlSchemaGenerator(null).generateCreateTable(metadata);

        assertTrue(sql.contains("`name` VARCHAR(64) DEFAULT 'newbie''s'"));
        assertTrue(sql.contains("`level` INT DEFAULT 1"));
        assertTrue(sql.contains("`active` TINYINT(1) DEFAULT 1"));
        assertTrue(sql.contains("`version` BIGINT DEFAULT 0"));
        assertFalse(sql.contains("`name` VARCHAR(64) NOT NULL"));
    }

    @Test
    public void schemaGeneratorCreatesUniqueIndexForUniqueColumn() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(FieldUnique.class);
        List<String> indexes = new MysqlSchemaGenerator(null).generateIndexes(metadata);

        assertTrue(indexes.contains("CREATE UNIQUE INDEX `uk_name` ON `field_unique` (`name`)"));
    }

    @Test
    public void schemaGeneratorDoesNotDuplicateExplicitUniqueIndexForSameColumn() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(ExplicitUnique.class);
        List<String> indexes = new MysqlSchemaGenerator(null).generateIndexes(metadata);

        assertEquals(List.of("CREATE UNIQUE INDEX `uk_name_explicit` ON `explicit_unique` (`name`)"), indexes);
    }

    @Test
    public void schemaGeneratorUsesJsonDefaultExpression() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(JsonDefaults.class);
        String sql = new MysqlSchemaGenerator(null).generateCreateTable(metadata);

        assertTrue(sql.contains("`bag` JSON DEFAULT ('{}')"));
    }

    @Test
    public void schemaGeneratorRejectsInvalidNumericDefault() {
        assertThrows(IllegalArgumentException.class, () -> {
            RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(InvalidNumericDefault.class);
    
            new MysqlSchemaGenerator(null).generateCreateTable(metadata);
        });
    }

    @Test
    public void schemaGeneratorRejectsInvalidJsonDefault() {
        assertThrows(IllegalArgumentException.class, () -> {
            RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(InvalidJsonDefault.class);
    
            new MysqlSchemaGenerator(null).generateCreateTable(metadata);
        });
    }

    @Test
    public void schemaGeneratorUsesColumnTypeOverrides() {
        RdbEntityMetadata metadata = new RdbEntityMetadataResolver().resolve(ColumnTypeOverrides.class);
        String sql = new MysqlSchemaGenerator(null).generateCreateTable(metadata);

        assertTrue(sql.contains("`description` TEXT"), sql);
        assertTrue(sql.contains("`content` LONGTEXT"), sql);
        assertTrue(sql.contains("`url` VARCHAR(1024)"), sql);
        assertTrue(sql.contains("`code` CHAR(32)"), sql);
    }

    @Entity
    @Table(name = "column_type_overrides")
    private static class ColumnTypeOverrides {
        @Id
        @Column
        private long id;

        @Column(type = ColumnType.TEXT)
        private String description;

        @Column(type = ColumnType.LONGTEXT)
        private String content;

        @Column(type = ColumnType.VARCHAR, length = 1024)
        private String url;

        @Column(type = ColumnType.CHAR, length = 32)
        private String code;
    }

    @Entity
    @Table(name = "player")
    private static class Player {
        @Id
        @Column
        private long id;
        @Column(name = "role_id")
        private long roleId;
        @Column
        private String name;
    }

    @Entity
    @Table(name = "player;drop")
    private static class UnsafeTable {
        @Id
        @Column
        private long id;
    }

    @Entity
    @Table(name = "versioned_player")
    private static class VersionedPlayer {
        @Id
        @Column
        private long id;
        @Column
        private String name;
        @Version
        @Column
        private long version;
    }

    @Entity
    @Table(name = "version_first")
    private static class VersionFirstPlayer {
        @Id
        @Column
        private long id;
        @Version
        @Column
        private long version;
        @Column
        private String name;
        @Column
        private long gold;
    }

    @Entity
    @Table(name = "column_defaults")
    private static class ColumnDefaults {
        @Id
        @Column
        private long id;

        @Column(length = 64, defaultValue = "newbie's")
        private String name;

        @Column(defaultValue = "1")
        private Integer level;

        @Column(defaultValue = "true")
        private Boolean active;

        @Version
        @Column
        private long version;
    }

    @Entity
    @Table(name = "field_unique")
    @Index(name = "uk_name", columns = {"name"}, unique = true)
    private static class FieldUnique {
        @Id
        @Column
        private long id;

        @Column
        private String name;
    }

    @Entity
    @Table(name = "explicit_unique")
    @Index(name = "uk_name_explicit", columns = {"name"}, unique = true)
    private static class ExplicitUnique {
        @Id
        @Column
        private long id;

        @Column
        private String name;
    }

    @Entity
    @Table(name = "json_defaults")
    private static class JsonDefaults {
        @Id
        @Column
        private long id;

        @Column(type = ColumnType.JSON, defaultValue = "{}")
        private java.util.Map<String, Object> bag;
    }

    @Entity
    @Table(name = "invalid_numeric_default")
    private static class InvalidNumericDefault {
        @Id
        @Column
        private long id;

        @Column(defaultValue = "one")
        private Integer level;
    }

    @Entity
    @Table(name = "invalid_json_default")
    private static class InvalidJsonDefault {
        @Id
        @Column
        private long id;

        @Column(type = ColumnType.JSON, defaultValue = "{bad")
        private java.util.Map<String, Object> bag;
    }
}
