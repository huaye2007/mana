package cn.managame.network.handler.pipeline;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.FixedLengthFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CompositePipelineConfiguratorTest {

    @Test
    void configuresPipelineInOrder() {
        CompositePipelineConfigurator configurator = CompositePipelineConfigurator.of(
                pipeline -> pipeline.addLast(PipelineConstants.NAME_IDLE_STATE,
                        new IdleStateHandler(10, 0, 0, TimeUnit.SECONDS)),
                pipeline -> pipeline.addLast(PipelineConstants.NAME_FRAMER,
                        new FixedLengthFrameDecoder(2))
        );
        EmbeddedChannel channel = new EmbeddedChannel();

        configurator.configure(channel.pipeline());

        assertNotNull(channel.pipeline().get(PipelineConstants.NAME_IDLE_STATE));
        assertNotNull(channel.pipeline().get(PipelineConstants.NAME_FRAMER));
    }
}
