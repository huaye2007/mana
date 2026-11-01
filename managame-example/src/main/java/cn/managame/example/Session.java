package cn.managame.example;

import cn.managame.Metadata;
import cn.managame.network.connection.Connection;
import cn.managame.runtime.Context;
import io.netty.util.AttributeKey;

/**
 * The game's session of a connection, kept on the connection itself: whom its messages act for, nobody (0) until it
 * logs in. As the source of every message the gateway dispatches, it gives the player domain its route key.
 */
record Session(Connection connection, long playerId) implements Context {
    static final AttributeKey<Session> KEY = AttributeKey.valueOf("example.session");
    static final byte PLAYER = 1;

    @Override public int domain() { return 0; }
    @Override public long routeKey() { return 0; }
    @Override public byte businessType() { return playerId == 0 ? 0 : PLAYER; }
    @Override public long businessId() { return playerId; }
    @Override public Metadata metadata() { return Metadata.empty(); }
}
