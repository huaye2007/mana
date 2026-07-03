package com.github.huaye2007.mana.network.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.List;

public class ByteArrayToByteBufEncoder extends MessageToMessageEncoder<byte[]> {

    @Override
    protected void encode(ChannelHandlerContext ctx, byte[] msg, List<Object> out) {
        ByteBuf byteBuf = ctx.alloc().buffer(msg.length);
        byteBuf.writeBytes(msg);
        out.add(byteBuf);
    }
}
