package cn.managame.example.server;

import cn.managame.FrameworkErrorCodes;
import cn.managame.example.DomainConstant;
import cn.managame.example.GamePacket;
import cn.managame.example.Protocols;
import cn.managame.example.proto.Login;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.runtime.GameContext;
import cn.managame.runtime.dispatch.Dispatcher;
import com.google.protobuf.Message;
import io.netty.util.AttributeKey;
import java.lang.System.Logger.Level;
import java.util.function.LongConsumer;

/**
 * 服务器的连接处理：每个连接一个 {@link Session}，把解码出的 GamePacket 的 body 连同按会话建立的上下文交给 Dispatcher，
 * 再把应答写成 GamePacket：成功时带应答消息和它的协议号；失败时带请求的协议号和错误码、没有 body，成功但没有内容时也一样，
 * 错误码为 0。seq 为 0 的消息不需要应答。
 *
 * <p>登录应答 LoginRes 写出之前，先把角色绑定到连接的 Session 上，所以客户端收到登录成功后发的请求都以这个角色执行。
 * 绑定连接是网关的事，登录处理器只管返回 LoginRes。连接断开时，经 {@code GameRuntime.handOn} 清理角色在途状态（如取消流式 AI 对话）。
 */
public final class GameGateway implements ConnectionHandler {
    private static final System.Logger LOG = System.getLogger(GameGateway.class.getName());
    private static final AttributeKey<Session> SESSION = AttributeKey.valueOf("example.session");
    private final Dispatcher dispatcher;
    private final Protocols protocols;
    private final LongConsumer onRoleDisconnect;

    public GameGateway(Dispatcher dispatcher, Protocols protocols) {
        this(dispatcher, protocols, roleId -> {});
    }

    public GameGateway(Dispatcher dispatcher, Protocols protocols, LongConsumer onRoleDisconnect) {
        this.dispatcher = dispatcher;
        this.protocols = protocols;
        this.onRoleDisconnect = onRoleDisconnect != null ? onRoleDisconnect : roleId -> {};
    }

    @Override public void onActive(Connection connection) { connection.set(SESSION, new Session(connection, 0)); }

    @Override public void onRead(Connection connection, Object message) {
        GamePacket packet = (GamePacket) message;
        GameContext source = context(connection.get(SESSION));
        if (packet.getSeq() == 0) {
            dispatcher.dispatch(source, packet.getBody());
            return;
        }
        int command = packet.getCommand(), seq = packet.getSeq();
        dispatcher.dispatch(source, packet.getBody(), result -> {
            if (result.code() == FrameworkErrorCodes.SUCCESS && result.data() instanceof Message body) {
                if (body instanceof Login.LoginRes res) connection.set(SESSION, new Session(connection, res.getRoleId()));
                connection.write(protocols.packet(seq, body));
            } else {
                connection.write(noBody(command, seq, result.code()));   // 失败包暂时只带错误码，args 未下发
            }
        });
    }

    @Override public void onInActive(Connection connection) {
        Session session = connection.get(SESSION);
        if (session != null && session.roleId() != 0) {
            onRoleDisconnect.accept(session.roleId());
        }
    }

    @Override public void onException(Connection connection, Throwable cause) {
        LOG.log(Level.WARNING, "连接 " + connection.remoteAddress() + " 出错，关闭", cause);
        connection.close();
    }

    /** 消息以谁的身份执行：登录后是会话的角色，登录前没有身份。domain 和路由键由处理器决定。 */
    private static GameContext context(Session session) {
        long role = session.roleId();
        return role == 0 ? GameContext.of(DomainConstant.LOGIN, 0) : GameContext.of(DomainConstant.PLAYER, role, Session.ROLE, role);
    }

    private static GamePacket noBody(int command, int seq, int code) {
        GamePacket packet = new GamePacket();
        packet.setCommand(command);
        packet.setSeq(seq);
        packet.setCode(code);
        return packet;
    }
}
