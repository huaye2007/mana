package cn.managame.demo.bus.system;

import cn.managame.demo.common.runtime.GameDomain;
import cn.managame.runtime.context.Contexts;
import cn.managame.runtime.timer.Cron;

import java.util.concurrent.atomic.AtomicLong;

/** Application task examples; HTTP may read counters from another Route. */
public class DemoTasks {
    public static final long ROUTE_KEY = 1L;
    private final AtomicLong timerRuns = new AtomicLong();
    private final AtomicLong cronRuns = new AtomicLong();

    public void onTimer() {
        log("timer", timerRuns.incrementAndGet());
    }

    @Cron(value = "*/10 * * * * ?", domain = GameDomain.SYSTEM_ID, routeKey = ROUTE_KEY)
    public void onCron() {
        log("cron", cronRuns.incrementAndGet());
    }

    public Status status() { return new Status(timerRuns.get(), cronRuns.get()); }

    public record Status(long timerRuns, long cronRuns) {}

    private static void log(String task, long runs) {
        var context = Contexts.current();
        System.out.printf("Demo %s: runs=%d domain=%d routeKey=%d%n",
                task, runs, context.routeDomain(), context.routeKey());
    }
}
