package com.github.huaye2007.mana.network.handler.pipeline;

import io.netty.channel.ChannelPipeline;

public interface IPipelineConfigurator {
    void configure(ChannelPipeline pipeline);
}
