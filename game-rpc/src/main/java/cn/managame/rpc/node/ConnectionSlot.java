package cn.managame.rpc.node;

import cn.managame.network.connection.Connection;
import java.util.concurrent.atomic.*;

final class ConnectionSlot {
    final int id;
    final AtomicReference<Connection> connection = new AtomicReference<>();
    // True throughout the complete delay/connect/handshake retry chain.
    final AtomicBoolean connecting = new AtomicBoolean();
    ConnectionSlot(int id) { this.id = id; }
}

