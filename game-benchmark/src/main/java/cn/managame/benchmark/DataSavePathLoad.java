package cn.managame.benchmark;

import cn.managame.data.DataStats;
import cn.managame.data.GameData;
import cn.managame.data.GameDataBuilder;
import cn.managame.data.SingleRepository;
import cn.managame.data.annotation.Id;
import cn.managame.data.error.DataOperationException;
import cn.managame.data.mysql.Column;
import cn.managame.data.mysql.JdbcMysqlAccess;
import cn.managame.data.mysql.Table;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;

/**
 * Opt-in load driver for the game-data save path: business writes through a SingleRepository, the write-behind
 * pipeline, MysqlEntityMapper and JdbcMysqlAccess into a real MySQL. It re-runs the measurements recorded in
 * docs/reviews/2026-10-18-data-save-path-load-test.md: INSERT and UPDATE throughput per batchSize, what
 * {@code rewriteBatchedStatements=true} is worth, and what a producer rate above the drain rate does to the
 * dirty set, to the passes and to the heap.
 *
 * <p>A main class rather than a JMH benchmark, unlike the other three in this module. JMH measures a steady
 * state it reaches by repeating one operation; here the quantity of interest is what a whole pipeline does over
 * seconds (passes completing, a dirty set at its bound, heap growing), the database is stateful so an invocation
 * cannot be repeated without deciding what to do with the rows it left behind, and warmup would already have
 * filled the table. The phases are therefore timed by hand, each against a table it creates and drops.
 *
 * <p>Nothing runs without a JDBC URL, and nothing in the build calls this class; it is invoked by hand:
 *
 * <pre>{@code
 * mvn -pl game-benchmark -am package -DskipTests
 * java -Xmx2g -cp game-benchmark/target/benchmarks.jar cn.managame.benchmark.DataSavePathLoad \
 *      --url=jdbc:mysql://127.0.0.1:3306/ogbs_bench --user=root --password=secret
 * }</pre>
 *
 * <p>It drops and recreates {@value #TABLE} in the database the URL names, and refuses to start when that
 * database holds any other table, so it cannot be pointed at a schema that matters. The URL's own
 * {@code rewriteBatchedStatements} is ignored: the throughput phase sets the flag both ways itself.
 */
public final class DataSavePathLoad {
    /** The one table the harness owns. Must equal the {@link Table} name below, which an annotation cannot read. */
    public static final String TABLE = "bench_players";

    /** Three columns, no secondary index: the row is small on purpose, so what is measured is the save path. */
    @Table(name = TABLE)
    public static class BenchPlayer {
        @Id @Column public long id;
        @Column public int score;
        @Column public long updated;
    }
    public static final class BenchPlayers extends SingleRepository<Long, BenchPlayer> {}

    private DataSavePathLoad() {}

    private static final int POOL_SIZE = 4;
    /** Sampling period of the pendingChanges/heap monitor. */
    private static final long SAMPLE_MILLIS = 5;
    /** How much of a paced producer's wait is spun rather than parked (see the sweep's pacer). */
    private static final long SPIN_NANOS = 2_000_000;

    /** One throughput configuration: rows INSERTed, then the same ids UPDATEd once each. */
    private record Throughput(int batchSize, boolean rewrite, long rows,
                              double insertRate, double updateRate, long refusals) {}
    /** One producer rate held for the sweep window; rate 0 means unthrottled. */
    private record Sweep(long rate, long accepted, long refused, long peakPending, long steadyPending,
                         long passes, long peakHeapBytes, long storedRows) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String url = option(options, "url", "OGBS_BENCH_MYSQL_URL", null);
        if (url == null || url.isBlank()) { usage(); return; }
        String user = option(options, "user", "OGBS_BENCH_MYSQL_USER", "root");
        String password = option(options, "password", "OGBS_BENCH_MYSQL_PASSWORD", "");
        long rows = number(options, "rows", 200_000);
        int producers = (int) number(options, "producers", 4);
        int maxPending = (int) number(options, "max-pending", 50_000);
        long pendingWaitMillis = number(options, "pending-wait-ms", 200);
        long flushMillis = number(options, "flush-ms", 100);
        boolean pooled = !"off".equalsIgnoreCase(option(options, "pool", null, "on"));
        String phase = option(options, "phase", null, "all");
        List<Integer> batchSizes = integers(option(options, "batch-sizes", null, "500,2000"));
        List<Long> rates = longs(option(options, "rates", null, "30000,60000,0"));
        long sweepSeconds = number(options, "sweep-seconds", 20);
        int sweepBatchSize = (int) number(options, "sweep-batch", 2000);
        long sweepFlushMillis = number(options, "sweep-flush-ms", 1000);
        List<Boolean> rewrites = switch (option(options, "jdbc", null, "both")) {
            case "plain" -> List.of(false);
            case "rewrite" -> List.of(true);
            case "both" -> List.of(false, true);
            default -> throw new IllegalArgumentException("--jdbc must be plain, rewrite or both");
        };
        boolean throughputPhase = phase.equals("all") || phase.equals("throughput");
        boolean sweepPhase = phase.equals("all") || phase.equals("sweep");
        if (!throughputPhase && !sweepPhase) throw new IllegalArgumentException("--phase must be all, throughput or sweep");

        describe(url, user, password, pooled, producers);
        List<Throughput> throughputs = new ArrayList<>();
        List<Sweep> sweeps = new ArrayList<>();
        if (throughputPhase) {
            for (boolean rewrite : rewrites) {
                for (int batchSize : batchSizes) {
                    throughputs.add(throughput(jdbcUrl(url, rewrite), user, password, pooled, rewrite,
                            batchSize, rows, producers, maxPending, pendingWaitMillis, flushMillis));
                }
            }
        }
        // The sweep keeps the URL as given: what a rate above the drain rate does depends on the drain rate,
        // so the operator decides whether the sweep sees a plain URL or a deployment's own.
        if (sweepPhase) {
            for (long rate : rates) {
                sweeps.add(sweep(url, user, password, pooled, sweepBatchSize, rate, producers,
                        sweepSeconds, maxPending, pendingWaitMillis, sweepFlushMillis));
            }
        }
        report(throughputs, sweeps, rows, sweepSeconds, sweepBatchSize, sweepFlushMillis, maxPending,
                pendingWaitMillis, producers);
    }

    /**
     * One configuration: {@code rows} INSERTs, a flush barrier, then one UPDATE per id and another barrier.
     * Both phases are timed from the first write to the barrier returning, so the rate covers everything the
     * save path does with a change, and the row count afterwards proves every change reached storage.
     */
    private static Throughput throughput(String url, String user, String password, boolean pooled, boolean rewrite,
                                         int batchSize, long rows, int producers, int maxPending,
                                         long pendingWaitMillis, long flushMillis) throws Exception {
        System.out.printf(Locale.ROOT, "%n-- INSERT then UPDATE %,d rows, batchSize %d, %s --%n   %s%n",
                rows, batchSize, rewrite ? "rewriteBatchedStatements=true" : "plain URL", shown(url));
        DataSource source = source(url, user, password, pooled);
        try {
            var access = new JdbcMysqlAccess(source);
            prepare(access);
            // The cache holds every id the run touches: eviction is the sweep's subject, not throughput's.
            GameData data = GameDataBuilder.builder().mysql(source).repositories(BenchPlayers.class)
                    .batchSize(batchSize).flushInterval(Duration.ofMillis(flushMillis))
                    .cacheExpire(Duration.ofMinutes(10))
                    .maxPendingChanges(maxPending).pendingChangeWait(Duration.ofMillis(pendingWaitMillis))
                    .maxCacheEntries((int) Math.min(Integer.MAX_VALUE, rows + maxPending)).build();
            var refusals = new AtomicLong();
            try {
                double insertRate = timed(data, rows, producers, refusals, false);
                System.out.printf(Locale.ROOT, "   INSERT %,.0f rows/s%n", insertRate);
                double updateRate = timed(data, rows, producers, refusals, true);
                System.out.printf(Locale.ROOT, "   UPDATE %,.0f rows/s%n", updateRate);
                long stored = count(access, "SELECT COUNT(*) FROM " + TABLE);
                long updated = count(access, "SELECT COUNT(*) FROM " + TABLE + " WHERE score = 2");
                if (stored != rows || updated != rows)
                    throw new IllegalStateException(stored + " rows stored and " + updated + " updated, expected " + rows);
                return new Throughput(batchSize, rewrite, rows, insertRate, updateRate, refusals.get());
            } finally {
                data.close();
                drop(access);
            }
        } finally { release(source); }
    }

    /**
     * One phase: every id from 1 to rows written once by {@code producers} threads, then flushed. A write
     * refused because the dirty set stayed full is retried with the same entity, so the phase always persists
     * exactly {@code rows} changes and the rate measures the drain rate rather than the refusal setting.
     */
    private static double timed(GameData data, long rows, int producers, AtomicLong refusals, boolean update)
            throws InterruptedException {
        var players = data.repository(BenchPlayers.class);
        var next = new AtomicLong();
        long started = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < producers; i++) threads.add(Thread.ofPlatform().name("producer-" + i).start(() -> {
            for (long id; (id = next.incrementAndGet()) <= rows;) {
                BenchPlayer player = new BenchPlayer();
                player.id = id; player.score = update ? 2 : 1; player.updated = System.currentTimeMillis();
                for (;;) {
                    try { if (update) players.update(player); else players.insert(player); break; }
                    catch (DataOperationException full) { refusals.incrementAndGet(); }
                }
            }
        }));
        for (Thread thread : threads) thread.join();
        data.flush();   // the producers have stopped, so this is a stable barrier for everything above
        return rows / ((System.nanoTime() - started) / 1e9);
    }

    /**
     * Producers held at {@code rate} changes per second (0 = flat out) for the window, sampling the dirty set
     * and the heap. What the window shows is whether the pipeline keeps up: a rate below the drain rate leaves
     * pendingChanges small and passes advancing, and above it the dirty set sits at its bound and the writes
     * that would exceed it are refused instead of becoming heap. close then stores everything accepted, which
     * the row count checks.
     */
    private static Sweep sweep(String url, String user, String password, boolean pooled, int batchSize, long rate,
                               int producers, long seconds, int maxPending, long pendingWaitMillis,
                               long flushMillis) throws Exception {
        System.out.printf(Locale.ROOT, "%n-- %s for %d s, batchSize %d, flushInterval %d ms --%n   %s%n",
                rate == 0 ? "unthrottled producers" : String.format(Locale.ROOT, "%,d changes/s", rate),
                seconds, batchSize, flushMillis, shown(url));
        DataSource source = source(url, user, password, pooled);
        try {
            var access = new JdbcMysqlAccess(source);
            prepare(access);
            GameData data = GameDataBuilder.builder().mysql(source).repositories(BenchPlayers.class)
                    .batchSize(batchSize).flushInterval(Duration.ofMillis(flushMillis))
                    .cacheExpire(Duration.ofMinutes(10))
                    .maxPendingChanges(maxPending).pendingChangeWait(Duration.ofMillis(pendingWaitMillis))
                    .maxCacheEntries(2 * maxPending).build();
            var accepted = new AtomicLong(); var refused = new AtomicLong(); var next = new AtomicLong();
            var running = new AtomicBoolean(true);
            try {
                var players = data.repository(BenchPlayers.class);
                long perProducerPeriod = rate == 0 ? 0 : TimeUnit.SECONDS.toNanos(1) * producers / rate;
                List<Thread> threads = new ArrayList<>();
                for (int i = 0; i < producers; i++) threads.add(Thread.ofPlatform().name("producer-" + i).start(() -> {
                    long due = System.nanoTime();
                    while (running.get()) {
                        if (perProducerPeriod > 0) {
                            // Park the bulk of a long wait, then spin out the rest: a rate like 30,000/s needs
                            // 0.13 ms per producer, and parking for that overshoots by milliseconds on a
                            // platform whose timer is coarser, which caps the rate far below the one asked for.
                            long wait = due - System.nanoTime();
                            if (wait > SPIN_NANOS) LockSupport.parkNanos(wait - SPIN_NANOS);
                            while (System.nanoTime() - due < 0) Thread.onSpinWait();
                            // A stall must not turn into a burst that hides what the rate was meant to be.
                            due = Math.max(due + perProducerPeriod, System.nanoTime() - perProducerPeriod);
                        }
                        BenchPlayer player = new BenchPlayer();
                        player.id = next.incrementAndGet(); player.score = 1; player.updated = System.currentTimeMillis();
                        try { players.insert(player); accepted.incrementAndGet(); }
                        catch (DataOperationException full) { refused.incrementAndGet(); }
                    }
                }));
                long passesAtStart = data.stats().flushes();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
                long peakPending = 0, peakHeap = 0;
                List<Long> pending = new ArrayList<>();
                while (System.nanoTime() < until) {
                    DataStats stats = data.stats();
                    peakPending = Math.max(peakPending, stats.pendingChanges());
                    peakHeap = Math.max(peakHeap, usedHeap());
                    pending.add(stats.pendingChanges());
                    Thread.sleep(SAMPLE_MILLIS);
                }
                long passes = data.stats().flushes() - passesAtStart;
                running.set(false);
                for (Thread thread : threads) thread.join();
                System.out.printf(Locale.ROOT,
                        "   accepted %,d  refused %,d  peak pendingChanges %,d  passes %d  peak heap %,d MB%n",
                        accepted.get(), refused.get(), peakPending, passes, peakHeap / (1024 * 1024));
                data.close();   // stores everything accepted; a refused change was never accepted
                long stored = count(access, "SELECT COUNT(*) FROM " + TABLE);
                if (stored != accepted.get())
                    throw new IllegalStateException(stored + " rows stored for " + accepted.get() + " accepted changes");
                return new Sweep(rate, accepted.get(), refused.get(), peakPending, median(pending),
                        passes, peakHeap, stored);
            } finally {
                try { data.close(); } catch (RuntimeException alreadyClosed) { /* the checks above report first */ }
                drop(access);
            }
        } finally { release(source); }
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
    /** Median of the samples from the second half of the window, once the pipeline has settled. */
    private static long median(List<Long> samples) {
        if (samples.isEmpty()) return 0;
        List<Long> tail = new ArrayList<>(samples.subList(samples.size() / 2, samples.size()));
        tail.sort(null);
        return tail.get(tail.size() / 2);
    }

    private static DataSource source(String url, String user, String password, boolean pooled) {
        MysqlDataSource plain = new MysqlDataSource();
        plain.setURL(url); plain.setUser(user); plain.setPassword(password);
        if (!pooled) return plain;   // JdbcMysqlAccess takes a connection per operation: a handshake per batch
        HikariConfig config = new HikariConfig();
        config.setDataSource(plain); config.setMaximumPoolSize(POOL_SIZE); config.setPoolName("ogbs-bench");
        return new HikariDataSource(config);
    }
    private static void release(DataSource source) {
        if (source instanceof HikariDataSource pool) pool.close();
    }

    /** Refuses a database holding anything the harness does not own, then starts from an empty table. */
    private static void prepare(JdbcMysqlAccess access) {
        long others = count(access, "SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema = DATABASE() AND table_name <> '" + TABLE + "'");
        if (others != 0) throw new IllegalStateException("The database holds " + others + " table(s) besides "
                + TABLE + "; point --url at a disposable database, because this harness drops and recreates its table");
        drop(access);
    }
    private static void drop(JdbcMysqlAccess access) { access.update("DROP TABLE IF EXISTS " + TABLE, new Object[0]); }
    private static long count(JdbcMysqlAccess access, String sql) {
        Long value = access.queryOne(sql, new Object[0], row -> row.getLong(1));
        return value == null ? 0 : value;
    }

    /** The URL a phase measures: the flag is set by the harness, not taken from the argument. */
    private static String jdbcUrl(String url, boolean rewrite) {
        int mark = url.indexOf('?');
        String base = mark < 0 ? url : url.substring(0, mark);
        StringBuilder kept = new StringBuilder();
        if (mark >= 0) {
            for (String parameter : url.substring(mark + 1).split("&")) {
                if (parameter.isEmpty() || parameter.startsWith("rewriteBatchedStatements=")) continue;
                kept.append(kept.isEmpty() ? "" : "&").append(parameter);
            }
        }
        if (rewrite) kept.append(kept.isEmpty() ? "" : "&").append("rewriteBatchedStatements=true");
        return kept.isEmpty() ? base : base + "?" + kept;
    }

    /** The URL as printed: a credential someone put on it is not echoed into a report. */
    private static String shown(String url) {
        int mark = url.indexOf('?');
        if (mark < 0) return url;
        StringBuilder kept = new StringBuilder();
        for (String parameter : url.substring(mark + 1).split("&")) {
            if (parameter.isEmpty() || parameter.startsWith("user=") || parameter.startsWith("password=")) continue;
            kept.append(kept.isEmpty() ? "" : "&").append(parameter);
        }
        return kept.isEmpty() ? url.substring(0, mark) : url.substring(0, mark) + "?" + kept;
    }

    private static void describe(String url, String user, String password, boolean pooled, int producers) {
        var access = new JdbcMysqlAccess(source(url, user, password, false));
        String database = access.queryOne("SELECT DATABASE()", new Object[0], row -> row.getString(1));
        String version = access.queryOne("SELECT VERSION()", new Object[0], row -> row.getString(1));
        String driver = access.queryOne("SELECT 1", new Object[0], row -> {
            var metaData = row.getStatement().getConnection().getMetaData();
            return metaData.getDriverName() + " " + metaData.getDriverVersion();
        });
        System.out.printf(Locale.ROOT, "game-data save path load%n"
                        + "  database    : %s on MySQL %s over %s%n"
                        + "  driver      : %s%n"
                        + "  machine     : %d cores, JDK %s, -Xmx %,d MB%n"
                        + "  schema      : %s(id BIGINT PRIMARY KEY, score INT, updated BIGINT), no secondary index%n"
                        + "  path        : GameData -> BenchPlayers(SingleRepository) -> write-behind"
                        + " -> MysqlEntityMapper -> JdbcMysqlAccess%n"
                        + "  producers   : %d platform threads, distinct ids (no coalescing: every change is a row)%n"
                        + "  connections : %s%n",
                database, version, host(url), driver, Runtime.getRuntime().availableProcessors(),
                Runtime.version(), Runtime.getRuntime().maxMemory() / (1024 * 1024), TABLE, producers,
                pooled ? "HikariCP, at most " + POOL_SIZE : "a new connection per batch (--pool=off)");
    }
    private static String host(String url) {
        int start = url.indexOf("//");
        int end = url.indexOf('/', start < 0 ? 0 : start + 2);
        String authority = start < 0 ? url : url.substring(start + 2, end < 0 ? url.length() : end);
        return authority.startsWith("127.0.0.1") || authority.startsWith("localhost") ? "loopback" : authority;
    }

    private static void report(List<Throughput> throughputs, List<Sweep> sweeps, long rows, long sweepSeconds,
                               int sweepBatchSize, long sweepFlushMillis, int maxPending, long pendingWaitMillis,
                               int producers) {
        if (!throughputs.isEmpty()) {
            System.out.printf(Locale.ROOT, "%n## Throughput, %,d rows per operation%n%n"
                    + "| Configuration | Rows/s |%n| --- | --- |%n", rows);
            for (Throughput t : throughputs) {
                System.out.printf(Locale.ROOT, "| INSERT, batchSize %d%s | %,.0f |%n", t.batchSize(), flag(t), t.insertRate());
                System.out.printf(Locale.ROOT, "| UPDATE, batchSize %d%s | %,.0f |%n", t.batchSize(), flag(t), t.updateRate());
            }
            for (Throughput plain : throughputs) {
                if (plain.rewrite()) continue;
                for (Throughput rewrite : throughputs) {
                    if (!rewrite.rewrite() || rewrite.batchSize() != plain.batchSize()) continue;
                    System.out.printf(Locale.ROOT, "%nrewriteBatchedStatements=true at batchSize %d:"
                                    + " INSERT x%.2f (%,.0f -> %,.0f), UPDATE x%.2f (%,.0f -> %,.0f)%n",
                            plain.batchSize(), rewrite.insertRate() / plain.insertRate(), plain.insertRate(),
                            rewrite.insertRate(), rewrite.updateRate() / plain.updateRate(), plain.updateRate(),
                            rewrite.updateRate());
                }
            }
            long refusals = throughputs.stream().mapToLong(Throughput::refusals).sum();
            if (refusals > 0) System.out.printf(Locale.ROOT, "%n%,d writes waited out pendingChangeWait and were"
                    + " retried with the same entity; raise --pending-wait-ms for fewer.%n", refusals);
        }
        if (!sweeps.isEmpty()) {
            System.out.printf(Locale.ROOT, "%n## Producer rate sweep, %d s each, batchSize %d, flushInterval %d ms,"
                            + " maxPendingChanges %,d, pendingChangeWait %d ms, %d producers%n%n"
                            + "| Producer rate | pendingChanges (steady / peak) | Passes | Peak heap"
                            + " | Accepted | Accepted/s | Refused | Rows stored |%n"
                            + "| --- | --- | --- | --- | --- | --- | --- | --- |%n",
                    sweepSeconds, sweepBatchSize, sweepFlushMillis, maxPending, pendingWaitMillis, producers);
            for (Sweep s : sweeps) {
                System.out.printf(Locale.ROOT, "| %s | %,d / %,d | %d | %,d MB | %,d | %,d | %,d | %,d |%n",
                        s.rate() == 0 ? "unthrottled" : String.format(Locale.ROOT, "%,d/s", s.rate()),
                        s.steadyPending(), s.peakPending(), s.passes(), s.peakHeapBytes() / (1024 * 1024),
                        s.accepted(), s.accepted() / Math.max(1, sweepSeconds), s.refused(), s.storedRows());
            }
            System.out.printf(Locale.ROOT, "%nEvery accepted change was stored by close, which the row count"
                    + " checks; a refused one was never accepted.%n");
        }
    }
    private static String flag(Throughput t) { return t.rewrite() ? ", rewriteBatchedStatements=true" : ", plain URL"; }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String argument : args) {
            if (!argument.startsWith("--")) throw new IllegalArgumentException("Unexpected argument: " + argument);
            int split = argument.indexOf('=');
            if (split < 0) throw new IllegalArgumentException("Expected --name=value: " + argument);
            options.put(argument.substring(2, split), argument.substring(split + 1));
        }
        return options;
    }
    private static String option(Map<String, String> options, String name, String environment, String fallback) {
        String value = options.get(name);
        if (value == null && environment != null) value = System.getenv(environment);
        return value == null ? fallback : value;
    }
    private static long number(Map<String, String> options, String name, long fallback) {
        String value = options.get(name);
        return value == null ? fallback : Long.parseLong(value.trim());
    }
    private static List<Integer> integers(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).map(Integer::parseInt).toList();
    }
    private static List<Long> longs(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).map(Long::parseLong).toList();
    }

    private static void usage() {
        System.out.print("""
                game-data save path load driver. Does nothing without a database.

                  --url=jdbc:mysql://127.0.0.1:3306/ogbs_bench   (or OGBS_BENCH_MYSQL_URL) required
                  --user=root                    (or OGBS_BENCH_MYSQL_USER)
                  --password=                    (or OGBS_BENCH_MYSQL_PASSWORD)
                  --phase=all|throughput|sweep   default all
                  --rows=200000                  changes per operation in the throughput phase
                  --producers=4                  writing threads
                  --batch-sizes=500,2000         GameDataBuilder.batchSize values to measure
                  --jdbc=both|plain|rewrite      measure with and without rewriteBatchedStatements=true
                  --pool=on|off                  HikariCP, or a new connection per batch
                  --flush-ms=100                 flushInterval in the throughput phase
                  --max-pending=50000            maxPendingChanges
                  --pending-wait-ms=200          pendingChangeWait
                  --rates=30000,60000,0          sweep producer rates; 0 is unthrottled
                  --sweep-seconds=20             how long each rate is held
                  --sweep-batch=2000             batchSize during the sweep
                  --sweep-flush-ms=1000          flushInterval during the sweep

                The database must be disposable: the harness drops and recreates its own table, and refuses to
                start when the database holds any other one.
                """);
    }
}
