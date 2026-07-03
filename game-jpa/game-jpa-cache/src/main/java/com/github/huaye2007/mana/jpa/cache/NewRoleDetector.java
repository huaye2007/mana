package com.github.huaye2007.mana.jpa.cache;

@FunctionalInterface
public interface NewRoleDetector {

    boolean isNewRole(Object roleId);
}
