package cn.managame.example;

import cn.managame.data.DataStats;
import cn.managame.data.EntityStats;
import cn.managame.data.GameData;
import cn.managame.data.LogStats;
import cn.managame.runtime.timer.Cron;
import java.lang.System.Logger.Level;
import org.springframework.stereotype.Component;

/** The game server's calendar jobs, run on the system domain's one route. */
@Component
final class Jobs {
    private static final System.Logger LOG = System.getLogger("cn.managame.example");
    private final GameData data;
    private final OnlinePlayers online;

    Jobs(GameData data, OnlinePlayers online) {
        this.data = data;
        this.online = online;
    }

    /** At the start of every minute on GameTime: who is online, and how much data waits to be written. */
    @Cron(domain = GameConfig.SYSTEM, value = "0 * * * * *")
    void report() {
        DataStats stats = data.stats();
        int waiting = stats.entities().stream().mapToInt(EntityStats::waiting).sum() + stats.logs().stream().mapToInt(LogStats::queued).sum();
        LOG.log(Level.INFO, online.count() + " players online, " + waiting + " changes and logs waiting to be written");
    }
}
