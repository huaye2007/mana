package cn.managame.runtime.timer;

import java.time.Duration;
public interface RuntimeTimer { TimerRef schedule(int routeDomain, long routeKey, Duration delay, Runnable task); }
