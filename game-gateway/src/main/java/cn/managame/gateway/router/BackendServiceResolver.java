package cn.managame.gateway.router;

import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;

/** Resolves the logical backend service type before instance-level routing. */
@FunctionalInterface
public interface BackendServiceResolver {
    String resolve(GatewaySession session, GatewayPacket packet);
}
