package cn.managame.gateway.filter;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;

/**
 * 登录 gate：未认证的会话只放行登录命令，其余一律回
 * {@link GatewayErrorCode#NOT_LOGGED_IN}。
 *
 * <p>认证态由转发链路维护：登录包转发到后端、响应 code=0 时
 * {@code PacketForwarder} 调 {@code session.setAuthenticated(true)}——
 * 即"登录数据包校验通过之后"其余命令才开闸。</p>
 */
public class AuthFilter implements GatewayFilter {

    private final int loginCommand;

    public AuthFilter(int loginCommand) {
        this.loginCommand = loginCommand;
    }

    @Override
    public int onPacket(GatewaySession session, GatewayPacket packet) {
        if (session.isAuthenticated() || packet.getCommand() == loginCommand) {
            return GatewayErrorCode.OK;
        }
        return GatewayErrorCode.NOT_LOGGED_IN;
    }
}
