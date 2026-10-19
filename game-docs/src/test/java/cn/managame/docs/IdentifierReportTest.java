package cn.managame.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prints every backticked identifier a specification names that the source tree does not declare, and fails
 * the build for none of them.
 *
 * <p>It is a report rather than a gate on purpose. The scan cannot tell a stale API name from a JDK or Netty
 * type the prose legitimately mentions, so roughly half of what it finds is correct prose — `VALUES`,
 * `_id`, `System.nanoTime()`, an environment variable. Gating that would tax every specification that
 * mentions a type this repository does not own, and the tax would be paid by adding names to an ignore list
 * rather than by fixing anything.
 *
 * <p>What it is good at is the opposite direction: it reads every name in all sixteen specification files
 * against the source and so confirms the ones that are right. The assertion below is on the scanner, not on
 * its findings — a report that silently stopped scanning would otherwise look like a clean one.
 */
class IdentifierReportTest {
    /** Types and members the prose names that this repository does not declare and should not have to. */
    private static final Set<String> FOREIGN = Set.of(
            "true", "false", "null", "void", "int", "long", "boolean", "byte", "short", "double", "float",
            "char", "String", "Object", "Class", "List", "Map", "Set", "Optional", "Duration", "Instant",
            "Clock", "Thread", "Runnable", "Supplier", "Function", "Consumer", "Exception", "Throwable",
            "Error", "RuntimeException", "IllegalArgumentException", "IllegalStateException",
            "NullPointerException", "UnsupportedOperationException", "ClassCastException",
            "InterruptedException", "AutoCloseable", "Comparable", "Iterable", "Iterator", "Stream",
            "Collection", "ByteBuf", "ByteBufAllocator", "Unpooled", "ByteBufUtil", "ReferenceCountUtil",
            "FullHttpRequest", "FullHttpResponse", "HttpMethod", "HttpVersion", "HttpResponseStatus",
            "HttpHeaderNames", "HttpUtil", "DefaultFullHttpResponse", "DefaultFullHttpRequest",
            "ChannelOption", "EventLoop", "EventLoopGroup", "NioEventLoopGroup", "Channel", "ChannelHandler",
            "HashedWheelTimer", "AtomicBoolean", "AtomicInteger", "AtomicLong", "LongAdder",
            "ConcurrentHashMap", "CountDownLatch", "TimeUnit", "DataSource", "Connection", "ResultSet",
            "PreparedStatement", "SQLException", "Bean", "Beans", "Configuration", "Import", "Primary",
            "Qualifier", "Component", "Repository", "Value", "PropertySource", "ObjectProvider",
            "Environment", "ApplicationContext", "AnnotationConfigApplicationContext", "SmartLifecycle",
            "ContextClosedEvent", "ApplicationListener", "Ordered", "BeanFactoryPostProcessor",
            "ConfigurableListableBeanFactory", "AopUtils", "MapPropertySource", "System",
            "GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "OK", "JSON", "HTTP", "TCP", "TLS",
            "WS", "WSS", "RPC", "CPU", "UNKNOWN", "VALUES", "Runtime", "finally", "network", "_id");

    private static final Pattern DECLARATION = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Z]\\w*)");
    private static final Pattern MEMBER = Pattern.compile("\\b([a-z]\\w*)\\s*\\(");
    private static final Pattern CONSTANT = Pattern.compile("\\b([A-Z][A-Z0-9_]{2,})\\b");
    private static final Pattern FIELD = Pattern.compile("\\b([a-z]\\w*)\\s*[=;]");
    /** Parameter names, which none of the patterns above reach: {@code long elapsedNanos)}. */
    private static final Pattern PARAMETER = Pattern.compile("\\b\\w+\\s+([a-z]\\w*)\\s*[,)]");
    private static final Pattern FENCED = Pattern.compile("```.*?```", Pattern.DOTALL);
    private static final Pattern BACKTICKED = Pattern.compile("`([^`\\n]+)`");

    @Test void unresolvedIdentifiersAreReportedWithoutFailingTheBuild() {
        Set<String> declared = declared();
        Map<String, Set<String>> unresolved = new LinkedHashMap<>();
        int checked = 0;

        for (Path document : Documents.markdown()) {
            String name = document.getFileName().toString();
            if (!name.startsWith("OGBS-")) continue;
            // Fenced examples may invent names on purpose; only the prose claims an API exists.
            String prose = FENCED.matcher(Documents.read(document)).replaceAll("");
            Matcher matcher = BACKTICKED.matcher(prose);
            while (matcher.find()) {
                for (String identifier : identifiers(matcher.group(1))) {
                    checked++;
                    if (declared.contains(identifier) || FOREIGN.contains(identifier)) continue;
                    unresolved.computeIfAbsent(identifier, ignored -> new LinkedHashSet<>())
                            .add(Documents.relative(document));
                }
            }
        }

        StringBuilder report = new StringBuilder("\n[game-docs] identifiers named in specifications: ")
                .append(checked).append(" checked, ").append(unresolved.size()).append(" not declared in the source.\n");
        unresolved.forEach((identifier, documents) ->
                report.append("  ").append(identifier).append("  ").append(documents).append('\n'));
        report.append("  (a report, not a gate: prose may name a JDK, Netty or Spring type this repository"
                + " does not declare)\n");
        System.out.print(report);

        assertTrue(checked > 200, "the scan found only " + checked
                + " identifiers, so it is broken rather than the specifications bare");
    }

    /** Every type, method, constant and field the source tree declares, main and test. */
    private static Set<String> declared() {
        Set<String> declared = new LinkedHashSet<>();
        for (Path source : Documents.java()) {
            String text = Documents.read(source);
            // Over-collecting is the safe direction here: this set only ever suppresses report noise, so a
            // loose pattern costs nothing while a missing one reports a name that does exist.
            for (Pattern pattern : List.of(DECLARATION, MEMBER, CONSTANT, FIELD, PARAMETER)) {
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) declared.add(matcher.group(1));
            }
        }
        return declared;
    }

    /** The identifiers inside one backticked span, or none when it is prose rather than a name. */
    private static Set<String> identifiers(String token) {
        if (token.isBlank() || token.contains(" ")) return Set.of();
        String name = token.endsWith(")") && token.contains("(") ? token.substring(0, token.indexOf('(')) : token;
        if (!name.matches("[\\w.]+")) return Set.of();
        // A dotted name with no parentheses and no capitalised segment is a metric or property name
        // (`writes.notwritable`, `game.http.port`), not a reference to an API this repository declares.
        if (!token.contains("(") && name.contains(".") && name.chars().noneMatch(Character::isUpperCase))
            return Set.of();
        Set<String> identifiers = new LinkedHashSet<>();
        boolean qualified = name.startsWith("cn.managame.");
        for (String part : name.split("\\.")) {
            if (part.length() <= 2) continue;
            // A package segment of our own namespace is a directory, not an API name.
            if (qualified && !Character.isUpperCase(part.charAt(0))) continue;
            if (Character.isLowerCase(part.charAt(0)) && part.length() < 5) continue;
            identifiers.add(part);
        }
        return identifiers;
    }
}
