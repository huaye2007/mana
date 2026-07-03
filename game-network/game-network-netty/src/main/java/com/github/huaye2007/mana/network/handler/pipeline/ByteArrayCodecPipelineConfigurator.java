package com.github.huaye2007.mana.network.handler.pipeline;

import com.github.huaye2007.mana.network.handler.codec.ByteArrayToByteBufEncoder;
import com.github.huaye2007.mana.network.handler.codec.ByteBufToByteArrayDecoder;
import io.netty.channel.ChannelPipeline;

public class ByteArrayCodecPipelineConfigurator implements IPipelineConfigurator {

    @Override
    public void configure(ChannelPipeline pipeline) {
        pipeline.addLast(PipelineConstants.NAME_BYTE_ARRAY_ENCODER, new ByteArrayToByteBufEncoder());
        pipeline.addLast(PipelineConstants.NAME_BYTE_ARRAY_DECODER, new ByteBufToByteArrayDecoder());
    }
}
