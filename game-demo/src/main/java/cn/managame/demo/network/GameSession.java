package cn.managame.demo.network;

import io.netty.util.AttributeKey;
import cn.managame.demo.bus.role.RoleId;
import java.util.Objects;

/** Authenticated application input snapshot, installed by login business code. */
public record GameSession(long routeKey, RoleId roleId) {
    public static final AttributeKey<GameSession> KEY = AttributeKey.valueOf("cn.managame.demo.session");

    public GameSession {
        if (routeKey == 0) throw new IllegalArgumentException("routeKey must be nonzero");
        Objects.requireNonNull(roleId, "roleId");
    }
}
