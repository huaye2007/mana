package cn.managame.example;

import cn.managame.example.proto.Login;
import cn.managame.example.proto.World;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 协议号表：每个 protobuf 消息类对应一个协议号，请求、应答、推送都一样。解码按协议号找解析器，编码按消息类找协议号。
 * 建好后只读，服务器和客户端共用同一张表。
 */
public final class Protocols {
    private final Map<Integer, Parser<? extends Message>> parsers;
    private final Map<Class<?>, Integer> commands;

    private Protocols(Map<Integer, Parser<? extends Message>> parsers, Map<Class<?>, Integer> commands) {
        this.parsers = Map.copyOf(parsers);
        this.commands = Map.copyOf(commands);
    }

    /** 游戏的全部协议号；新增消息时在这里加一行。 */
    public static Protocols game() {
        return of(Map.of(
                1001, Login.LoginReq.getDefaultInstance(),
                1002, Login.LoginRes.getDefaultInstance(),
                1003, World.EnterWorldReq.getDefaultInstance(),
                1004, World.JoinWorldReq.getDefaultInstance(),
                1005, World.JoinWorldRes.getDefaultInstance()));
    }

    /** 协议号到消息类（用它的默认实例表示）的表；一个消息类绑定两个协议号时抛 IllegalArgumentException。 */
    public static Protocols of(Map<Integer, ? extends Message> prototypes) {
        Map<Integer, Parser<? extends Message>> parsers = new HashMap<>();
        Map<Class<?>, Integer> commands = new HashMap<>();
        prototypes.forEach((command, prototype) -> {
            parsers.put(command, prototype.getParserForType());
            Integer existing = commands.putIfAbsent(prototype.getClass(), command);
            if (existing != null)
                throw new IllegalArgumentException(prototype.getClass().getName() + " 绑定了两个协议号：" + existing + "、" + command);
        });
        return new Protocols(parsers, commands);
    }

    /** 消息的协议号；没有绑定时抛 IllegalArgumentException。 */
    public int commandOf(Message message) {
        Integer command = commands.get(message.getClass());
        if (command == null) throw new IllegalArgumentException(message.getClass().getName() + " 没有绑定协议号");
        return command;
    }

    /** 按协议号解析消息体；未知协议号抛 IllegalArgumentException。 */
    public Message parse(int command, ByteBuffer body) throws InvalidProtocolBufferException {
        Parser<? extends Message> parser = parsers.get(command);
        if (parser == null) throw new IllegalArgumentException("未知协议号 " + command);
        return parser.parseFrom(body);
    }

    /** 装着 body 的包，协议号取 body 的；seq 为 0 表示不需要应答，或是推送。 */
    public GamePacket packet(int seq, Message body) {
        GamePacket packet = new GamePacket();
        packet.setCommand(commandOf(body));
        packet.setSeq(seq);
        packet.setBody(body);
        return packet;
    }
}
