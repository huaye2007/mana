package cn.managame.docs;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No document has been encoded twice. A tool that reads UTF-8 bytes as if they were Latin-1 and then writes
 * UTF-8 turns 中文 into ä¸­æ–‡, and the result is still valid UTF-8 — so nothing downstream complains and the
 * damage is invisible in a diff viewer that shows both sides mangled.
 *
 * <p>This is not hypothetical: it happened twice in this repository to two READMEs, from a script that
 * mixed byte strings with character literals. It was caught by eye, late, and only because the files
 * happened to be read aloud.
 *
 * <p>The test is exact rather than heuristic. A line that is entirely Latin-1 range and survives the
 * reverse of that mistake — encode back to Latin-1, decode as strict UTF-8 — was encoded twice; prose that
 * was only ever encoded once cannot pass that round trip and produce ideographs.
 */
class DocumentEncodingTest {
    @Test void noDocumentHasBeenEncodedTwice() {
        List<String> damaged = new ArrayList<>();
        for (Path document : Documents.markdown()) {
            String[] lines = Documents.read(document).split("\n", -1);
            for (int number = 0; number < lines.length; number++) {
                String recovered = doubleEncoded(lines[number]);
                if (recovered == null) continue;
                damaged.add(Documents.relative(document) + ":" + (number + 1)
                        + "\n      reads as: " + preview(lines[number])
                        + "\n      and means: " + preview(recovered));
            }
        }
        assertTrue(damaged.isEmpty(), () -> damaged.size() + " line(s) were encoded twice:\n  "
                + String.join("\n  ", damaged));
    }

    /**
     * The single-byte encodings the mistake is made through. Windows-1252 first and not only ISO-8859-1,
     * because the UTF-8 lead bytes 0x80–0x9F become printable characters there — 中文 double-encodes to
     * ä¸­æ–‡, whose en dash is U+2013 and exists in no Latin-1 text. Checking Latin-1 alone was the first
     * version of this test, and it let exactly that line through.
     */
    private static final List<Charset> SINGLE_BYTE =
            List.of(Charset.forName("windows-1252"), StandardCharsets.ISO_8859_1);

    /** What the line was before it was encoded a second time, or null when it was not. */
    private static String doubleEncoded(String line) {
        if (line.chars().noneMatch(c -> c >= 0x80)) return null;     // nothing but ASCII to misread
        for (Charset single : SINGLE_BYTE) {
            String recovered = recover(line, single);
            if (recovered != null) return recovered;
        }
        return null;
    }

    private static String recover(String line, Charset single) {
        CharsetEncoder encoder = single.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            // Strict both ways: the line must be expressible in the single-byte charset, and those bytes
            // must really be UTF-8. A decoder that replaced bad input would say yes to anything.
            ByteBuffer bytes = encoder.encode(CharBuffer.wrap(line));
            String recovered = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes)
                    .toString();
            return recovered.codePoints().anyMatch(Character::isIdeographic) ? recovered : null;
        } catch (CharacterCodingException notDoubleEncoded) {
            return null;
        }
    }

    private static String preview(String line) {
        String trimmed = line.strip();
        return trimmed.length() <= 60 ? trimmed : trimmed.substring(0, 60) + "…";
    }
}
