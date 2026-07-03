package com.github.huaye2007.mana.jpa.docdb.metadata;

import com.github.huaye2007.mana.jpa.docdb.annotation.Document;
import com.github.huaye2007.mana.jpa.docdb.annotation.Id;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class DocEntityMetadataResolverTest {

    @Test
    public void ignoresStaticAndTransientFields() {
        DocEntityMetadata metadata = new DocEntityMetadataResolver().resolve(PlayerProfile.class);

        assertEquals(2, metadata.fields().size());
        assertNotNull(metadata.fieldByPropertyName("id"));
        assertNotNull(metadata.fieldByPropertyName("name"));
        assertNull(metadata.fieldByPropertyName("TYPE"));
        assertNull(metadata.fieldByPropertyName("scratch"));
    }

    @Document(collection = "player_profile")
    private static class PlayerProfile {
        private static final String TYPE = "profile";

        @Id
        private long id;
        private String name;
        private transient String scratch;
    }
}
