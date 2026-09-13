package cn.managame.runtime.diagnostics;

import cn.managame.runtime.route.Route;

import java.time.Duration;

/** A completed task exceeding the configured execution threshold; contains no payload or Throwable. */
public record SlowTask(String domain, Route route, String source, String threadName,
                       Duration queueWait, Duration executionTime, boolean failed) {}
