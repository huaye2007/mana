package cn.managame.runtime.diagnostics;

/** AutoCloseable deadline expired; inspect report and optionally wait again with close(Duration). */
public final class RuntimeShutdownException extends IllegalStateException {
    private final ShutdownReport report;
    public RuntimeShutdownException(ShutdownReport report) {
        super("Runtime shutdown incomplete: " + report);
        this.report = report;
    }
    public ShutdownReport report() { return report; }
}
