package cn.managame.gateway.network.tcp;

import cn.managame.gateway.codec.BodyCodec;
import cn.managame.gateway.codec.GatewayPacketDecoder;
import cn.managame.gateway.codec.GatewayPacketEncoder;
import cn.managame.network.handler.pipeline.IPipelineConfigurator;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateHandler;

/**
 * 外网 TCP 管线（业务派发器之前）：读空闲检测 + 外网帧编解码。
 * 作为 {@code NettyTcpServer} 的 beforeDispatcher 配置注入；每 channel 调用一次，
 * handler 均为新实例，可安全携带每连接状态（{@link GatewayPacketDecoder} 的累积缓冲）。
 */
public class GatewayTcpPipelineConfigurator implements IPipelineConfigurator {

    /** 默认读空闲踢人阈值（秒）：客户端心跳间隔的数倍，漏一两拍不误杀。 */
    public static final int DEFAULT_READER_IDLE_SECONDS = 180;

    private final int readerIdleSeconds;
    private final BodyCodec bodyCodec;

    public GatewayTcpPipelineConfigurator() {
        this(DEFAULT_READER_IDLE_SECONDS, BodyCodec.IDENTITY);
    }

    /** @param readerIdleSeconds 读空闲阈值（秒），0 表示不做空闲检测（测试用） */
    public GatewayTcpPipelineConfigurator(int readerIdleSeconds, BodyCodec bodyCodec) {
        this.readerIdleSeconds = readerIdleSeconds;
        this.bodyCodec = bodyCodec;
    }

    @Override
    public void configure(ChannelPipeline pipeline) {
        if (readerIdleSeconds > 0) {
            pipeline.addLast(new IdleStateHandler(readerIdleSeconds, 0, 0));
            pipeline.addLast(new GatewayIdleCloseHandler());
        }
        pipeline.addLast(new GatewayPacketEncoder(bodyCodec));
        pipeline.addLast(new GatewayPacketDecoder(bodyCodec));
    }
}
