package cn.managame.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Runs the benchmarks of framework components, compares each result with the baseline recorded for its component,
 * and with {@code --save} records the run as the new baseline:
 *
 * <pre>
 * java -Djdk.net.unixdomain.tmpdir=D:\tmp -jar target/benchmarks.jar rpc network [--save] [--include=Latency] [--threshold=10]
 * </pre>
 *
 * A component's benchmarks are the classes in {@code cn.managame.benchmark.<component>}; {@code all} runs every
 * component. Its baseline is {@code results/<component>.csv}: one line per benchmark and parameter set with the
 * score, its error, the bytes allocated per operation and, for sampled latencies, the median and 99th percentile,
 * under a line naming the machine it was recorded on. A result is better or worse when it moved by more than the
 * threshold, 10 percent by default, and by more than the two errors together; allocation, when it moved by more
 * than the threshold and 32 bytes. The exit code is 1 when anything got worse. Saving a run of some benchmarks
 * replaces their lines and keeps the others.
 */
public final class Bench {
    static final List<String> COMPONENTS = List.of("common", "network", "runtime", "rpc");
    private static final String PACKAGE = "cn.managame.benchmark.";

    private Bench() {}

    public static void main(String[] args) throws Exception {
        List<String> components = new ArrayList<>();
        boolean save = false;
        double threshold = 10;
        String include = ".*";
        Path results = Path.of("results");
        for (String arg : args) {
            if (arg.equals("--save")) save = true;
            else if (arg.startsWith("--threshold=")) threshold = Double.parseDouble(arg.substring(12));
            else if (arg.startsWith("--include=")) include = ".*" + arg.substring(10) + ".*";
            else if (arg.startsWith("--results=")) results = Path.of(arg.substring(10));
            else if (arg.equals("all")) components.addAll(COMPONENTS);
            else if (COMPONENTS.contains(arg)) components.add(arg);
            else usage("Unknown argument " + arg);
        }
        if (components.isEmpty()) usage("Name the components to run");
        boolean worse = false;
        for (String component : components) {
            List<Row> now = rows(component, run(component, include));
            Path file = results.resolve(component + ".csv");
            Baseline baseline = Baseline.read(file);
            worse |= compare(component, baseline, now, threshold / 100);
            if (save) {
                Files.createDirectories(results);
                baseline.merge(now).write(file, environment());
                System.out.println("Saved " + file);
            }
        }
        System.exit(worse ? 1 : 0);
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("Usage: java -jar benchmarks.jar <" + String.join("|", COMPONENTS) + "|all>... "
                + "[--save] [--include=<regex>] [--threshold=<percent>] [--results=<dir>]");
        System.exit(2);
    }

    /** One fork per benchmark, with the JVM settings every recorded run uses. */
    private static Collection<RunResult> run(String component, String include) throws Exception {
        List<String> jvm = new ArrayList<>(List.of("-Xms1g", "-Xmx1g", "-Dio.netty.leakDetection.level=disabled"));
        String unixTmp = System.getProperty("jdk.net.unixdomain.tmpdir");
        if (unixTmp != null) jvm.add("-Djdk.net.unixdomain.tmpdir=" + unixTmp);
        ChainedOptionsBuilder options = new OptionsBuilder()
                .include(PACKAGE.replace(".", "\\.") + component + "\\." + include)
                .addProfiler(GCProfiler.class)
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
            double alloc = run.getSecondaryResults().entrySet().stream()
                    .filter(entry -> entry.getKey().endsWith("gc.alloc.rate.norm"))
                    .mapToDouble(entry -> entry.getValue().getScore()).findFirst().orElse(Double.NaN);
            boolean sampled = params.getMode() == Mode.SampleTime;
            rows.add(new Row(params.getBenchmark().substring((PACKAGE + component + ".").length()),
                    String.join(" ", values), params.getMode().shortLabel(), primary.getScore(),
                    finite(primary.getScoreError()), primary.getScoreUnit(), alloc,
                    sampled ? primary.getStatistics().getPercentile(50) : Double.NaN,
                    sampled ? primary.getStatistics().getPercentile(99) : Double.NaN));
        }
        return rows;
    }

    /** Prints each result next to its baseline; true when any got worse. */
    private static boolean compare(String component, Baseline baseline, List<Row> now, double threshold) {
        System.out.println();
        System.out.println("== " + component + (baseline.rows().isEmpty() ? ": no baseline yet"
                : ": against the baseline " + baseline.recorded()));
        System.out.println("   now " + environment());
        boolean worse = false;
        for (Row row : now) {
            Row before = baseline.rows().get(row.key());
            String verdict = "new";
            String change = "";
            if (before != null) {
                double delta = (row.score() - before.score()) / before.score();
                boolean moved = Math.abs(delta) > threshold && Math.abs(row.score() - before.score()) > row.error() + before.error();
                boolean better = row.higherIsBetter() == delta > 0;
                verdict = !moved ? "same" : better ? "better" : "WORSE";
                double allocDelta = before.alloc() > 0 ? (row.alloc() - before.alloc()) / before.alloc() : 0;
                if (allocDelta > threshold && row.alloc() - before.alloc() > 32) verdict += ", MORE ALLOCATION";
                else if (-allocDelta > threshold && before.alloc() - row.alloc() > 32) verdict += ", less allocation";
                worse |= verdict.contains("WORSE") || verdict.contains("MORE");
                change = String.format(Locale.ROOT, "%s -> ", number(before.score()));
            }
            System.out.printf(Locale.ROOT, "%-38s %-42s %s%s %s  %s  alloc %s B/op%s%n",
                    row.benchmark(), row.params(), change, number(row.score()), row.unit(),
                    before == null ? verdict : String.format(Locale.ROOT, "%+.1f%% %s", 100 * (row.score() - before.score()) / before.score(), verdict),
                    number(row.alloc()), Double.isNaN(row.p99()) ? "" : "  p50 " + number(row.p50()) + " p99 " + number(row.p99()));
        }
        return worse;
    }

    private static String environment() {
        return LocalDate.now() + " on " + System.getProperty("os.name") + " " + System.getProperty("os.arch") + ", "
                + Runtime.getRuntime().availableProcessors() + " CPUs, Java " + System.getProperty("java.version");
    }

    private static double finite(double value) { return Double.isFinite(value) ? value : 0; }

    private static String number(double value) {
        if (Double.isNaN(value)) return "-";
        return value >= 100 ? String.format(Locale.ROOT, "%,.0f", value) : String.format(Locale.ROOT, "%.3f", value);
    }

    /** One benchmark under one parameter set. */
    record Row(String benchmark, String params, String mode, double score, double error, String unit, double alloc,
               double p50, double p99) {
        String key() { return benchmark + " " + params + " " + mode; }

        boolean higherIsBetter() { return mode.equals(Mode.Throughput.shortLabel()); }

        String csv() {
            return String.join(",", benchmark, params, mode, value(score), value(error), unit, value(alloc), value(p50), value(p99));
        }

        static Row parse(String line) {
            String[] cells = line.split(",", -1);
            return new Row(cells[0], cells[1], cells[2], cell(cells[3]), cell(cells[4]), cells[5], cell(cells[6]),
                    cell(cells[7]), cell(cells[8]));
        }

        private static String value(double value) { return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.3f", value); }

        private static double cell(String cell) { return cell.isEmpty() ? Double.NaN : Double.parseDouble(cell); }
    }

    /** A component's recorded results, by key, and when and where they were recorded. */
    record Baseline(String recorded, Map<String, Row> rows) {
        private static final String HEADER = "benchmark,params,mode,score,error,unit,alloc_bytes_per_op,p50,p99";

        static Baseline read(Path file) throws IOException {
            Map<String, Row> rows = new LinkedHashMap<>();
            if (!Files.exists(file)) return new Baseline("", rows);
            String recorded = "";
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("# recorded ")) recorded = line.substring(11);
                else if (!line.isBlank() && !line.startsWith("#") && !line.equals(HEADER)) {
                    Row row = Row.parse(line);
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
            lines.add(HEADER);
            rows.values().forEach(row -> lines.add(row.csv()));
            Files.write(file, lines, StandardCharsets.UTF_8);
        }
    }
}
