package cn.managame.demo.common.runtime;

import cn.managame.runtime.route.RouteDomain;
import cn.managame.runtime.context.DefaultClientHandlerContext;
import cn.managame.network.connection.Connection;
import cn.managame.demo.network.GameSession;
import cn.managame.demo.bus.user.LoginReq;
import cn.managame.core.Metadatas;
import cn.managame.core.Metadata;

/** Application-owned domains with explicit IDs; never use enum ordinal as a Route ID. */
public enum GameDomain {
    ROLE(GameDomain.ROLE_ID),
    LOGIN(GameDomain.LOGIN_ID),
    SYSTEM(GameDomain.SYSTEM_ID);

    // Annotation arguments need compile-time constants rather than arbitrary enum values.
    public static final int ROLE_ID = 1;
    public static final int LOGIN_ID = 2;
    public static final int SYSTEM_ID = 3;
    public static final int ROLE_BUSINESS_ID_TYPE = 1;

    private final int id;

    GameDomain(int id) { this.id = id; }

    public int id() { return id; }

    public RouteDomain routeDomain() { return RouteDomain.of(id, name()); }

    public static GameDomain fromId(int id) {
        return switch (id) {
            case ROLE_ID -> ROLE;
            case LOGIN_ID -> LOGIN;
            case SYSTEM_ID -> SYSTEM;
            default -> throw new IllegalArgumentException("Unknown game domain: " + id);
        };
    }

    /** Application policy: each new domain explicitly chooses its routing and identity inputs. */
    public DefaultClientHandlerContext handlerContext(Connection connection, Object message) {
        return handlerContext(connection, Metadatas.empty(), message);
    }
    public DefaultClientHandlerContext handlerContext(Connection connection, Metadata metadata, Object message) {
        return switch (this) {
            case LOGIN -> new DefaultClientHandlerContext(id, ((LoginReq) message).getUserId(), 0, 0,
                    metadata, message, connection);
            case ROLE -> {
                GameSession session = connection.get(GameSession.KEY);
                if (session == null) throw new IllegalArgumentException("ROLE requires an authenticated GameSession");
                yield new DefaultClientHandlerContext(id, session.routeKey(), ROLE_BUSINESS_ID_TYPE,
                        session.roleId(), metadata, message, connection);
            }
            case SYSTEM -> throw new IllegalArgumentException("SYSTEM has no packet identity policy");
        };
    }
}
