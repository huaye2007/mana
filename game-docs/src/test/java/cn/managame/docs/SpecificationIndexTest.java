package cn.managame.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every OGBS specification is reachable from the index, in its own language. AGENTS.md makes the index the
 * canonical entry point, which only holds if adding a specification cannot leave it out — and a document
 * nobody links to is one nobody finds, however carefully it was written.
 */
class SpecificationIndexTest {
    @Test void everySpecificationIsListedInTheIndexOfItsOwnLanguage() {
        Path directory = Documents.root().resolve("docs/ogbs");
        String english = Documents.read(directory.resolve("README.md"));
        String chinese = Documents.read(directory.resolve("README.zh-CN.md"));

        List<String> unlisted = new ArrayList<>();
        for (Path specification : Documents.markdown()) {
            if (!specification.getParent().equals(directory)) continue;
            String name = specification.getFileName().toString();
            if (!name.startsWith("OGBS-")) continue;        // the index itself
            String index = Documents.isChinese(specification) ? chinese : english;
            if (!index.contains("(" + name + ")"))
                unlisted.add(name + "  (not linked from README"
                        + (Documents.isChinese(specification) ? ".zh-CN" : "") + ".md)");
        }
        assertTrue(unlisted.isEmpty(), () -> unlisted.size()
                + " specification(s) are not reachable from the index:\n  " + String.join("\n  ", unlisted));
    }
}
