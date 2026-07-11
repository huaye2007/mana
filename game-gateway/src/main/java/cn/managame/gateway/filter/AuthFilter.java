package cn.managame.gateway.filter;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;

public final class AuthFilter implements GatewayFilter {
    private final int loginCommand;

    public AuthFilter(int loginCommand) {
        if (loginCommand <= 0) throw new IllegalArgumentException("loginCommand must be positive");
        this.loginCommand = loginCommand;
    }

    @Override
    public int onPacket(GatewaySession session, GatewayPacket packet) {
        return session.isAuthenticated() || packet.getCommand() == loginCommand
                ? GatewayErrorCode.OK : GatewayErrorCode.NOT_LOGGED_IN;
    }
}
