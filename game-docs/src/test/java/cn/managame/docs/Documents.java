package cn.managame.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Where the repository's documents are, and the little that every check needs to read them. */
final class Documents {
    private Documents() {}

    private static final Path ROOT = findRoot();

    /**
     * Walks up from the working directory to the aggregator, so a check works from any module.
     *
     * <p>The aggregator is the pom that declares modules. Matching the artifactId alone would stop at the
     * first module instead, because every module names mana3 as its parent.
     */
    private static Path findRoot() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            Path pom = directory.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) continue;
            String text = read(pom);
            if (text.contains("<artifactId>mana3</artifactId>") && text.contains("<modules>")) return directory;
        }
        throw new IllegalStateException("Repository root not found above " + Path.of("").toAbsolutePath());
    }

    static Path root() { return ROOT; }

    /** Every Markdown file the repository keeps, excluding build output. */
    static List<Path> markdown() {
        try (Stream<Path> tree = Files.walk(ROOT)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(path -> {
                        String relative = relative(path);
                        return !relative.startsWith(".git/") && !relative.contains("/target/");
                    })
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Every Java source the repository keeps, main and test, excluding generated output. */
    static List<Path> java() {
        try (Stream<Path> tree = Files.walk(ROOT)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !relative(path).contains("/target/"))
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    static String read(Path path) {
        try { return Files.readString(path, StandardCharsets.UTF_8); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    /** The path a failure message should print: relative to the root, with forward slashes. */
    static String relative(Path path) {
        return ROOT.relativize(path).toString().replace('\\', '/');
    }

    /** The Chinese twin of an English document, or the English twin of a Chinese one. */
    static Path twin(Path document) {
        String name = document.getFileName().toString();
        String other = name.endsWith(".zh-CN.md")
                ? name.substring(0, name.length() - ".zh-CN.md".length()) + ".md"
                : name.substring(0, name.length() - ".md".length()) + ".zh-CN.md";
        return document.resolveSibling(other);
    }

    static boolean isChinese(Path document) {
        return document.getFileName().toString().endsWith(".zh-CN.md");
    }

    // ---------------------------------------------------------------- anchors

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*?)\\s*$", Pattern.MULTILINE);
    private static final Pattern EXPLICIT_ANCHOR = Pattern.compile("<a\\s+id=\"([^\"]+)\"");
    private static final Pattern LINK_TEXT = Pattern.compile("\\[([^]]*)]\\([^)]*\\)");
    /** CJK and full-width punctuation, plus the dashes and ellipsis these documents use. */
    private static final Pattern DROPPED = Pattern.compile("[\\u3000-\\u303F\\uFF00-\\uFFEF\\u2018-\\u201D\\u2013\\u2014\\u2026]");
    private static final Pattern ASCII_PUNCTUATION = Pattern.compile("[^\\w\\s\\u0080-\\uFFFF-]");

    /**
     * Every fragment a link may target: one slug per heading, plus every explicit {@code <a id>}.
     *
     * <p>The slug follows the rule a Markdown renderer applies — lowercase, punctuation dropped, spaces
     * to hyphens — including for Chinese headings, where the ideographs survive and the full-width
     * punctuation between them does not.
     */
    static Set<String> anchors(String markdown) {
        Set<String> anchors = new LinkedHashSet<>();
        Matcher headings = HEADING.matcher(markdown);
        while (headings.find()) anchors.add(slug(headings.group(1)));
        Matcher explicit = EXPLICIT_ANCHOR.matcher(markdown);
        while (explicit.find()) anchors.add(explicit.group(1));
        return anchors;
    }

    static String slug(String heading) {
        String text = heading.replace("`", "");
        text = LINK_TEXT.matcher(text).replaceAll("$1");
        text = text.replace("**", "").replace("*", "");
        text = text.toLowerCase(java.util.Locale.ROOT);
        text = DROPPED.matcher(text).replaceAll("");
        text = ASCII_PUNCTUATION.matcher(text).replaceAll("");
        return text.trim().replaceAll("\\s+", "-");
    }

    // ---------------------------------------------------------------- links

    /** One local Markdown link: where it points and, when it has one, the fragment it expects there. */
    record Link(String target, String fragment) {}

    private static final Pattern LINK = Pattern.compile("\\[[^]]*]\\(([^)\\s]+)\\)");

    /** Local links only; a document is free to point anywhere on the web and this says nothing about it. */
    static List<Link> localLinks(String markdown) {
        List<Link> links = new ArrayList<>();
        Matcher matcher = LINK.matcher(markdown);
        while (matcher.find()) {
            String href = matcher.group(1);
            if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")) continue;
            int hash = href.indexOf('#');
            if (hash < 0) links.add(new Link(href, null));
            else links.add(new Link(href.substring(0, hash), href.substring(hash + 1)));
        }
        return links;
    }
}
