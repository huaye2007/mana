package cn.managame.demo.network;

import cn.managame.runtime.GameRuntime;
import cn.managame.runtime.protocol.ProtocolRegistry;
import cn.managame.runtime.protocol.ProtocolType;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import org.apache.fory.ThreadSafeFory;
import org.springframework.stereotype.Component;
import cn.managame.runtime.error.RuntimeDispatchException;

@Component
public class GamePacketHandler implements ConnectionHandler {
    private final ThreadSafeFory fory;
    private final ProtocolRegistry protocols;
    private final GameRuntime runtime;

    public GamePacketHandler(ThreadSafeFory fory, GameRuntime runtime) {
        this.fory = fory;
        this.protocols = runtime.protocols();
        this.runtime = runtime;
    }

    @Override public void onConnected(Connection connection) {}

    @Override
    public void onMessage(Connection connection, Object message) {
        GamePacket packet = (GamePacket) message;
        var protocol = protocols.get(ProtocolType.REQUEST, packet.getCommand());
        if (protocol == null) {
            throw new IllegalArgumentException("Unknown request command: " + packet.getCommand());
        }
        Class<?> type = protocol.messageType();
        Object decodedMessage = type.cast(fory.deserialize(packet.getBody()));
        try { runtime.dispatch(connection, GamePacketMetadata.from(packet), decodedMessage); }
        catch (RuntimeDispatchException rejected) {
            var response = new GamePacket();
            response.setCommand(packet.getCommand()); response.setSeq(packet.getSeq()); response.setCode(rejected.errorCode());
            var status = connection.write(response);
            System.getLogger(GamePacketHandler.class.getName()).log(System.Logger.Level.WARNING,
                    "Packet rejected: command={0}, seq={1}, error={2}, response={3}",
                    packet.getCommand(), packet.getSeq(), rejected.errorCode(), status);
        }
    }

    @Override public void onDisconnected(Connection connection) {}

    @Override
    public void onException(Connection connection, Throwable cause) {
        System.getLogger(GamePacketHandler.class.getName()).log(System.Logger.Level.ERROR, "Invalid packet or connection failure", cause);
        connection.close();
    }
}
