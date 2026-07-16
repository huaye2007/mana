package cn.managame.network.http.netty;

import cn.managame.network.http.IHttpHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

final class NettyHttpChannelInitializer extends ChannelInitializer<SocketChannel> {

    private static final String HTTP1_FALLBACK = "http1Fallback";

    private final IHttpHandler handler;
    private final NettyHttpProtocol protocol;
    private final SslContext sslContext;
    private final int maxContentLength;
    private final int maxInitialLineLength;
    private final int maxHeaderSize;
    private final int maxPendingRequests;
    private final long maxConcurrentStreams;
    private final Duration requestTimeout;
    private final Consumer<ChannelPipeline> beforeProtocol;
    private final Consumer<ChannelPipeline> applicationPipeline;

    NettyHttpChannelInitializer(IHttpHandler handler, NettyHttpProtocol protocol, SslContext sslContext,
                                int maxContentLength, int maxInitialLineLength, int maxHeaderSize,
                                int maxPendingRequests, long maxConcurrentStreams,
                                Duration requestTimeout,
                                Consumer<ChannelPipeline> beforeProtocol,
                                Consumer<ChannelPipeline> applicationPipeline) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.sslContext = sslContext;
        this.maxContentLength = maxContentLength;
        this.maxInitialLineLength = maxInitialLineLength;
        this.maxHeaderSize = maxHeaderSize;
        this.maxPendingRequests = maxPendingRequests;
        this.maxConcurrentStreams = maxConcurrentStreams;
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.beforeProtocol = Objects.requireNonNull(beforeProtocol, "beforeProtocol");
        this.applicationPipeline = Objects.requireNonNull(applicationPipeline, "applicationPipeline");
    }

    @Override
    protected void initChannel(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        beforeProtocol.accept(pipeline);
        if (sslContext == null) {
            configureCleartext(pipeline);
            return;
        }

        pipeline.addLast("ssl", sslContext.newHandler(channel.alloc()));
        pipeline.addLast("alpn", new ApplicationProtocolNegotiationHandler(
                protocol == NettyHttpProtocol.HTTP2 ? "" : ApplicationProtocolNames.HTTP_1_1) {
            @Override
            protected void configurePipeline(ChannelHandlerContext context, String negotiatedProtocol) {
                if (protocol != NettyHttpProtocol.HTTP1
                        && ApplicationProtocolNames.HTTP_2.equals(negotiatedProtocol)) {
                    configureHttp2(context.pipeline());
                } else if (protocol != NettyHttpProtocol.HTTP2
                        && ApplicationProtocolNames.HTTP_1_1.equals(negotiatedProtocol)) {
                    configureHttp1(context.pipeline(), true);
                } else {
                    throw new IllegalStateException("unsupported negotiated HTTP protocol: " + negotiatedProtocol);
                }
            }
        });
    }

    private void configureCleartext(ChannelPipeline pipeline) {
        switch (protocol) {
            case HTTP1 -> configureHttp1(pipeline, true);
            case HTTP2 -> configureHttp2(pipeline);
            case AUTO -> configureH2cAndHttp1(pipeline);
        }
    }

    private void configureHttp1(ChannelPipeline pipeline, boolean addCodec) {
        if (addCodec) {
            pipeline.addLast("http1Codec", newHttp1Codec());
        }
        pipeline.addLast("http1Aggregator", new HttpObjectAggregator(maxContentLength));
        applicationPipeline.accept(pipeline);
        pipeline.addLast("httpDispatcher", new NettyHttpDispatcher(
                handler, false, requestTimeout, maxPendingRequests));
    }

    private void configureHttp2(ChannelPipeline pipeline) {
        pipeline.addLast("http2FrameCodec", newHttp2FrameCodec());
        pipeline.addLast("http2Multiplex", new Http2MultiplexHandler(newStreamInitializer()));
    }

    private void configureH2cAndHttp1(ChannelPipeline pipeline) {
        HttpServerCodec sourceCodec = newHttp1Codec();
        Http2FrameCodec upgradeFrameCodec = newHttp2FrameCodec();
        Http2MultiplexHandler upgradeMultiplex = new Http2MultiplexHandler(
                newStreamInitializer(), newStreamInitializer());
        HttpServerUpgradeHandler upgradeHandler = new HttpServerUpgradeHandler(
                sourceCodec,
                requestedProtocol -> AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME,
                        requestedProtocol)
                        ? new Http2ServerUpgradeCodec(upgradeFrameCodec, upgradeMultiplex)
                        : null,
                maxContentLength);
        pipeline.addLast("cleartextHttp2", new CleartextHttp2ServerUpgradeHandler(
                sourceCodec, upgradeHandler, http2PriorKnowledgeInitializer()));
        pipeline.addLast(HTTP1_FALLBACK, new io.netty.channel.SimpleChannelInboundHandler<io.netty.handler.codec.http.HttpMessage>() {
            @Override
            protected void channelRead0(ChannelHandlerContext context,
                                        io.netty.handler.codec.http.HttpMessage message) {
                configureHttp1(context.pipeline(), false);
                context.fireChannelRead(ReferenceCountUtil.retain(message));
                context.pipeline().remove(this);
            }
        });
    }

    private ChannelHandler http2PriorKnowledgeInitializer() {
        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.context(HTTP1_FALLBACK) != null) {
                    pipeline.remove(HTTP1_FALLBACK);
                }
                configureHttp2(pipeline);
            }
        };
    }

    private Http2FrameCodec newHttp2FrameCodec() {
        Http2Settings settings = new Http2Settings()
                .maxConcurrentStreams(maxConcurrentStreams)
                .maxHeaderListSize((long) maxHeaderSize);
        return Http2FrameCodecBuilder.forServer().initialSettings(settings).build();
    }

    private HttpServerCodec newHttp1Codec() {
        HttpDecoderConfig decoderConfig = new HttpDecoderConfig()
                .setMaxInitialLineLength(maxInitialLineLength)
                .setMaxHeaderSize(maxHeaderSize);
        return new HttpServerCodec(decoderConfig);
    }

    private ChannelHandler newStreamInitializer() {
        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) {
                ChannelPipeline pipeline = channel.pipeline();
                pipeline.addLast("http2StreamCodec", new Http2StreamFrameToHttpObjectCodec(true));
                pipeline.addLast("http2Aggregator", new HttpObjectAggregator(maxContentLength));
                applicationPipeline.accept(pipeline);
                pipeline.addLast("httpDispatcher", new NettyHttpDispatcher(
                        handler, true, requestTimeout, maxPendingRequests));
            }
        };
    }
}
