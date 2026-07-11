package cn.managame.gateway.network.websocket;

import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.codec.GatewayPacketEncoder;
import cn.managame.network.handler.codec.PacketToWebSocketFrameEncoder;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GatewayWebSocketCodecOrderTest {
    @Test void outboundPacketBecomesBinaryFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new PacketToWebSocketFrameEncoder(), new GatewayPacketEncoder());
        assertTrue(channel.writeOutbound(GatewayPacket.of(7, 2, 0, new byte[]{1, 2})));
        BinaryWebSocketFrame frame = channel.readOutbound();
        assertEquals(19, frame.content().readableBytes());
        frame.release();
        channel.finishAndReleaseAll();
    }
}
