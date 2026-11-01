package cn.managame.example;

import com.google.protobuf.Message;
import io.netty.buffer.Unpooled;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * A blocking client of the game's protocol, as a game client would speak it: each request a frame, each answer read
 * back. For {@link ExampleClient} and the test; a real client is the game's own, in its own language.
 */
final class GameClient implements AutoCloseable {
    private final Socket socket;
    private final DataOutputStream out;
    private final DataInputStream in;
    private int seq;

    GameClient(String host, int port) throws IOException {
        socket = new Socket(host, port);
        socket.setSoTimeout(5000);
        out = new DataOutputStream(socket.getOutputStream());
        in = new DataInputStream(socket.getInputStream());
    }

    /** Sends {@code message} and waits for its answer. */
    Packet request(Message message) throws IOException {
        byte[] body = message.toByteArray();
        out.writeInt(12 + body.length);
        out.writeInt(Protocol.idOf(message));
        out.writeInt(++seq);
        out.writeInt(0);
        out.write(body);
        out.flush();
        int length = in.readInt(), id = in.readInt(), answerSeq = in.readInt(), code = in.readInt();
        byte[] answer = in.readNBytes(length - 12);
        return new Packet(answerSeq, code, id == 0 ? null : Protocol.parse(id, Unpooled.wrappedBuffer(answer)));
    }

    /** Sends raw bytes as one frame, as a broken or hostile client might. */
    void sendFrame(byte[] frame) throws IOException {
        out.writeInt(frame.length);
        out.write(frame);
        out.flush();
    }

    /** Whether the server closed the connection: the next read finds the end of the stream. */
    boolean isClosedByServer() throws IOException { return in.read() < 0; }

    @Override public void close() throws IOException { socket.close(); }
}
