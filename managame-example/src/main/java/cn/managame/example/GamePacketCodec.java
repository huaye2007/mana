package cn.managame.example;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * GamePacket 的编解码，放在 network 的长度帧（{@code NetworkHandlers.lengthFieldFraming}）之后，一帧一个包：
 *
 * <pre>
 * | command 4 字节 | seq 4 字节 | code 4 字节 | body：protobuf 字节，可以为空 |
 * </pre>
 *
 * 整数都是大端。body 按 command 在 {@link Protocols} 里找到的消息类解析；失败应答 code 不为 0、没有 body，解出来
 * body 为 null。没有状态，一个实例给所有连接共用。
 */
@ChannelHandler.Sharable
public final class GamePacketCodec extends MessageToMessageCodec<ByteBuf, GamePacket> {
    /** command、seq、code 三个 int。 */
    public static final int HEADER_LENGTH = 12;
    /** 一帧的最大长度，给 lengthFieldFraming 用；超过时报 TooLongFrameException。 */
    public static final int MAX_FRAME_LENGTH = 1 << 20;
    private final Protocols protocols;

    public GamePacketCodec(Protocols protocols) { this.protocols = Objects.requireNonNull(protocols, "protocols"); }

    @Override
    protected void encode(ChannelHandlerContext ctx, GamePacket packet, List<Object> out) throws IOException {
        Message body = (Message) packet.getBody();
        int size = body == null ? 0 : body.getSerializedSize();
        ByteBuf frame = ctx.alloc().ioBuffer(HEADER_LENGTH + size);
        try {
            frame.writeInt(packet.getCommand()).writeInt(packet.getSeq()).writeInt(packet.getCode());
            if (body != null) {   // 直接写进 frame 的内存，不经过中间数组
                body.writeTo(CodedOutputStream.newInstance(frame.nioBuffer(frame.writerIndex(), size)));
                frame.writerIndex(frame.writerIndex() + size);
            }
        } catch (IOException | RuntimeException e) {
            frame.release();
            throw e;
        }
        out.add(frame);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf frame, List<Object> out) throws IOException {
        GamePacket packet = new GamePacket();
        packet.setCommand(frame.readInt());
        packet.setSeq(frame.readInt());
        packet.setCode(frame.readInt());
        if (frame.isReadable() || packet.getCode() == 0) packet.setBody(protocols.parse(packet.getCommand(), frame.nioBuffer()));
        out.add(packet);
    }
}
