package com.github.huaye2007.mana.jpa.docdb.mongo;

import com.github.huaye2007.mana.jpa.core.executor.ExecutorContext;
import com.github.huaye2007.mana.jpa.docdb.annotation.Document;
import com.github.huaye2007.mana.jpa.docdb.annotation.Id;
import com.github.huaye2007.mana.jpa.docdb.annotation.Indexed;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocEntityMetadata;
import com.github.huaye2007.mana.jpa.docdb.metadata.DocEntityMetadataResolver;
import com.github.huaye2007.mana.jpa.docdb.query.DocQuerySpec;
import com.github.huaye2007.mana.jpa.docdb.query.DocUpdateSpec;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

public class MongoDocExecutorIT {

    private static final MongoDBContainer MONGO =
            new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    private static MongoDocExecutor executor;
    private static DocEntityMetadata metadata;

    @BeforeAll
    public static void startMongo() {
        MONGO.start();
        executor = new MongoDocExecutor(MONGO.getConnectionString(), "game_jpa_it")
                .autoCreateIndexes(true);
        metadata = new DocEntityMetadataResolver().resolve(PlayerProfile.class);
    }

    @AfterAll
    public static void stopMongo() {
        if (executor != null) {
            executor.close();
        }
        MONGO.stop();
    }

    @Test
    public void executesCrudQueryUpdateAndBulkWriteAgainstRealMongo() {
        executor.insert(metadata, new PlayerProfile(1L, "ada", 10), ExecutorContext.defaultContext());

        PlayerProfile loaded = executor.findById(metadata, 1L, ExecutorContext.defaultContext());
        assertEquals("ada", loaded.name);

        executor.update(metadata, 1L,
                new DocUpdateSpec().set("name", "grace").inc("level", 5),
                ExecutorContext.defaultContext());
        PlayerProfile updated = executor.findById(metadata, 1L, ExecutorContext.defaultContext());
        assertEquals("grace", updated.name);
        assertEquals(15, updated.level);

        executor.batchSave(metadata, List.of(
                new PlayerProfile(2L, "linus", 30),
                new PlayerProfile(3L, "margaret", 40)), ExecutorContext.defaultContext());

        List<PlayerProfile> highLevel = executor.find(metadata,
                new DocQuerySpec().in("name", List.of("linus", "margaret")).sortAsc("level"),
                ExecutorContext.defaultContext());
        assertEquals(2, highLevel.size());
        assertEquals("linus", highLevel.get(0).name);

        executor.batchDelete(metadata, List.of(2L, 3L), ExecutorContext.defaultContext());
        assertNull(executor.findById(metadata, 2L, ExecutorContext.defaultContext()));
        assertNull(executor.findById(metadata, 3L, ExecutorContext.defaultContext()));
    }

    @Document(collection = "player_profile_it")
    public static class PlayerProfile {
        @Id
        private long id;
        @Indexed
        private String name;
        private int level;

        public PlayerProfile() {
        }

        public PlayerProfile(long id, String name, int level) {
            this.id = id;
            this.name = name;
            this.level = level;
        }
    }
}
