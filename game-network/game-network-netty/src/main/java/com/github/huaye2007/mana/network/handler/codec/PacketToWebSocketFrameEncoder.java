package com.github.huaye2007.mana.network.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.util.List;

public class PacketToWebSocketFrameEncoder extends MessageToMessageEncoder<Object> {

    @Override
    public boolean acceptOutboundMessage(Object msg) throws Exception {
        return msg instanceof WebSocketFrame
                || msg instanceof ByteBuf
                || msg instanceof byte[]
                || msg instanceof String;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) {
        if(msg instanceof WebSocketFrame frame){
            out.add(frame.retain());
            return;
        }
        if(msg instanceof ByteBuf byteBuf){
            out.add(new BinaryWebSocketFrame(byteBuf.retain()));
            return;
        }
        if(msg instanceof byte[] bytes){
            out.add(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes)));
            return;
        }
        if(msg instanceof String text){
            out.add(new TextWebSocketFrame(text));
            return;
        }
        out.add(msg);
    }
}
