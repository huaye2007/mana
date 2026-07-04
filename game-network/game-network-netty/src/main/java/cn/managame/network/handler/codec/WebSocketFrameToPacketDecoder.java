package cn.managame.network.handler.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.util.List;

public class WebSocketFrameToPacketDecoder extends MessageToMessageDecoder<WebSocketFrame> {

    @Override
    protected void decode(ChannelHandlerContext ctx, WebSocketFrame frame, List<Object> out) {
        if(frame instanceof BinaryWebSocketFrame){
            out.add(frame.content().retain());
            return;
        }
        if(frame instanceof TextWebSocketFrame textFrame){
            out.add(textFrame.text());
        }
    }
}
