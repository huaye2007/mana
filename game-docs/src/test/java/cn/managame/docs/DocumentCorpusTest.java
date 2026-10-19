package cn.managame.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The corpus every other check reads is actually there.
 *
 * <p>Without this, each of the other checks passes trivially on an empty list — which is exactly what
 * happened the first time they ran, when the root was resolved to a module and three of them reported
 * success over nothing at all. A check that cannot fail is worse than no check, because it is believed.
 */
class DocumentCorpusTest {
    @Test void theRepositoryRootIsTheAggregatorAndNotAModule() {
        Path root = Documents.root();
        assertTrue(Files.isRegularFile(root.resolve("AGENTS.md")), "AGENTS.md is at the root; found " + root);
        assertTrue(Files.isDirectory(root.resolve("docs/ogbs")), "docs/ogbs is at the root; found " + root);
        assertTrue(Documents.read(root.resolve("pom.xml")).contains("<modules>"), "the root pom declares modules");
    }

    @Test void theCorpusIsLargeEnoughThatTheOtherChecksCannotPassOverNothing() {
        List<Path> markdown = Documents.markdown();
        assertTrue(markdown.size() >= 40, "found only " + markdown.size() + " Markdown files");
        List<Path> java = Documents.java();
        assertTrue(java.size() >= 200, "found only " + java.size() + " Java sources");

        long specifications = markdown.stream()
                .filter(path -> path.getFileName().toString().startsWith("OGBS-"))
                .count();
        // Eight components, two layers each, two languages: the index table says what the number must be.
        assertEquals(0, specifications % 4, specifications + " OGBS files is not a whole number of pairs");
        assertTrue(specifications >= 32, "found only " + specifications + " OGBS specification files");
    }
}
