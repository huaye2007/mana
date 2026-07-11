package cn.managame.gateway.filter;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;

public interface GatewayFilter {
    default boolean onConnect(GatewaySession session) { return true; }
    default int onPacket(GatewaySession session, GatewayPacket packet) { return GatewayErrorCode.OK; }
    default void onDisconnect(GatewaySession session) { }
}
