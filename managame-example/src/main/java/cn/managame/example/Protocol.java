package cn.managame.example;

import cn.managame.example.proto.BagRequest;
import cn.managame.example.proto.BagResponse;
import cn.managame.example.proto.BuyRequest;
import cn.managame.example.proto.BuyResponse;
import cn.managame.example.proto.LoginRequest;
import cn.managame.example.proto.LoginResponse;
import cn.managame.example.proto.RankRequest;
import cn.managame.example.proto.RankResponse;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.Map;

/**
 * The game's message ids, which the framework leaves to the game: every protobuf type the client and the game server
 * exchange has one, and the parser of its body. Id 0 is no message, as in an error answer.
 */
final class Protocol {
    private static final Map<Integer, Parser<? extends Message>> PARSERS = new HashMap<>();
    private static final Map<Class<?>, Integer> IDS = new HashMap<>();

    static {
        register(1, LoginRequest.class, LoginRequest.parser());
        register(2, LoginResponse.class, LoginResponse.parser());
        register(3, BuyRequest.class, BuyRequest.parser());
        register(4, BuyResponse.class, BuyResponse.parser());
        register(5, BagRequest.class, BagRequest.parser());
        register(6, BagResponse.class, BagResponse.parser());
        register(7, RankRequest.class, RankRequest.parser());
        register(8, RankResponse.class, RankResponse.parser());
    }

    private Protocol() {}

    private static void register(int id, Class<? extends Message> type, Parser<? extends Message> parser) {
        PARSERS.put(id, parser);
        IDS.put(type, id);
    }

    /** IllegalArgumentException for a type the game does not send. */
    static int idOf(Message message) {
        Integer id = IDS.get(message.getClass());
        if (id == null) throw new IllegalArgumentException("No message id for " + message.getClass().getName());
        return id;
    }

    /** The message of id {@code id} in {@code body}; IllegalArgumentException for an unknown id or broken bytes. */
    static Message parse(int id, ByteBuf body) {
        Parser<? extends Message> parser = PARSERS.get(id);
        if (parser == null) throw new IllegalArgumentException("Unknown message id " + id);
        return read(parser, body);
    }

    /** A protobuf message read from a buffer without copying it; IllegalArgumentException for broken bytes. */
    static <M extends Message> M read(Parser<M> parser, ByteBuf body) {
        try {
            return parser.parseFrom(body.nioBuffer());
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Broken message body", e);
        }
    }

    /** A protobuf message as an RPC body. */
    static ByteBuf body(Message message) { return Unpooled.wrappedBuffer(message.toByteArray()); }
}
