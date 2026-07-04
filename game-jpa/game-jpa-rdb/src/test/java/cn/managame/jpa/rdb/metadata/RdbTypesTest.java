package cn.managame.jpa.rdb.metadata;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RdbTypesTest {

    private enum Color { RED }

    private static final class Pojo {
    }

    @Test
    public void scalarTypesAreScalar() {
        assertTrue(RdbTypes.isScalar(int.class));
        assertTrue(RdbTypes.isScalar(Integer.class));
        assertTrue(RdbTypes.isScalar(long.class));
        assertTrue(RdbTypes.isScalar(boolean.class));
        assertTrue(RdbTypes.isScalar(Boolean.class));
        assertTrue(RdbTypes.isScalar(char.class));
        assertTrue(RdbTypes.isScalar(Character.class));
        assertTrue(RdbTypes.isScalar(String.class));
        assertTrue(RdbTypes.isScalar(Color.class));
        assertTrue(RdbTypes.isScalar(byte[].class));
        assertTrue(RdbTypes.isScalar(BigDecimal.class));
        assertTrue(RdbTypes.isScalar(UUID.class));
        assertTrue(RdbTypes.isScalar(Instant.class));
        assertTrue(RdbTypes.isScalar(LocalDate.class));
        assertTrue(RdbTypes.isScalar(java.util.Date.class));
        assertTrue(RdbTypes.isScalar(java.sql.Timestamp.class));
    }

    @Test
    public void complexTypesAreNotScalar() {
        assertFalse(RdbTypes.isScalar(Map.class));
        assertFalse(RdbTypes.isScalar(List.class));
        assertFalse(RdbTypes.isScalar(Pojo.class));
        assertFalse(RdbTypes.isScalar(int[].class));
        assertFalse(RdbTypes.isScalar(String[].class));

        assertTrue(RdbTypes.isComplex(Map.class));
        assertTrue(RdbTypes.isComplex(Pojo.class));
        assertFalse(RdbTypes.isComplex(String.class));
    }
}
