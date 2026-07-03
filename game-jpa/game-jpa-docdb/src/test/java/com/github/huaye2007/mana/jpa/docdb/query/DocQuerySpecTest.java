package com.github.huaye2007.mana.jpa.docdb.query;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

public class DocQuerySpecTest {

    @Test
    public void inRejectsEmptyValues() {
        assertThrows(IllegalArgumentException.class, () -> {
            new DocQuerySpec().in("roleId", List.of());
        });
    }

    @Test
    public void skipRejectsNegativeValue() {
        assertThrows(IllegalArgumentException.class, () -> {
            new DocQuerySpec().skip(-1);
        });
    }

    @Test
    public void limitRejectsNonPositiveValue() {
        assertThrows(IllegalArgumentException.class, () -> {
            new DocQuerySpec().limit(0);
        });
    }
}
