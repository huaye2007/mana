package cn.managame.jpa.core.registry;

import cn.managame.jpa.core.bootstrap.ModelType;
import cn.managame.jpa.core.bootstrap.ModelTypes;
import cn.managame.jpa.core.metadata.EntityMetadata;
import cn.managame.jpa.core.metadata.FieldMetadata;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class DataSourceBindingTest {

    @Test
    public void annotationWinsOverRegistration() {
        DataSourceBinding binding = new DataSourceBinding();
        binding.register(DataSourceBindingTest.class, "by-class");
        // 注解（metadata.dataSourceName 非默认）优先于按类注册
        assertEquals("log", binding.resolve(meta(DataSourceBindingTest.class, "log")));
    }

    @Test
    public void classBindingUsedWhenNoAnnotation() {
        DataSourceBinding binding = new DataSourceBinding();
        binding.register(DataSourceBindingTest.class, "db1");
        assertEquals("db1", binding.resolve(meta(DataSourceBindingTest.class, "default")));
    }

    @Test
    public void longestPackagePrefixWins() {
        DataSourceBinding binding = new DataSourceBinding();
        binding.registerPackage("cn.managame.jpa", "jpa");
        binding.registerPackage("cn.managame.jpa.core", "core");
        // 本测试类在 cn.managame.jpa.core.registry，最长前缀命中 "cn.managame.jpa.core"
        assertEquals("core", binding.resolve(meta(DataSourceBindingTest.class, "default")));
    }

    @Test
    public void defaultWhenNothingMatches() {
        DataSourceBinding binding = new DataSourceBinding();
        assertEquals("default", binding.resolve(meta(DataSourceBindingTest.class, "default")));
    }

    private static EntityMetadata meta(Class<?> type, String dataSource) {
        return new Meta(type, dataSource);
    }

    private record Meta(Class<?> entityType, String ds) implements EntityMetadata {
        @Override
        public ModelType modelType() {
            return ModelTypes.RDB;
        }

        @Override
        public String logicalName() {
            return "t";
        }

        @Override
        public String dataSourceName() {
            return ds;
        }

        @Override
        public FieldMetadata idField() {
            return null;
        }

        @Override
        public FieldMetadata shardKeyField() {
            return null;
        }

        @Override
        public FieldMetadata roleIdField() {
            return null;
        }
    }
}
