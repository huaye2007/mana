package cn.managame.jpa.cache;

@FunctionalInterface
public interface NewRoleDetector {

    boolean isNewRole(Object roleId);
}
