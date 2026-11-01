package cn.managame.example;

import cn.managame.network.connection.Connection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** The connections of the players logged in: the game keeps them, as the network layer keeps none. */
@Component
final class OnlinePlayers {
    private final Map<Long, Connection> connections = new ConcurrentHashMap<>();

    void add(long playerId, Connection connection) { connections.put(playerId, connection); }

    void remove(long playerId, Connection connection) { connections.remove(playerId, connection); }

    int count() { return connections.size(); }
}
