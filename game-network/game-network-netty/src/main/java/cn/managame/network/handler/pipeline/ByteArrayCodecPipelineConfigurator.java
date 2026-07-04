package cn.managame.network.handler.pipeline;

import cn.managame.network.handler.codec.ByteArrayToByteBufEncoder;
import cn.managame.network.handler.codec.ByteBufToByteArrayDecoder;
import io.netty.channel.ChannelPipeline;

public class ByteArrayCodecPipelineConfigurator implements IPipelineConfigurator {

    @Override
    public void configure(ChannelPipeline pipeline) {
        pipeline.addLast(PipelineConstants.NAME_BYTE_ARRAY_ENCODER, new ByteArrayToByteBufEncoder());
        pipeline.addLast(PipelineConstants.NAME_BYTE_ARRAY_DECODER, new ByteBufToByteArrayDecoder());
    }
}
