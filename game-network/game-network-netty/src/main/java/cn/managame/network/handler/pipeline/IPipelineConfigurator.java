package cn.managame.network.handler.pipeline;

import io.netty.channel.ChannelPipeline;

public interface IPipelineConfigurator {
    void configure(ChannelPipeline pipeline);
}
