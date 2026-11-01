package cn.managame.example;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.runtime.dispatch.Dispatcher;
import com.google.protobuf.Message;

/**
 * The game's ConnectionHandler: it receives the packets {@link PacketCodec} decoded, keeps the session on the
 * connection, hands each protobuf message to the Dispatcher and writes its answer back as a packet under the request's
 * seq. It runs on the connection's EventLoop and blocks on nothing; the handlers run on the runtime.
 */
record Gateway(Dispatcher dispatcher, OnlinePlayers online) implements ConnectionHandler {
    /** Dispatched by the gateway itself, not a client, when a logged-in connection closes. */
    record Logout() {}

    @Override public void onActive(Connection connection) { connection.set(Session.KEY, new Session(connection, 0)); }

    @Override public void onRead(Connection connection, Object message) {
        Packet request = (Packet) message;
        if (request.message() == null) return;
        dispatcher.dispatch(connection.get(Session.KEY), request.message(),
                result -> connection.write(new Packet(request.seq(), 0, (Message) result)),
                errorCode -> connection.write(Packet.error(request.seq(), errorCode)));
    }

    /** A player who leaves logs out on their route, after the requests they sent before. */
    @Override public void onInActive(Connection connection) {
        Session session = connection.get(Session.KEY);
        if (session.playerId() == 0) return;
        online.remove(session.playerId(), connection);
        dispatcher.dispatch(session, new Logout());
    }

    /** A frame the codec cannot read, among others: the connection is closed, as a client that breaks the protocol. */
    @Override public void onException(Connection connection, Throwable cause) { connection.close(); }
}
