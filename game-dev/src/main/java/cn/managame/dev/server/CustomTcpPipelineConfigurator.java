package cn.managame.dev.server;

import cn.managame.dev.protocol.GamePacketDecoder;
import cn.managame.dev.protocol.GamePacketEncoder;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateHandler;

/**
 * 外网管线：读空闲检测（踢掉心跳断供的连接）+ 外网帧编解码。
 * 该 configurator 每个 channel 调用一次，handler 都是新实例，可安全携带状态。
 */
public class CustomTcpPipelineConfigurator {

    /** 默认读空闲踢人阈值（秒）：客户端心跳间隔的数倍，漏一两拍不误杀。 */
    public static final int DEFAULT_READER_IDLE_SECONDS = 180;

    private final int readerIdleSeconds;

    public CustomTcpPipelineConfigurator() {
        this(DEFAULT_READER_IDLE_SECONDS);
    }

    /** @param readerIdleSeconds 读空闲踢人阈值（秒），0 表示不做空闲检测（测试用） */
    public CustomTcpPipelineConfigurator(int readerIdleSeconds) {
        this.readerIdleSeconds = readerIdleSeconds;
    }

    public void configure(ChannelPipeline pipeline) {
        if (readerIdleSeconds > 0) {
            pipeline.addLast(new IdleStateHandler(readerIdleSeconds, 0, 0));
            pipeline.addLast(new IdleKickHandler());
        }
        pipeline.addLast(new GamePacketEncoder());
        pipeline.addLast(new GamePacketDecoder());
    }
}
