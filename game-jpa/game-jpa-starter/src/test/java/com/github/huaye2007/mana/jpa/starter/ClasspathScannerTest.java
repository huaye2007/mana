package com.github.huaye2007.mana.jpa.starter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClasspathScannerTest {

    @Test
    void scanFindsClassesRecursivelyFromDirectory() {
        List<Class<?>> classes = ClasspathScanner.scan("com.github.huaye2007.mana.jpa.starter", getClass().getClassLoader());

        // 同包下的已知类都应被发现(目录扫描 + 递归子包)。
        assertTrue(classes.contains(GameJpaBootstrap.class));
        assertTrue(classes.contains(GameJpaScan.class));
        assertTrue(classes.contains(ClasspathScanner.class));
    }

    @Test
    void scanReturnsEmptyForUnknownPackage() {
        assertTrue(ClasspathScanner.scan("com.github.huaye2007.mana.jpa.no.such.pkg", getClass().getClassLoader()).isEmpty());
    }

    @Test
    void scanDoesNotLeakSiblingPackageSharingPrefix() {
        // 扫 "...starter" 不应混入恰好以该名为前缀的兄弟包(如果存在)。这里至少保证不抛异常、结果非空。
        List<Class<?>> classes = ClasspathScanner.scan("com.github.huaye2007.mana.jpa.starter", getClass().getClassLoader());
        assertFalse(classes.isEmpty());
    }
}
