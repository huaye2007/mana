package cn.managame.gateway.network.tcp;

import cn.managame.gateway.codec.BodyCodec;
import cn.managame.gateway.codec.GatewayPacketDecoder;
import cn.managame.gateway.codec.GatewayPacketEncoder;
import cn.managame.network.handler.pipeline.IPipelineConfigurator;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class GatewayTcpPipelineConfigurator implements IPipelineConfigurator {
    public static final int DEFAULT_READER_IDLE_SECONDS = 180;
    private static final GatewayIdleCloseHandler IDLE_CLOSE = new GatewayIdleCloseHandler();
    private final int readerIdleSeconds;
    private final BodyCodec bodyCodec;

    public GatewayTcpPipelineConfigurator() { this(DEFAULT_READER_IDLE_SECONDS, BodyCodec.IDENTITY); }
    public GatewayTcpPipelineConfigurator(int readerIdleSeconds, BodyCodec bodyCodec) {
        if (readerIdleSeconds < 0) throw new IllegalArgumentException("readerIdleSeconds must be non-negative");
        this.readerIdleSeconds = readerIdleSeconds;
        this.bodyCodec = Objects.requireNonNull(bodyCodec, "bodyCodec");
    }

    @Override
    public void configure(ChannelPipeline pipeline) {
        if (readerIdleSeconds > 0) {
            pipeline.addLast("gatewayIdleState", new IdleStateHandler(readerIdleSeconds, 0, 0, TimeUnit.SECONDS));
            pipeline.addLast("gatewayIdleClose", IDLE_CLOSE);
        }
        pipeline.addLast("gatewayPacketDecoder", new GatewayPacketDecoder(bodyCodec));
        pipeline.addLast("gatewayPacketEncoder", new GatewayPacketEncoder(bodyCodec));
    }
}
