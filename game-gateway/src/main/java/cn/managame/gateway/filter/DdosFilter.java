package cn.managame.gateway.filter;

import cn.managame.gateway.codec.GatewayErrorCode;
import cn.managame.gateway.codec.GatewayPacket;
import cn.managame.gateway.session.GatewaySession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP 级防护：单 IP 并发连接数上限 + 单 IP 聚合包速率（同 IP 所有连接共享一个桶）。
 * 连接数超限直接拒绝接入；包速率超限回 {@link GatewayErrorCode#CONNECTION_LIMITED}。
 *
 * <p>连接计数的增减都在 {@code compute} 回调内完成：与"归零移除条目"原子，
 * 不会在已被移除的条目上计数。这是网关进程内的兜底防线，挡的是"少量源
 * 刷爆游戏逻辑"的应用层滥用；容量型 DDoS 要靠前置的四层/云端清洗。</p>
 */
public class DdosFilter implements GatewayFilter {

    private static final Logger logger = LoggerFactory.getLogger(DdosFilter.class);

    private final int maxConnectionsPerIp;
    private final double packetsPerSecondPerIp;
    private final double burstPerIp;

    private final Map<String, IpState> ipStates = new ConcurrentHashMap<>();

    public DdosFilter(int maxConnectionsPerIp, double packetsPerSecondPerIp, double burstPerIp) {
        if (maxConnectionsPerIp < 1) {
            throw new IllegalArgumentException("maxConnectionsPerIp must be >= 1, got " + maxConnectionsPerIp);
        }
        this.maxConnectionsPerIp = maxConnectionsPerIp;
        this.packetsPerSecondPerIp = packetsPerSecondPerIp;
        this.burstPerIp = burstPerIp;
    }

    @Override
    public boolean onConnect(GatewaySession session) {
        String ip = session.getClientIp();
        boolean[] accepted = {false};
        ipStates.compute(ip, (key, state) -> {
            IpState current = state == null
                    ? new IpState(new TokenBucket(packetsPerSecondPerIp, burstPerIp)) : state;
            if (current.connections < maxConnectionsPerIp) {
                current.connections++;
                accepted[0] = true;
            }
            return current;
        });
        if (!accepted[0]) {
            logger.warn("connection limit reached for ip {}, rejecting", ip);
        }
        return accepted[0];
    }

    @Override
    public int onPacket(GatewaySession session, GatewayPacket packet) {
        IpState state = ipStates.get(session.getClientIp());
        if (state == null) {
            // onConnect 未登记（不应发生），放行交给会话级限流兜底
            return GatewayErrorCode.OK;
        }
        return state.bucket.tryAcquire() ? GatewayErrorCode.OK : GatewayErrorCode.CONNECTION_LIMITED;
    }

    @Override
    public void onDisconnect(GatewaySession session) {
        // 归零时移除条目，防 IP 维度状态只增不减
        ipStates.computeIfPresent(session.getClientIp(), (key, state) -> {
            state.connections--;
            return state.connections <= 0 ? null : state;
        });
    }

    /** connections 只在 compute 回调（map 分段锁）内读写。 */
    private static final class IpState {
        final TokenBucket bucket;
        int connections;

        IpState(TokenBucket bucket) {
            this.bucket = bucket;
        }
    }
}
