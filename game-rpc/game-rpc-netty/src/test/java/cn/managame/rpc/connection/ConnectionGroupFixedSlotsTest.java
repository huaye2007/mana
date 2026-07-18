package cn.managame.rpc.connection;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConnectionGroupFixedSlotsTest {

    @Test
    void reconnectKeepsRouteKeyBoundToTheSameSlot() {
        EmbeddedChannel channel0 = new EmbeddedChannel();
        EmbeddedChannel channel1 = new EmbeddedChannel();
        EmbeddedChannel replacementChannel0 = new EmbeddedChannel();
        try {
            RpcConnection connection0 = new RpcConnection(channel0);
            RpcConnection connection1 = new RpcConnection(channel1);
            RpcConnection replacement0 = new RpcConnection(replacementChannel0);
            ConnectionGroup group = new ConnectionGroup();
            group.configureFixedSlots(2);
            group.setSlot(0, connection0);
            group.setSlot(1, connection1);

            assertSame(connection0, group.select(0));
            assertSame(connection1, group.select(1));

            group.remove(connection0);
            assertEquals(1, group.count());
            assertSame(connection1, group.select(0));

            group.setSlot(0, replacement0);
            assertEquals(2, group.count());
            assertSame(replacement0, group.select(0));
            assertSame(connection1, group.select(1));
        } finally {
            channel0.finishAndReleaseAll();
            channel1.finishAndReleaseAll();
            replacementChannel0.finishAndReleaseAll();
        }
    }
}
