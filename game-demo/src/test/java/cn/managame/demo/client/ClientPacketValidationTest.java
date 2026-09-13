package cn.managame.demo.client;

import static cn.managame.demo.DemoCalls.*;

import cn.managame.demo.protocol.client.ClientProtocol.SpendGoldRes;
import cn.managame.demo.protocol.client.ClientProtocol.WalletChangedNotify;
import cn.managame.demo.protocol.GamePacket;
import static cn.managame.demo.protocol.client.ClientProtocol.WALLET_CHANGED;
import java.util.List;
import cn.managame.demo.protocol.client.ClientProtocol.SpendGoldReq;
import cn.managame.demo.serialization.MessageSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import static cn.managame.demo.protocol.client.ClientProtocol.SPEND_GOLD;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ClientPacketValidationTest {
    @Test void clientRejectsCommandMismatchAndIgnoresUnknownSequences() throws Exception {
        for (boolean mismatch : new boolean[] { true, false }) {
            try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 var worker = Executors.newSingleThreadExecutor();
                 var client = new DemoClient()) {
                var peer = worker.submit(() -> {
                    try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(5000);
                        var input = new DataInputStream(socket.getInputStream());
                        var output = new DataOutputStream(socket.getOutputStream());
                        int length = input.readInt();
                        assertEquals(SPEND_GOLD, input.readInt());
                        int requestId = input.readInt();
                        assertEquals(0, input.readInt()); assertEquals(0, input.readInt());
                        byte[] body = new byte[length - 16];
                        input.readFully(body);
                        assertEquals(new SpendGoldReq(7, 10, 123), MessageSerializer.deserialize(body, SpendGoldReq.class));
                        if (mismatch) output.write(response(SPEND_GOLD + 1, requestId, 90));
                        else {
                            output.write(response(SPEND_GOLD, requestId + 1, 5));
                            output.write(response(SPEND_GOLD, requestId, 90));
                        }
                        output.flush();
                    }
                    return null;
                });
                client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()));
                var response = spend(client, 7, 10, 123);
                if (mismatch) {
                    var failure = assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS));
                    assertInstanceOf(IllegalArgumentException.class, failure.getCause());
                    assertEquals("Response command differs from request", failure.getCause().getMessage());
                } else assertEquals(new SpendGoldRes(7, 90, 123), response.get(5, TimeUnit.SECONDS));
                peer.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test void notificationsNeedNoPendingRequestAndProduceNoAcknowledgement() throws Exception {
        var received = new LinkedBlockingQueue<WalletChangedNotify>();
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var worker = Executors.newSingleThreadExecutor();
             var client = new DemoClient()) {
            client.onNotify(WalletChangedNotify.class, received::add);
            var peer = worker.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    output.write(notification(WALLET_CHANGED, MessageSerializer.serialize(new WalletChangedNotify(7, 100))));
                    output.flush();
                    int length = input.readInt();
                    assertEquals(SPEND_GOLD, input.readInt());
                    assertEquals(1, input.readInt()); // A notify has not allocated a client sequence.
                    assertEquals(0, input.readInt());
                    assertEquals(GamePacket.REQUEST, input.readInt());
                    assertEquals(length - 16, input.readNBytes(length - 16).length);
                    output.write(response(SPEND_GOLD, 1, 90));
                    output.write(notification(WALLET_CHANGED, MessageSerializer.serialize(new WalletChangedNotify(7, 90))));
                    output.flush();
                    assertEquals(-1, input.read()); // Close, with no notify acknowledgements on the wire.
                }
                return null;
            });
            client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()));
            assertEquals(new WalletChangedNotify(7, 100), received.poll(3, TimeUnit.SECONDS));
            assertEquals(new SpendGoldRes(7, 90, 123), spend(client, 7, 10, 123).get(3, TimeUnit.SECONDS));
            assertEquals(new WalletChangedNotify(7, 90), received.poll(3, TimeUnit.SECONDS));
            client.close();
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void malformedNotificationsFailPendingCallsEvenWithoutASubscription() throws Exception {
        for (byte[] frame : List.of(
                notification(9999, MessageSerializer.serialize(new WalletChangedNotify(7, 90))),
                notification(WALLET_CHANGED, MessageSerializer.serialize(new SpendGoldRes(7, 90, 123))),
                notification(WALLET_CHANGED, new byte[0]))) {
            try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 var worker = Executors.newSingleThreadExecutor();
                 var client = new DemoClient()) {
                var peer = worker.submit(() -> {
                    try (Socket socket = listener.accept()) {
                        socket.setSoTimeout(5000);
                        var input = new DataInputStream(socket.getInputStream());
                        int length = input.readInt();
                        assertEquals(length, input.readNBytes(length).length);
                        socket.getOutputStream().write(frame);
                        socket.getOutputStream().flush();
                        assertEquals(-1, input.read());
                    }
                    return null;
                });
                client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()));
                var call = spend(client, 7, 10, 123);
                var failure = assertThrows(ExecutionException.class, () -> call.get(3, TimeUnit.SECONDS));
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
                peer.get(3, TimeUnit.SECONDS);
            }
        }
    }

    private static byte[] notification(int command, byte[] body) {
        return ByteBuffer.allocate(20 + body.length).putInt(16 + body.length)
                .putInt(command).putInt(0).putInt(0).putInt(GamePacket.NOTIFY).put(body).array();
    }

    private static byte[] response(int command, int requestId, int gold) {
        byte[] body = MessageSerializer.serialize(new SpendGoldRes(7, gold, 123));
        return ByteBuffer.allocate(20 + body.length).putInt(16 + body.length)
                .putInt(command).putInt(requestId).putInt(0).putInt(1).put(body).array();
    }
}
