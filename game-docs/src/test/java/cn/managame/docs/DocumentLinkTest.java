package cn.managame.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every local link in every document resolves: the file exists, and a {@code #fragment} naming a Markdown
 * file matches a heading there or an explicit anchor.
 *
 * <p>This is the check that earns its place most often, because a link breaks from a distance. Renaming a
 * heading, moving a source file or splitting a module breaks links in documents nobody opened during the
 * change, and the two most recent breaks in this repository were both of that shape.
 */
class DocumentLinkTest {
    @Test void everyLocalLinkResolvesToAFileThatExists() {
        List<String> broken = new ArrayList<>();
        for (Path document : Documents.markdown()) {
            for (Documents.Link link : Documents.localLinks(Documents.read(document))) {
                Path target = target(document, link);
                if (!Files.exists(target))
                    broken.add(Documents.relative(document) + " -> " + link.target() + "  (no such file)");
            }
        }
        assertTrue(broken.isEmpty(), () -> broken.size() + " link(s) point at a file that does not exist:\n  "
                + String.join("\n  ", broken));
    }

    @Test void everyFragmentResolvesToAHeadingOrAnAnchor() {
        List<String> broken = new ArrayList<>();
        for (Path document : Documents.markdown()) {
            for (Documents.Link link : Documents.localLinks(Documents.read(document))) {
                if (link.fragment() == null || link.fragment().isEmpty()) continue;
                Path target = target(document, link);
                // A missing file is the other test's finding; do not report it twice.
                if (!Files.isRegularFile(target) || !target.getFileName().toString().endsWith(".md")) continue;
                Set<String> anchors = Documents.anchors(Documents.read(target));
                if (!anchors.contains(link.fragment()))
                    broken.add(Documents.relative(document) + " -> " + link.target() + "#" + link.fragment()
                            + "  (no such heading or anchor in " + Documents.relative(target) + ")");
            }
        }
        assertTrue(broken.isEmpty(), () -> broken.size() + " fragment(s) name nothing in their target:\n  "
                + String.join("\n  ", broken));
    }

    /** An empty target means the document links into itself. */
    private static Path target(Path document, Documents.Link link) {
        Path target = link.target().isEmpty() ? document : document.resolveSibling(link.target());
        return target.normalize();
    }
}
