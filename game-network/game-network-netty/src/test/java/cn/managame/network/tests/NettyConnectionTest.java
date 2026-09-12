package cn.managame.network.tests;

import static org.junit.jupiter.api.Assertions.*;

import cn.managame.network.AttributeKey;
import cn.managame.network.ConnectionType;
import cn.managame.network.netty.NettyConnection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.jupiter.api.Test;

class NettyConnectionTest {
    @Test
    void delegatesToChannelWithoutIndependentState() {
        var channel = new EmbeddedChannel();
        var connection = new NettyConnection(channel, ConnectionType.TCP);
        try {
            assertEquals(ConnectionType.TCP, connection.type());
            assertEquals(channel.id().asLongText(), connection.id());
            assertEquals(channel.isActive(), connection.isActive());
            assertEquals(channel.isWritable(), connection.isWritable());
            assertEquals(channel.remoteAddress(), connection.remoteAddress());
            var key = AttributeKey.of("player", String.class);
            var other = AttributeKey.of("player", String.class);
            connection.set(key, "alice");
            assertNull(connection.get(other));
            assertEquals("alice", channel.attr(io.netty.util.AttributeKey.valueOf(key.id())).get());
            assertTrue(connection.compareAndSet(key, "alice", "bob"));
            ByteBuf message = Unpooled.buffer(1).writeByte(7);
            assertTrue(connection.write(message));
            assertSame(message, channel.readOutbound());
            message.release();
            connection.close();
            assertTrue(channel.closeFuture().isDone());
            assertEquals(channel.isActive(), connection.isActive());
            assertEquals(channel.isWritable(), connection.isWritable());
            assertEquals("bob", connection.remove(key));
            assertNull(connection.get(key));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
