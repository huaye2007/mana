package cn.managame.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both halves of every bilingual pair exist, and the two halves of a specification constrain the same
 * clauses. AGENTS.md requires one contract in two languages; nothing but attention was checking it.
 *
 * <p>Clause identity is the strongest mechanical proxy for "the same contract" available without reading
 * the prose: a clause added to one language and forgotten in the other is the failure a reader of the other
 * language would never notice, because the text they have reads complete.
 */
class DocumentPairTest {
    /** RT-DIAG-01, N-WRITE-03, B-OBS-01 … two dashes, so UTF-8 and HTTP/1.1 are not clause IDs. */
    private static final Pattern CLAUSE = Pattern.compile("\\b[A-Z]{1,2}-[A-Z]+-\\d{2}\\b");

    @Test void everyDocumentHasItsTwinInTheOtherLanguage() {
        List<String> unpaired = new ArrayList<>();
        for (Path document : Documents.markdown()) {
            Path twin = Documents.twin(document);
            if (!Files.isRegularFile(twin))
                unpaired.add(Documents.relative(document) + "  (expected " + Documents.relative(twin) + ")");
        }
        assertTrue(unpaired.isEmpty(), () -> unpaired.size()
                + " document(s) have no counterpart in the other language:\n  " + String.join("\n  ", unpaired));
    }

    @Test void bothLanguagesOfASpecificationCarryTheSameClauses() {
        List<String> mismatched = new ArrayList<>();
        for (Path english : Documents.markdown()) {
            if (Documents.isChinese(english)) continue;
            Path chinese = Documents.twin(english);
            if (!Files.isRegularFile(chinese)) continue;   // the other test's finding
            Set<String> left = clauses(english), right = clauses(chinese);
            if (left.equals(right)) continue;
            Set<String> englishOnly = new TreeSet<>(left);
            englishOnly.removeAll(right);
            Set<String> chineseOnly = new TreeSet<>(right);
            chineseOnly.removeAll(left);
            mismatched.add(Documents.relative(english)
                    + (englishOnly.isEmpty() ? "" : "\n      only in English: " + englishOnly)
                    + (chineseOnly.isEmpty() ? "" : "\n      only in Chinese: " + chineseOnly));
        }
        assertTrue(mismatched.isEmpty(), () -> mismatched.size()
                + " pair(s) do not constrain the same clauses:\n  " + String.join("\n  ", mismatched));
    }

    private static Set<String> clauses(Path document) {
        Set<String> clauses = new LinkedHashSet<>();
        Matcher matcher = CLAUSE.matcher(Documents.read(document));
        while (matcher.find()) clauses.add(matcher.group());
        return clauses;
    }
}
