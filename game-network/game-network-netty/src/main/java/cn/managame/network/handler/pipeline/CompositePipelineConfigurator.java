package cn.managame.network.handler.pipeline;

import io.netty.channel.ChannelPipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CompositePipelineConfigurator implements IPipelineConfigurator {

    private final List<IPipelineConfigurator> configurators = new ArrayList<>();

    public CompositePipelineConfigurator(List<IPipelineConfigurator> configurators) {
        this.configurators.addAll(configurators);
    }

    public static CompositePipelineConfigurator of(IPipelineConfigurator... configurators) {
        return new CompositePipelineConfigurator(Arrays.asList(configurators));
    }

    @Override
    public void configure(ChannelPipeline pipeline) {
        for(IPipelineConfigurator configurator : configurators){
            if(configurator != null){
                configurator.configure(pipeline);
            }
        }
    }
}
