package com.github.huaye2007.mana.network.connection;

import java.util.concurrent.atomic.AtomicLong;

public class ServerConnectionIdGenerator implements IConnectionIdGenerator{

    private final AtomicLong nextId;

    private long serverId;

    public ServerConnectionIdGenerator(int serverId) {
        this.serverId = serverId;
        nextId = new AtomicLong(serverId);
    }

    @Override
    public long nextId() {
        return serverId<<32 | nextId.incrementAndGet();
    }
}
