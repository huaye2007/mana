package cn.managame.benchmark;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One side of a benchmark in a JVM of its own, as two server processes talk over TCP. A fixture starts it with the
 * class whose {@code main} runs that side, on this JVM's settings, and closes it with the fixture. Around every
 * iteration {@link CostProfiler} asks it what it used, so that the costs of both sides show.
 *
 * <p>The two talk over the peer's standard streams, a line each way: the peer says {@code ready <port>} once it
 * listens; then {@code begin} is answered {@code ok}, {@code end} with what the peer used since, as
 * {@link Usage.Interval#line()}, and {@code stop}, or the end of its input, closes it. Its standard error is this
 * JVM's.
 */
public final class PeerProcess implements AutoCloseable {
    private static volatile PeerProcess current;
    private final Process process;
    private final BufferedReader replies;
    private final PrintStream requests;
    private final int port;
    private boolean begun, closed;
    /** What the peer used from the last begin until it was closed, for the end that comes after. */
    private Usage.Interval last;

    private PeerProcess(Process process) throws IOException {
        this.process = process;
        replies = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        requests = new PrintStream(process.getOutputStream(), true, StandardCharsets.UTF_8);
        String ready = replies.readLine();
        if (ready == null || !ready.startsWith("ready ")) {
            process.destroyForcibly();
            throw new IOException("The peer process did not start: " + ready);
        }
        port = Integer.parseInt(ready.substring(6).trim());
    }

    /** Starts {@code main} in a new JVM with this JVM's settings, but no agents, and returns once it listens. */
    public static PeerProcess start(Class<?> main) throws IOException {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments())
            if (!arg.startsWith("-agent") && !arg.startsWith("-javaagent") && !arg.startsWith("-Xrun")) command.add(arg);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), main.getName()));
        PeerProcess peer = new PeerProcess(new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start());
        current = peer;
        return peer;
    }

    /** The peer this JVM runs, until it is closed; null when there is none. */
    static PeerProcess current() { return current; }

    /** The port the peer listens on, on the loopback address. */
    public int port() { return port; }

    /** The peer starts measuring what it uses; nothing once it is closed. */
    synchronized void begin() {
        if (closed) return;
        if (!ask("begin").equals("ok")) throw new IllegalStateException("The peer process did not begin");
        begun = true;
    }

    /** What the peer used since {@link #begin()}, also when it was closed meanwhile; null without a begin. */
    synchronized Usage.Interval end() {
        Usage.Interval interval = last;
        last = null;
        if (interval != null || closed || !begun) return interval;
        begun = false;
        return Usage.Interval.parse(ask("end"));
    }

    /** Takes what the peer used since the last begin, as the iteration that closes it ends after, and stops it. */
    @Override public synchronized void close() {
        if (closed) return;
        if (begun) last = end();
        closed = true;
        if (current == this) current = null;
        requests.println("stop");
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private String ask(String request) {
        requests.println(request);
        try {
            String reply = replies.readLine();
            if (reply == null) throw new IllegalStateException("The peer process ended");
            return reply;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The peer's side, for its {@code main}: says it is ready on {@code port}, answers until told to stop or its
     * input ends, then closes {@code server}.
     */
    public static void serve(AutoCloseable server, int port) throws Exception {
        Usage usage = new Usage();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.println("ready " + port);
        try (server) {
            for (String line; (line = in.readLine()) != null && !line.equals("stop"); ) {
                if (line.equals("begin")) {
                    usage.begin();
                    out.println("ok");
                } else if (line.equals("end")) {
                    out.println(usage.end().line());
                }
            }
        }
    }
}
