package com.github.huaye2007.mana.dev.protocol;

/**
 * 心跳协议常量。
 *
 * <p>心跳是普通业务命令：由 {@code RoleController#heartbeat} 在 PLAYER 组处理，
 * 请求 {@code HeartbeatReq} 带客户端时间戳，响应 {@code HeartbeatRes} 回服务端当前时间。
 * ping 与 pong 复用同一个 command，靠方向区分。</p>
 */
public final class HeartbeatConstant {

    /** 心跳 command。 */
    public static final int COMMAND = 1001;

    private HeartbeatConstant() {
    }
}
