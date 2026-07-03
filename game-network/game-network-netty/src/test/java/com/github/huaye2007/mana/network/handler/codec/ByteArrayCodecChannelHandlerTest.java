package com.github.huaye2007.mana.network.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteArrayCodecChannelHandlerTest {

    @Test
    void decodesByteBufToByteArrayAndReleasesInput() {
        EmbeddedChannel channel = new EmbeddedChannel(new ByteBufToByteArrayDecoder());
        ByteBuf input = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});

        assertTrue(channel.writeInbound(input));

        byte[] decoded = channel.readInbound();
        assertArrayEquals(new byte[]{1, 2, 3}, decoded);
        assertEquals(0, input.refCnt());
        assertFalse(channel.finish());
    }

    @Test
    void encodesByteArrayToByteBuf() {
        EmbeddedChannel channel = new EmbeddedChannel(new ByteArrayToByteBufEncoder());

        assertTrue(channel.writeOutbound(new byte[]{4, 5, 6}));

        ByteBuf encoded = channel.readOutbound();
        byte[] bytes = new byte[encoded.readableBytes()];
        encoded.readBytes(bytes);
        encoded.release();

        assertArrayEquals(new byte[]{4, 5, 6}, bytes);
        assertFalse(channel.finish());
    }
}
