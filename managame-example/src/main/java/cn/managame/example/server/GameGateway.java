package cn.managame.example.server;

import cn.managame.example.GamePacket;
import cn.managame.example.Protocols;
import cn.managame.example.proto.Login;
import cn.managame.network.connection.Connection;
import cn.managame.network.connection.ConnectionHandler;
import cn.managame.runtime.dispatch.Dispatcher;
import com.google.protobuf.Message;
import io.netty.util.AttributeKey;
import java.lang.System.Logger.Level;

/**
 * 服务器的连接处理：每个连接一个 {@link Session}，把解码出的 GamePacket 的 body 交给 Dispatcher，再把应答写成
 * GamePacket：成功时带应答消息和它的协议号，失败时带请求的协议号和错误码、没有 body。seq 为 0 的消息不需要应答。
 *
 * <p>登录应答 LoginRes 写出之前，先把角色绑定到连接的 Session 上，所以客户端收到登录成功后发的请求都以这个角色执行。
 * 绑定连接是网关的事，登录处理器只管返回 LoginRes。连接不可写时 network 拒绝写，这条应答就丢了。
 */
public final class GameGateway implements ConnectionHandler {
    private static final System.Logger LOG = System.getLogger(GameGateway.class.getName());
    private static final AttributeKey<Session> SESSION = AttributeKey.valueOf("example.session");
    private final Dispatcher dispatcher;
    private final Protocols protocols;

    public GameGateway(Dispatcher dispatcher, Protocols protocols) {
        this.dispatcher = dispatcher;
        this.protocols = protocols;
    }

    @Override public void onActive(Connection connection) { connection.set(SESSION, new Session(connection, 0)); }

    @Override public void onRead(Connection connection, Object message) {
        GamePacket packet = (GamePacket) message;
        Session session = connection.get(SESSION);
        if (packet.getSeq() == 0) {
            dispatcher.dispatch(session, packet.getBody());
            return;
        }
        int command = packet.getCommand(), seq = packet.getSeq();
        dispatcher.dispatch(session, packet.getBody(),
                result -> {
                    if (result instanceof Login.LoginRes res) connection.set(SESSION, new Session(connection, res.getRoleId()));
                    connection.write(protocols.packet(seq, (Message) result));
                },
                (code, args) -> connection.write(failure(command, seq, code)));   // 失败包暂时只带错误码，args 未下发
    }

    @Override public void onInActive(Connection connection) {
        // 下线：角色登录过时，在这里把下线消息交给 Dispatcher（保存、离开场景等）
    }

    @Override public void onException(Connection connection, Throwable cause) {
        LOG.log(Level.WARNING, "连接 " + connection.remoteAddress() + " 出错，关闭", cause);
        connection.close();
    }

    private static GamePacket failure(int command, int seq, int code) {
        GamePacket packet = new GamePacket();
        packet.setCommand(command);
        packet.setSeq(seq);
        packet.setCode(code);
        return packet;
    }
}
