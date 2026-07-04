package cn.managame.jpa.core.bootstrap;

/**
 * 内置模型类型枚举。
 */
public enum ModelTypes implements ModelType {

    RDB("rdb"),
    DOCDB("docdb");

    private final String modelName;

    ModelTypes(String name) {
        this.modelName = name;
    }

    @Override
    public String modelName() {
        return modelName;
    }
}
