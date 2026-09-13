package cn.managame.demo.serialization;

import cn.managame.demo.protocol.GamePacket;
import static cn.managame.demo.protocol.rpc.RpcProtocol.*;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;

import static cn.managame.demo.protocol.client.ClientProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class MessageSerializerTest {
    public record UnregisteredReq(int value) {}

    @Test void independentlyConfiguredEndpointsReadAllMessageTypes() {
        var peer = MessageSerializer.newSerializer();
        for (Object message : List.of(new SpendGoldReq(7, 12, 123), new GetWalletReq(7, 123),
                new SpendGoldRes(7, 88, 123), new ErrorRes(List.of("", "金币不足", "12", "🙂")),
                new ErrorRes(List.of()), new GetWalletRes(7, 88), new WalletChangedNotify(7, 88),
                new GrantGoldReq(10), new GrantGoldRes(7, 110, 123))) {
            assertEquals(message, peer.deserialize(MessageSerializer.serialize(message)));
            assertEquals(message, MessageSerializer.deserialize(peer.serialize(message), message.getClass()));
        }
        var error = MessageSerializer.deserialize(MessageSerializer.serialize(new ErrorRes(List.of("a"))), ErrorRes.class);
        assertThrows(UnsupportedOperationException.class, () -> error.args().add("b"));
    }

    @Test void malformedWrongTypeAndTrailingBodiesAreRejectedAndPoolRecovers() {
        var request = new SpendGoldReq(7, 1, 42);
        byte[] valid = MessageSerializer.serialize(request);
        for (int size = 0; size < valid.length; size++) {
            byte[] truncated = Arrays.copyOf(valid, size);
            assertThrows(IllegalArgumentException.class,
                    () -> MessageSerializer.deserialize(truncated, SpendGoldReq.class));
        }
        assertThrows(IllegalArgumentException.class, () ->
                MessageSerializer.deserialize(Arrays.copyOf(valid, valid.length + 1), SpendGoldReq.class));
        assertThrows(IllegalArgumentException.class, () ->
                MessageSerializer.deserialize(valid, SpendGoldRes.class));
        assertThrows(IllegalArgumentException.class, () -> MessageSerializer.serialize(new UnregisteredReq(1)));
        assertEquals(request, MessageSerializer.deserialize(valid, SpendGoldReq.class));
    }

    @Test void borrowedRpcBufferKeepsIndexesAndOwnership() {
        var wallet = new SpendGoldRes(7, 88, 42);
        var body = Unpooled.directBuffer().writeInt(99).writeBytes(MessageSerializer.serialize(wallet));
        body.skipBytes(4);
        int writerIndex = body.writerIndex();
        try {
            assertEquals(wallet, MessageSerializer.deserialize(body, SpendGoldRes.class));
            assertEquals(4, body.readerIndex());
            assertEquals(writerIndex, body.writerIndex());
            assertEquals(1, body.refCnt());
        } finally { body.release(); }
    }

    @Test void concurrentVirtualThreadsDoNotShareMutableSerializationState() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 200; i++) {
                int id = i;
                futures.add(executor.submit(() -> {
                    var message = new SpendGoldRes(id, id + 1, id + 2);
                    assertEquals(message, MessageSerializer.deserialize(MessageSerializer.serialize(message), SpendGoldRes.class));
                }));
            }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void serializedBodiesStillRespectTcpFrameLimit() {
        byte[] body = MessageSerializer.serialize(new ErrorRes(List.of("x".repeat(GamePacket.MAX_FRAME_BYTES))));
        assertThrows(IllegalArgumentException.class, () -> new GamePacket(1, 1, 1, GamePacket.RESPONSE, body));
    }
}
