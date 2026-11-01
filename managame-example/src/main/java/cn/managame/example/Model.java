package cn.managame.example;

import cn.managame.data.GroupRepository;
import cn.managame.data.LogRepository;
import cn.managame.data.SingleRepository;
import cn.managame.data.annotation.GroupKey;
import cn.managame.data.annotation.Id;
import cn.managame.data.annotation.MapKey;
import cn.managame.data.mysql.annotation.Table;
import java.util.Map;

/** The game's data, kept by managame-data in the MySQL tables {@link Schema} creates. */
final class Model {
    static final long STARTING_GOLD = 100;
    /** What each item costs, as a config table would say. */
    static final Map<Integer, Integer> PRICES = Map.of(5001, 30, 5002, 50, 5003, 200);

    private Model() {}

    @Table(name = "player")
    static class Player {
        @Id long id;
        String name;
        long gold;
        /** The best rank the world has given, 0 before the first. */
        int bestRank;
        long lastLogout;

        Player() {}

        Player(long id, String name) {
            this.id = id;
            this.name = name;
            gold = STARTING_GOLD;
        }
    }

    /** A player's stack of one item: the bag is the group of a player's items, by config id. */
    @Table(name = "item")
    static class Item {
        @Id long uid;
        @GroupKey long playerId;
        @MapKey int configId;
        int amount;

        Item() {}

        Item(long uid, long playerId, int configId) {
            this.uid = uid;
            this.playerId = playerId;
            this.configId = configId;
            amount = 1;
        }
    }

    @Table(name = "gold_log")
    static class GoldLog {
        long playerId;
        long delta;
        String reason;
        long createdAt;

        GoldLog() {}

        GoldLog(long playerId, long delta, String reason, long createdAt) {
            this.playerId = playerId;
            this.delta = delta;
            this.reason = reason;
            this.createdAt = createdAt;
        }
    }

    static final class Players extends SingleRepository<Long, Player> {}

    static final class Bags extends GroupRepository<Integer, Item> {}

    static final class GoldLogs extends LogRepository<GoldLog> {}
}
