package cn.managame.gateway.filter;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;

/**
 * 网关过滤器：连接与包两级钩子，按 {@link FilterChain} 的装配顺序执行。
 * 全部回调都在网络 IO 线程上，实现必须轻量、无阻塞。
 */
public interface GatewayFilter {

    /** 新连接钩子：返回 false 拒绝接入（调用方负责关连接）。 */
    default boolean onConnect(GatewaySession session) {
        return true;
    }

    /**
     * 入站包钩子：返回 {@link GatewayErrorCode#OK} 放行，
     * 非 0 拦截（调用方按该错误码回包，包不再转发）。
     */
    int onPacket(GatewaySession session, GatewayPacket packet);

    /** 断线钩子：清理本过滤器为该会话维护的状态。 */
    default void onDisconnect(GatewaySession session) {
    }
}
