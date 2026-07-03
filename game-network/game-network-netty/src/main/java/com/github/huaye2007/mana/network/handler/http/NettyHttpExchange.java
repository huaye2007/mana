package com.github.huaye2007.mana.network.handler.http;

import com.github.huaye2007.mana.network.http.IHttpExchange;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class NettyHttpExchange implements IHttpExchange {

    private final ChannelHandlerContext ctx;
    private final String protocol;
    private final String method;
    private final String uri;
    private final HttpVersion responseVersion;
    private final boolean keepAlive;
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final byte[] body;
    private final AtomicBoolean responseWritten = new AtomicBoolean(false);

    public NettyHttpExchange(ChannelHandlerContext ctx, FullHttpRequest request, String protocol) {
        this.ctx = ctx;
        this.protocol = protocol;
        this.method = request.method().name();
        this.uri = request.uri();
        this.responseVersion = request.protocolVersion();
        this.keepAlive = HttpUtil.isKeepAlive(request);
        request.headers().forEach(header -> headers.put(header.getKey(), header.getValue()));
        this.body = new byte[request.content().readableBytes()];
        request.content().getBytes(request.content().readerIndex(), body);
    }

    @Override
    public String getProtocol() {
        return protocol;
    }

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public String getUri() {
        return uri;
    }

    @Override
    public String getHeader(String name) {
        return headers.get(name);
    }

    @Override
    public byte[] getBody() {
        return body;
    }

    @Override
    public boolean isResponseWritten() {
        return responseWritten.get();
    }

    @Override
    public void writeResponse(int status, byte[] body) {
        writeResponse(status, Map.of(), body);
    }

    @Override
    public void writeResponse(int status, Map<String, String> headers, byte[] body) {
        if(!responseWritten.compareAndSet(false, true)){
            return;
        }

        byte[] responseBody = body == null ? new byte[0] : body;
        FullHttpResponse response = new DefaultFullHttpResponse(responseVersion,
                HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(responseBody));
        if(headers != null){
            headers.forEach((name, value) -> {
                if(name != null && value != null){
                    response.headers().set(name, value);
                }
            });
        }
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseBody.length);

        ChannelFuture future = ctx.writeAndFlush(response);
        if(!keepAlive && responseVersion.isKeepAliveDefault()){
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }
}
