package com.github.huaye2007.mana.network.handler.pipeline;

import com.github.huaye2007.mana.network.handler.http.NettyHttpDispatcher;
import com.github.huaye2007.mana.network.http.HttpProtocol;
import com.github.huaye2007.mana.network.http.IHttpHandler;
import com.github.huaye2007.mana.network.server.NetworkHttpServerConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;

public class DefaultHttpPipelineConfigurator implements IPipelineConfigurator {

    private static final String HTTP1_PROTOCOL = "HTTP/1.1";
    private static final String HTTP2_PROTOCOL = "HTTP/2";

    private final NetworkHttpServerConfig config;
    private final IHttpHandler httpHandler;

    public DefaultHttpPipelineConfigurator(NetworkHttpServerConfig config, IHttpHandler httpHandler) {
        this.config = config;
        this.httpHandler = httpHandler;
    }

    @Override
    public void configure(ChannelPipeline pipeline) {
        if(config.getHttpProtocol() == HttpProtocol.HTTP2){
            configureHttp2(pipeline);
        } else if(config.getHttpProtocol() == HttpProtocol.HTTP1_AND_HTTP2){
            configureHttp1AndHttp2(pipeline);
        } else {
            configureHttp1(pipeline);
        }
    }

    private void configureHttp1(ChannelPipeline pipeline) {
        pipeline.addLast(PipelineConstants.NAME_HTTP_CODEC, new HttpServerCodec());
        pipeline.addLast(PipelineConstants.NAME_HTTP_AGGREGATOR,
                new HttpObjectAggregator(config.getHttpMaxContentLength()));
        pipeline.addLast(PipelineConstants.NAME_HTTP_DISPATCHER,
                new NettyHttpDispatcher(httpHandler, HTTP1_PROTOCOL));
    }

    private void configureHttp2(ChannelPipeline pipeline) {
        pipeline.addLast(PipelineConstants.NAME_HTTP2_FRAME_CODEC,
                Http2FrameCodecBuilder.forServer().build());
        pipeline.addLast(PipelineConstants.NAME_HTTP2_MULTIPLEX,
                new Http2MultiplexHandler(newHttp2StreamInitializer()));
    }

    private void configureHttp1AndHttp2(ChannelPipeline pipeline) {
        HttpServerCodec sourceCodec = new HttpServerCodec();
        Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forServer().build();
        Http2MultiplexHandler multiplexHandler = new Http2MultiplexHandler(newHttp2StreamInitializer());
        HttpServerUpgradeHandler upgradeHandler = new HttpServerUpgradeHandler(sourceCodec, protocol -> {
            if(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME.toString().contentEquals(protocol)){
                return new Http2ServerUpgradeCodec(http2FrameCodec, multiplexHandler);
            }
            return null;
        }, config.getHttpMaxContentLength());

        pipeline.addLast(PipelineConstants.NAME_HTTP_CLEAR_TEXT_UPGRADE,
                new CleartextHttp2ServerUpgradeHandler(sourceCodec, upgradeHandler, newHttp1Initializer()));
    }

    private ChannelInitializer<Channel> newHttp1Initializer() {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel channel) {
                channel.pipeline().addLast(PipelineConstants.NAME_HTTP_AGGREGATOR,
                        new HttpObjectAggregator(config.getHttpMaxContentLength()));
                channel.pipeline().addLast(PipelineConstants.NAME_HTTP_DISPATCHER,
                        new NettyHttpDispatcher(httpHandler, HTTP1_PROTOCOL));
            }
        };
    }

    private ChannelInitializer<Channel> newHttp2StreamInitializer() {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel channel) {
                channel.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(true));
                channel.pipeline().addLast(new HttpObjectAggregator(config.getHttpMaxContentLength()));
                channel.pipeline().addLast(new NettyHttpDispatcher(httpHandler, HTTP2_PROTOCOL));
            }
        };
    }
}
