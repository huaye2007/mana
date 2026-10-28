package cn.managame.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Runs the benchmarks of framework components and compares each result, with the baseline recorded for its
 * component or, with {@code --against}, with another build of the benchmarks run in turn in the same session:
 *
 * <pre>
 * java -Djdk.net.unixdomain.tmpdir=D:\tmp -jar target/benchmarks.jar rpc network [--save] [--include=Latency] [--threshold=10]
 * java -Djdk.net.unixdomain.tmpdir=D:\tmp -jar target/benchmarks.jar rpc --against=before.jar
 * </pre>
 *
 * A component's benchmarks are the classes in {@code cn.managame.benchmark.<component>}; {@code all} runs every
 * component. Its baseline is {@code results/<component>.csv}: one line per benchmark and parameter set with the
 * score and its error, then the {@link #COLUMNS}: bytes allocated per operation and per second, garbage
 * collections and their time, for sampled latencies the median and 99th percentile, and what {@link CostProfiler}
 * measures: CPU per operation by kind of thread, Netty's direct memory, bytes on the wire per operation and how busy
 * the rest of the machine was. A line naming the machine it was recorded on heads it; files from before a column
 * existed leave it empty. A result is better or worse when it moved by more than the threshold, 10 percent by
 * default, and by more than the two errors together; allocation, when it moved by more than the threshold and 32
 * bytes; bytes on the wire, by the threshold and 4 bytes. A score that got worse while other programs used more than
 * a tenth of the machine is unsure instead. The exit code is 1 when anything got worse, otherwise 3 when anything
 * is unsure. {@code --save} records the run as the new baseline, unless the machine was that busy; saving a run of
 * some benchmarks replaces their lines and keeps the others.
 *
 * <p>A machine that is not steady, such as a laptop whose busy foreground apps push the benchmarks onto slower
 * cores, can move every result at once. {@code --against=<jar>} tells that from a change of code: the other jar,
 * built from the code before the change, and this one run in turn, the other first, twice each, so that both meet
 * the machine as it is during the session. A result is better or worse only when it moved by more than the
 * threshold, both runs of this jar fall on the same side of both runs of the other, and the two means differ by
 * more than the other jar moved between its own runs. One that moved by more than the threshold, but not that
 * clearly, is {@code unsure}: the machine moved as much as the result did. Allocation is judged as against a
 * baseline. Nothing is saved in this mode; the exit code is 1 when anything got worse, otherwise 3 when anything is
 * unsure.
 */
public final class Bench {
    static final List<String> COMPONENTS = List.of("common", "network", "runtime", "rpc");
    private static final String PACKAGE = "cn.managame.benchmark.";
    /** How many times each jar runs with {@code --against}. */
    private static final int ROUNDS = 2;
    /** Above this share of the machine used by other programs, a run's scores are unsure and are not saved. */
    private static final double BUSY_LIMIT = 10;
    private static final String ALLOC = "alloc_bytes_per_op", P50 = "p50", P99 = "p99", ALLOC_RATE = "alloc_mb_per_s",
            GC_COUNT = "gc_count", GC_MS = "gc_ms", CPU = "cpu_ns_per_op", CPU_CALLER = "cpu_caller_ns_per_op",
            CPU_EVENT_LOOP = "cpu_eventloop_ns_per_op", CPU_OTHER = "cpu_other_ns_per_op", CPU_JVM = "cpu_jvm_ns_per_op",
            DIRECT = "direct_mib", SENT = "sent_bytes_per_op", RECEIVED = "received_bytes_per_op", BUSY = "busy_percent",
            PEER_CPU = "peer_cpu_ns_per_op", PEER_CPU_EVENT_LOOP = "peer_cpu_eventloop_ns_per_op",
            PEER_CPU_OTHER = "peer_cpu_other_ns_per_op", PEER_CPU_JVM = "peer_cpu_jvm_ns_per_op",
            PEER_ALLOC = "peer_alloc_bytes_per_op", PEER_GC_COUNT = "peer_gc_count", PEER_GC_MS = "peer_gc_ms",
            PEER_DIRECT = "peer_direct_mib";
    /** What a result records besides its score, in the order of the baseline's columns; the peer's when it has one. */
    static final List<String> COLUMNS = List.of(ALLOC, P50, P99, ALLOC_RATE, GC_COUNT, GC_MS, CPU, CPU_CALLER,
            CPU_EVENT_LOOP, CPU_OTHER, CPU_JVM, DIRECT, SENT, RECEIVED, BUSY, PEER_CPU, PEER_CPU_EVENT_LOOP,
            PEER_CPU_OTHER, PEER_CPU_JVM, PEER_ALLOC, PEER_GC_COUNT, PEER_GC_MS, PEER_DIRECT);
    /** The JMH result each column but the percentiles comes from: the GC profiler's, then {@link CostProfiler}'s. */
    private static final Map<String, String> SOURCES = sources(ALLOC, "gc.alloc.rate.norm", ALLOC_RATE, "gc.alloc.rate",
            GC_COUNT, "gc.count", GC_MS, "gc.time", CPU, "cost.cpu", CPU_CALLER, "cost.cpu.caller",
            CPU_EVENT_LOOP, "cost.cpu.eventloop", CPU_OTHER, "cost.cpu.other", CPU_JVM, "cost.cpu.jvm",
            DIRECT, "cost.direct", SENT, "cost.net.sent", RECEIVED, "cost.net.received", BUSY, "cost.busy",
            PEER_CPU, "cost.peer.cpu", PEER_CPU_EVENT_LOOP, "cost.peer.cpu.eventloop", PEER_CPU_OTHER, "cost.peer.cpu.other",
            PEER_CPU_JVM, "cost.peer.cpu.jvm", PEER_ALLOC, "cost.peer.alloc", PEER_GC_COUNT, "cost.peer.gc.count",
            PEER_GC_MS, "cost.peer.gc.time", PEER_DIRECT, "cost.peer.direct");

    private Bench() {}

    private static Map<String, String> sources(String... pairs) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) sources.put(pairs[i], pairs[i + 1]);
        return sources;
    }

    public static void main(String[] args) throws Exception {
        List<String> components = new ArrayList<>();
        boolean save = false;
        double threshold = 10;
        String include = "";
        Path results = Path.of("results"), against = null;
        for (String arg : args) {
            if (arg.equals("--save")) save = true;
            else if (arg.startsWith("--threshold=")) threshold = Double.parseDouble(arg.substring(12));
            else if (arg.startsWith("--include=")) include = arg.substring(10);
            else if (arg.startsWith("--results=")) results = Path.of(arg.substring(10));
            else if (arg.startsWith("--against=")) against = Path.of(arg.substring(10));
            else if (arg.equals("all")) components.addAll(COMPONENTS);
            else if (COMPONENTS.contains(arg)) components.add(arg);
            else usage("Unknown argument " + arg);
        }
        if (components.isEmpty()) usage("Name the components to run");
        if (against != null && save) usage("--save records a run compared with the baseline, not one --against another jar");
        if (against != null && !Files.isRegularFile(against)) usage("No jar at " + against);
        boolean worse = false, unsure = false;
        for (String component : components) {
            Outcome outcome;
            if (against != null) {
                outcome = against(component, against, include, threshold / 100);
            } else {
                List<Row> now = rows(component, run(component, include));
                Path file = results.resolve(component + ".csv");
                Baseline baseline = Baseline.read(file);
                outcome = compare(component, baseline, now, threshold / 100);
                double busiest = now.stream().mapToDouble(row -> row.get(BUSY)).filter(Double::isFinite).max().orElse(0);
                if (save && busiest > BUSY_LIMIT) {
                    System.out.printf(Locale.ROOT, "Not saved: other programs kept the machine up to %.0f%% busy; run again when it is quiet%n", busiest);
                } else if (save) {
                    Files.createDirectories(results);
                    baseline.merge(now).write(file, environment());
                    System.out.println("Saved " + file);
                }
            }
            worse |= outcome.worse();
            unsure |= outcome.unsure();
        }
        System.exit(worse ? 1 : unsure ? 3 : 0);
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("Usage: java -jar benchmarks.jar <" + String.join("|", COMPONENTS) + "|all>... "
                + "[--save] [--include=<regex>] [--threshold=<percent>] [--results=<dir>] [--against=<jar>]");
        System.exit(2);
    }

    /** One fork per benchmark, with the JVM settings every recorded run uses and both profilers. */
    private static Collection<RunResult> run(String component, String include) throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-Xms1g", "-Xmx1g", "-Dio.netty.leakDetection.level=disabled"));
        String unixTmp = System.getProperty("jdk.net.unixdomain.tmpdir");
        if (unixTmp != null) jvm.add("-Djdk.net.unixdomain.tmpdir=" + unixTmp);
        ChainedOptionsBuilder options = new OptionsBuilder()
                .include(PACKAGE.replace(".", "\\.") + component + "\\..*" + include + ".*")
                .addProfiler(GCProfiler.class)
                .addProfiler(CostProfiler.class)
                .jvmArgsAppend(jvm.toArray(String[]::new))
                .shouldFailOnError(true);
        return new Runner(options.build()).run();
    }

    private static List<Row> rows(String component, Collection<RunResult> runs) {
        List<Row> rows = new ArrayList<>();
        for (RunResult run : runs) {
            BenchmarkParams params = run.getParams();
            Result<?> primary = run.getPrimaryResult();
            List<String> values = new ArrayList<>();
            for (String key : params.getParamsKeys()) values.add(key + "=" + params.getParam(key));
            Map<String, Double> measures = new LinkedHashMap<>();
            SOURCES.forEach((column, label) -> {
                Result<?> secondary = run.getSecondaryResults().get(label);
                if (secondary != null) measures.put(column, secondary.getScore());
            });
            if (params.getMode() == Mode.SampleTime) {
                measures.put(P50, primary.getStatistics().getPercentile(50));
                measures.put(P99, primary.getStatistics().getPercentile(99));
            }
            rows.add(new Row(params.getBenchmark().substring((PACKAGE + component + ".").length()),
                    String.join(" ", values), params.getMode().shortLabel(), primary.getScore(),
                    finite(primary.getScoreError()), primary.getScoreUnit(), measures));
        }
        return rows;
    }

    /** Prints each result next to its baseline, with what it cost. */
    private static Outcome compare(String component, Baseline baseline, List<Row> now, double threshold) {
        System.out.println();
        System.out.println("== " + component + (baseline.rows().isEmpty() ? ": no baseline yet"
                : ": against the baseline " + baseline.recorded()));
        System.out.println("   now " + environment());
        boolean worse = false, unsure = false;
        for (Row row : now) {
            Row before = baseline.rows().get(row.key());
            String verdict = "new";
            String change = "";
            if (before != null) {
                double delta = (row.score() - before.score()) / before.score();
                boolean moved = Math.abs(delta) > threshold && Math.abs(row.score() - before.score()) > row.error() + before.error();
                boolean better = row.higherIsBetter() == delta > 0;
                verdict = busy(!moved ? "same" : better ? "better" : "WORSE", row.get(BUSY)) + costChanges(before, row, threshold);
                worse |= verdict.contains("WORSE") || verdict.contains("MORE");
                unsure |= verdict.startsWith("unsure");
                change = String.format(Locale.ROOT, "%s -> ", number(before.score()));
            }
            System.out.printf(Locale.ROOT, "%-38s %-42s %s%s %s  %s  alloc %s B/op%s%n",
                    row.benchmark(), row.params(), change, number(row.score()), row.unit(),
                    before == null ? verdict : String.format(Locale.ROOT, "%+.1f%% %s", 100 * (row.score() - before.score()) / before.score(), verdict),
                    number(row.get(ALLOC)), Double.isNaN(row.get(P99)) ? "" : "  p50 " + number(row.get(P50)) + " p99 " + number(row.get(P99)));
            System.out.println(costs(before, row));
        }
        if (unsure) System.out.println("unsure: other programs kept the machine busy while it ran; run again when it is "
                + "quiet, or --against the jar of the code before");
        return new Outcome(worse, unsure);
    }

    /** A score that got worse while other programs kept the machine busy may be theirs: it is unsure instead. */
    private static String busy(String verdict, double busyPercent) {
        return verdict.equals("WORSE") && busyPercent > BUSY_LIMIT
                ? String.format(Locale.ROOT, "unsure (machine %.0f%% busy)", busyPercent) : verdict;
    }

    /**
     * What became of the costs that do not depend on the machine: bytes allocated and bytes on the wire per
     * operation, flagged when they moved by the threshold and 32 bytes, or 4 bytes on the wire.
     */
    private static String costChanges(Row before, Row now, double threshold) {
        return change(before.get(ALLOC), now.get(ALLOC), threshold, 32, "ALLOCATION", "allocation")
                + change(before.get(PEER_ALLOC), now.get(PEER_ALLOC), threshold, 32, "SERVER ALLOCATION", "server allocation")
                + change(before.get(SENT) + before.get(RECEIVED), now.get(SENT) + now.get(RECEIVED), threshold, 4, "BYTES", "bytes");
    }

    private static String change(double before, double now, double threshold, double least, String more, String less) {
        if (!(before > 0) || Double.isNaN(now)) return "";
        double delta = (now - before) / before;
        if (delta > threshold && now - before > least) return ", MORE " + more;
        if (-delta > threshold && before - now > least) return ", less " + less;
        return "";
    }

    /**
     * The lines under a result: CPU per operation by kind of thread, garbage collection, Netty's direct memory, bytes
     * on the wire and the bandwidth they make, and how busy the rest of the machine was; the CPU and the bytes of
     * {@code before}, when there is one that has them, come first. A result with a peer process shows its two sides
     * apart: the benchmark's JVM as the client, then the peer's as the server.
     */
    private static String costs(Row before, Row row) {
        boolean apart = Double.isFinite(row.get(PEER_CPU));
        List<String> parts = new ArrayList<>();
        if (Double.isFinite(row.get(CPU))) {
            String was = before != null && Double.isFinite(before.get(CPU)) ? number(before.get(CPU)) + " -> " : "";
            parts.add(String.format(Locale.ROOT, "%scpu %s%s ns/op (caller %s, eventloop %s, other %s, jvm %s)",
                    apart ? "client " : "", was, number(row.get(CPU)), number(row.get(CPU_CALLER)),
                    number(row.get(CPU_EVENT_LOOP)), number(row.get(CPU_OTHER)), number(row.get(CPU_JVM))));
        }
        if (Double.isFinite(row.get(GC_COUNT)))
            parts.add(String.format(Locale.ROOT, "gc %.0f in %.0f ms", row.get(GC_COUNT), finite(row.get(GC_MS))));
        if (Double.isFinite(row.get(DIRECT))) parts.add(String.format(Locale.ROOT, "direct %.1f MiB", row.get(DIRECT)));
        if (Double.isFinite(row.get(SENT))) {
            double bytes = row.get(SENT) + row.get(RECEIVED);
            String was = before != null && Double.isFinite(before.get(SENT))
                    ? String.format(Locale.ROOT, "%,.0f+%,.0f -> ", before.get(SENT), before.get(RECEIVED)) : "";
            parts.add(String.format(Locale.ROOT, "net %s%,.0f+%,.0f B/op, %.1f MB/s", was, row.get(SENT),
                    row.get(RECEIVED), bytes * row.opsPerSecond() / 1e6));
        }
        if (Double.isFinite(row.get(BUSY))) parts.add(String.format(Locale.ROOT, "busy %.0f%%", row.get(BUSY)));
        String lines = "    " + String.join("  |  ", parts);
        if (!apart) return lines;
        String was = before != null && Double.isFinite(before.get(PEER_CPU)) ? number(before.get(PEER_CPU)) + " -> " : "";
        return lines + String.format(Locale.ROOT, "%n    server cpu %s%s ns/op (eventloop %s, other %s, jvm %s)  |  alloc %s B/op"
                        + "  |  gc %.0f in %.0f ms  |  direct %.1f MiB", was, number(row.get(PEER_CPU)),
                number(row.get(PEER_CPU_EVENT_LOOP)), number(row.get(PEER_CPU_OTHER)), number(row.get(PEER_CPU_JVM)),
                number(row.get(PEER_ALLOC)), finite(row.get(PEER_GC_COUNT)), finite(row.get(PEER_GC_MS)), finite(row.get(PEER_DIRECT)));
    }

    /** Runs {@code jar} and this jar in turn, {@code jar} first, and prints how this one compares with it. */
    private static Outcome against(String component, Path jar, String include, double threshold) throws Exception {
        Path work = Files.createTempDirectory("bench-" + component + "-");
        List<Map<String, Row>> before = new ArrayList<>(), now = new ArrayList<>();
        for (int round = 1; round <= ROUNDS; round++) {
            before.add(runJar(jar, component, include, work.resolve("before-" + round)));
            Map<String, Row> rows = new LinkedHashMap<>();
            for (Row row : rows(component, run(component, include))) rows.put(row.key(), row);
            now.add(rows);
        }
        System.out.println();
        System.out.println("== " + component + ": against " + jar + ", each run " + ROUNDS + " times in turn, it first");
        System.out.println("   now " + environment());
        boolean worse = false, unsure = false;
        for (Row row : now.getFirst().values()) {
            List<Row> theirs = runsOf(before, row.key()), mine = runsOf(now, row.key());
            if (theirs.size() < ROUNDS || mine.size() < ROUNDS) {
                System.out.printf(Locale.ROOT, "%-38s %-42s %s %s  new%n", row.benchmark(), row.params(), scores(mine), row.unit());
                System.out.println(costs(null, Row.mean(mine)));
                continue;
            }
            DoubleSummaryStatistics a = theirs.stream().mapToDouble(Row::score).summaryStatistics();
            DoubleSummaryStatistics b = mine.stream().mapToDouble(Row::score).summaryStatistics();
            Row was = Row.mean(theirs), is = Row.mean(mine);
            double delta = (b.getAverage() - a.getAverage()) / a.getAverage();
            boolean apart = b.getMin() > a.getMax() || b.getMax() < a.getMin();
            boolean clear = apart && Math.abs(b.getAverage() - a.getAverage()) > a.getMax() - a.getMin();
            String verdict = Math.abs(delta) <= threshold ? "same" : !clear ? "unsure"
                    : row.higherIsBetter() == delta > 0 ? "better" : "WORSE";
            verdict = busy(verdict, Math.max(finite(was.get(BUSY)), finite(is.get(BUSY)))) + costChanges(was, is, threshold);
            worse |= verdict.contains("WORSE") || verdict.contains("MORE");
            unsure |= verdict.startsWith("unsure");
            System.out.printf(Locale.ROOT, "%-38s %-42s %s -> %s %s  %+.1f%% %s (it moved %.1f%% itself)  alloc %s -> %s B/op%n",
                    row.benchmark(), row.params(), scores(theirs), scores(mine), row.unit(), 100 * delta, verdict,
                    100 * (a.getMax() - a.getMin()) / a.getAverage(), number(was.get(ALLOC)), number(is.get(ALLOC)));
            System.out.println(costs(was, is));
        }
        if (unsure) System.out.println("unsure: the result moved, but no more clearly than " + jar
                + " moved between its own runs, or the machine was busy; run again on a steadier machine");
        System.out.println("   runs of " + jar + " logged in " + work);
        return new Outcome(worse, unsure);
    }

    /** Runs {@code jar}'s own runner for {@code component} and reads the rows it saves into {@code dir}. */
    private static Map<String, Row> runJar(Path jar, String component, String include, Path dir) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        String unixTmp = System.getProperty("jdk.net.unixdomain.tmpdir");
        if (unixTmp != null) command.add("-Djdk.net.unixdomain.tmpdir=" + unixTmp);
        command.addAll(List.of("-jar", jar.toString(), component, "--save", "--results=" + dir));
        if (!include.isEmpty()) command.add("--include=" + include);
        Path log = Path.of(dir + ".log");
        System.out.println("Running " + jar + " for " + component + ", logging to " + log);
        int exit = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start().waitFor();
        if (exit != 0) throw new IllegalStateException(jar + " failed with exit code " + exit + "; see " + log);
        return Baseline.read(dir.resolve(component + ".csv")).rows();
    }

    private static List<Row> runsOf(List<Map<String, Row>> runs, String key) {
        return runs.stream().map(rows -> rows.get(key)).filter(Objects::nonNull).toList();
    }

    private static String scores(List<Row> runs) {
        return runs.stream().map(row -> number(row.score())).collect(Collectors.joining("/"));
    }

    private static String environment() {
        return LocalDate.now() + " on " + System.getProperty("os.name") + " " + System.getProperty("os.arch") + ", "
                + Runtime.getRuntime().availableProcessors() + " CPUs, Java " + System.getProperty("java.version");
    }

    private static double finite(double value) { return Double.isFinite(value) ? value : 0; }

    private static String number(double value) {
        if (Double.isNaN(value)) return "-";
        if (value == 0) return "0";
        return value >= 100 ? String.format(Locale.ROOT, "%,.0f", value) : String.format(Locale.ROOT, "%.3f", value);
    }

    /** Whether a comparison found anything worse, or anything it cannot tell. */
    record Outcome(boolean worse, boolean unsure) {}

    /** One benchmark under one parameter set: its score and what it measured besides, by column. */
    record Row(String benchmark, String params, String mode, double score, double error, String unit,
               Map<String, Double> measures) {
        String key() { return benchmark + " " + params + " " + mode; }

        boolean higherIsBetter() { return mode.equals(Mode.Throughput.shortLabel()); }

        /** A measure by its column, NaN when this row has none. */
        double get(String column) { return measures.getOrDefault(column, Double.NaN); }

        /** Operations per second, whatever unit the score is in. */
        double opsPerSecond() {
            return switch (unit) {
                case "ops/s" -> score;
                case "ms/op" -> 1e3 / score;
                case "us/op" -> 1e6 / score;
                case "ns/op" -> 1e9 / score;
                default -> Double.NaN;
            };
        }

        /** The mean of several runs, and the highest machine load among them. */
        static Row mean(List<Row> runs) {
            Map<String, Double> measures = new LinkedHashMap<>();
            for (String column : COLUMNS) {
                DoubleSummaryStatistics values = runs.stream().mapToDouble(row -> row.get(column))
                        .filter(Double::isFinite).summaryStatistics();
                if (values.getCount() > 0) measures.put(column, column.equals(BUSY) ? values.getMax() : values.getAverage());
            }
            Row first = runs.getFirst();
            return new Row(first.benchmark(), first.params(), first.mode(),
                    runs.stream().mapToDouble(Row::score).average().orElse(Double.NaN), first.error(), first.unit(), measures);
        }

        String csv() {
            List<String> cells = new ArrayList<>(List.of(benchmark, params, mode, value(score), value(error), unit));
            for (String column : COLUMNS) cells.add(value(get(column)));
            return String.join(",", cells);
        }

        /** A line under {@code header}; the columns it lacks, as in files from older runs, are NaN. */
        static Row parse(List<String> header, String line) {
            String[] cells = line.split(",", -1);
            Map<String, Double> measures = new LinkedHashMap<>();
            for (String column : COLUMNS) {
                int at = header.indexOf(column);
                if (at >= 0 && at < cells.length && !cells[at].isEmpty()) measures.put(column, Double.parseDouble(cells[at]));
            }
            return new Row(cells[0], cells[1], cells[2], cell(cells[3]), cell(cells[4]), cells[5], measures);
        }

        private static String value(double value) { return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.3f", value); }

        private static double cell(String cell) { return cell.isEmpty() ? Double.NaN : Double.parseDouble(cell); }
    }

    /** A component's recorded results, by key, and when and where they were recorded. */
    record Baseline(String recorded, Map<String, Row> rows) {
        private static final String CORE = "benchmark,params,mode,score,error,unit";

        static Baseline read(Path file) throws IOException {
            Map<String, Row> rows = new LinkedHashMap<>();
            if (!Files.exists(file)) return new Baseline("", rows);
            String recorded = "";
            List<String> header = List.of((CORE + "," + String.join(",", COLUMNS)).split(","));
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("# recorded ")) recorded = line.substring(11);
                else if (line.startsWith(CORE)) header = List.of(line.split(","));
                else if (!line.isBlank() && !line.startsWith("#")) {
                    Row row = Row.parse(header, line);
                    rows.put(row.key(), row);
                }
            }
            return new Baseline(recorded, rows);
        }

        /** These rows with {@code now} in place of the ones it measured again. */
        Baseline merge(List<Row> now) {
            Map<String, Row> merged = new LinkedHashMap<>(rows);
            for (Row row : now) merged.put(row.key(), row);
            return new Baseline(recorded, merged);
        }

        void write(Path file, String environment) throws IOException {
            List<String> lines = new ArrayList<>();
            lines.add("# recorded " + environment);
            lines.add(CORE + "," + String.join(",", COLUMNS));
            rows.values().forEach(row -> lines.add(row.csv()));
            Files.write(file, lines, StandardCharsets.UTF_8);
        }
    }
}
