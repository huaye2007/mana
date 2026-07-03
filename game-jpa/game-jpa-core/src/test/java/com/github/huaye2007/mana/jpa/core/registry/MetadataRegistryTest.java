package com.github.huaye2007.mana.jpa.core.registry;

import com.github.huaye2007.mana.jpa.core.bootstrap.ModelType;
import com.github.huaye2007.mana.jpa.core.bootstrap.ModelTypes;
import com.github.huaye2007.mana.jpa.core.metadata.EntityMetadata;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;

public class MetadataRegistryTest {

    @Test
    public void repeatedRegistrationReplacesModelIndexEntry() {
        MetadataRegistry registry = new MetadataRegistry();
        TestMetadata first = new TestMetadata(Player.class, ModelTypes.RDB, "player_old");
        TestMetadata second = new TestMetadata(Player.class, ModelTypes.RDB, "player_new");

        registry.register(first);
        registry.register(second);

        assertSame(second, registry.get(Player.class).orElseThrow());
        assertEquals(List.of(second), registry.getByModel(ModelTypes.RDB));
    }

    @Test
    public void registrationCanMoveEntityBetweenModelTypes() {
        MetadataRegistry registry = new MetadataRegistry();
        TestMetadata rdb = new TestMetadata(Player.class, ModelTypes.RDB, "player");
        TestMetadata doc = new TestMetadata(Player.class, ModelTypes.DOCDB, "player_doc");

        registry.register(rdb);
        registry.register(doc);

        assertTrue(registry.getByModel(ModelTypes.RDB).isEmpty());
        assertEquals(List.of(doc), registry.getByModel(ModelTypes.DOCDB));
    }

    @Test
    public void rejectsNullMetadata() {
        assertThrows(NullPointerException.class, () -> {
            new MetadataRegistry().register(null);
        });
    }

    private record TestMetadata(Class<?> entityType, ModelType modelType, String logicalName)
            implements EntityMetadata {
        @Override
        public com.github.huaye2007.mana.jpa.core.metadata.FieldMetadata idField() {
            return null;
        }

        @Override
        public com.github.huaye2007.mana.jpa.core.metadata.FieldMetadata shardKeyField() {
            return null;
        }

        @Override
        public com.github.huaye2007.mana.jpa.core.metadata.FieldMetadata roleIdField() {
            return null;
        }
    }

    private static class Player {
    }
}
