package cn.managame.demo.network;

import cn.managame.demo.network.codec.GamePacketDecoder;
import cn.managame.demo.network.codec.GamePacketEncoder;
import cn.managame.demo.protocol.GamePacket;
import cn.managame.network.Connection;
import io.netty.channel.ChannelPipeline;

/** Both TCP endpoints install the same packet codecs and write owned packet objects. */
public final class GameProtocol {
    public static void pipeline(Connection connection, ChannelPipeline pipeline) {
        pipeline.addLast(new GamePacketDecoder(), new GamePacketEncoder());
    }

    public static boolean write(Connection connection, GamePacket packet) {
        return connection.write(packet, failure -> {
            if (failure != null) {
                System.getLogger(GameProtocol.class.getName()).log(System.Logger.Level.WARNING,
                        "Game packet write failed on " + connection.id(), failure);
                connection.close();
            }
        });
    }
    private GameProtocol() {}
}
