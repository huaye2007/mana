package cn.managame.example;

import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.runtime.dispatch.Dispatcher;
import java.util.Map;

/**
 * The game's ConnectionHandler: it decodes lines into messages, keeps the session on the connection, hands every
 * message to the Dispatcher and writes the answers back in the game's format. It runs on the connection's EventLoop
 * and blocks on nothing; the handlers run on the runtime.
 */
record Gateway(Dispatcher dispatcher, Map<Long, Connection> online) implements ConnectionHandler {
    @Override public void onActive(Connection connection) { connection.set(Session.KEY, new Session(connection, 0)); }

    @Override public void onRead(Connection connection, Object message) {
        String[] line = ((String) message).strip().split("\\s+", 3);
        if (line.length < 2) return;
        String seq = line[0];
        Object request;
        try {
            request = Messages.decode(line[1], line.length > 2 ? line[2] : "");
        } catch (RuntimeException unreadable) {
            connection.write(seq + " err bad request\n");
            return;
        }
        dispatcher.dispatch(connection.get(Session.KEY), request,
                result -> connection.write(seq + " ok " + result + "\n"),
                errorCode -> connection.write(seq + " err " + errorCode + "\n"));
    }

    /** A player who leaves logs out on their route, after the requests they sent before. */
    @Override public void onInActive(Connection connection) {
        Session session = connection.get(Session.KEY);
        if (session.playerId() == 0) return;
        online.remove(session.playerId(), connection);
        dispatcher.dispatch(session, new Messages.Logout());
    }

    @Override public void onException(Connection connection, Throwable cause) { connection.close(); }
}
