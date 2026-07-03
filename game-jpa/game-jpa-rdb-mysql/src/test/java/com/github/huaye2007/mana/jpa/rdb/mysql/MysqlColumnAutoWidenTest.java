package com.github.huaye2007.mana.jpa.rdb.mysql;

import com.github.huaye2007.mana.jpa.rdb.metadata.RdbFieldMetadata;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * 字段超长自愈的纯逻辑单元测试：列名解析 + 可加宽类型判断。
 * ALTER MODIFY 的端到端行为由 MySQL 集成测试覆盖。
 */
public class MysqlColumnAutoWidenTest {

    @Test
    public void parsesColumnNameFromDataTooLongMessage() {
        assertEquals("nickname",
                MysqlRdbExecutor.parseDataTooLongColumn("Data too long for column 'nickname' at row 1"));
        // 大小写不敏感
        assertEquals("descr",
                MysqlRdbExecutor.parseDataTooLongColumn("data too long for COLUMN 'descr' at row 5"));
        // 非字段超长错误返回 null
        assertNull(MysqlRdbExecutor.parseDataTooLongColumn("Duplicate entry '1' for key 'PRIMARY'"));
        assertNull(MysqlRdbExecutor.parseDataTooLongColumn(null));
    }

    @Test
    public void onlyVariableLengthColumnsAreWidenable() throws Exception {
        // VARCHAR 列可加宽
        assertTrue(MysqlRdbExecutor.isWidenable(field("name", String.class, 50, false)));
        // 数值列无长度概念，不可加宽
        assertFalse(MysqlRdbExecutor.isWidenable(field("age", int.class, 0, false)));
        // JSON 列不可加宽
        assertFalse(MysqlRdbExecutor.isWidenable(field("bag", Map.class, 0, true)));
    }

    private static RdbFieldMetadata field(String name, Class<?> javaType, int length, boolean json)
            throws NoSuchFieldException {
        Field f = Sample.class.getDeclaredField(name);
        return new RdbFieldMetadata(f, name, name, javaType, false, length, "", false, json, "", false, false);
    }

    @SuppressWarnings("unused")
    private static final class Sample {
        String name;
        int age;
        Map<String, Object> bag;
    }
}
