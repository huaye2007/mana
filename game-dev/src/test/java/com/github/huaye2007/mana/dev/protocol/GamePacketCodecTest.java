package com.github.huaye2007.mana.dev.protocol;

import com.github.huaye2007.mana.dev.message.LoginReq;
import com.github.huaye2007.mana.dev.bootstrap.FuryMessageRegistrar;
import com.github.huaye2007.mana.dev.server.PlayerSession;
import com.github.huaye2007.mana.runtime.annotation.GameController;
import com.github.huaye2007.mana.runtime.annotation.GameMethod;
import com.github.huaye2007.mana.runtime.command.CommandRegistry;
import com.github.huaye2007.mana.runtime.executor.ExecutorGroups;
import com.github.huaye2007.mana.serialization.SerializerManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 encode → decode 往返：body 字节被真正读出并按固定序列化反序列化成类型化对象，
 * 且半包（帧未到齐）时不会误解出包。用独立 command 9001，避免与其它测试占用 1000 冲突。
 */
class GamePacketCodecTest {

    private static final int CMD = 9001;

    @GameController(group = ExecutorGroups.PLAYER)
    public static class CodecController {
        @GameMethod(value = CMD)
        public void handle(PlayerSession session, LoginReq req) {
        }
    }

    @BeforeAll
    static void registerCommand() {
        // CommandRegistry 是进程级单例，跨测试共享；幂等注册避免被其它测试的扫描重复登记
        if (CommandRegistry.getInstance().getCommandMeta(CMD) == null) {
            CommandRegistry.getInstance().register(new CodecController());
        }
        // 默认 Fury 要求类注册，外网包体收发前先登记消息 DTO（幂等）
        FuryMessageRegistrar.registerMessageTypes();
    }

    private static byte[] encode(int command, int seq, LoginReq req) {
        byte[] body = SerializerManager.getInstance()
                .getISerializer(GamePacketConstant.BODY_SERIAL_TYPE).serialize(req);
        GamePacket packet = new GamePacket();
        packet.setCommand(command);
        packet.setSeq(seq);
        packet.setCode(0);
        packet.setFlags((byte) 0);
        packet.setBody(body);

        EmbeddedChannel encoder = new EmbeddedChannel(new GamePacketEncoder());
        assertTrue(encoder.writeOutbound(packet));
        ByteBuf encoded = encoder.readOutbound();
        byte[] bytes = new byte[encoded.readableBytes()];
        encoded.readBytes(bytes);
        encoded.release();
        encoder.finish();
        return bytes;
    }

    @Test
    void encodeThenDecodeRoundTripsTypedBody() {
        LoginReq req = new LoginReq();
        req.setUserId(42L);
        req.setToken("tok");

        EmbeddedChannel decoder = new EmbeddedChannel(new GamePacketDecoder());
        assertTrue(decoder.writeInbound(Unpooled.wrappedBuffer(encode(CMD, 7, req))));
        GamePacket decoded = decoder.readInbound();
        decoder.finish();

        assertEquals(CMD, decoded.getCommand());
        assertEquals(7, decoded.getSeq());
        assertTrue(decoded.getBody() instanceof LoginReq);
        LoginReq body = (LoginReq) decoded.getBody();
        assertEquals(42L, body.getUserId());
        assertEquals("tok", body.getToken());
    }

    @Test
    void splitFrameDecodesOnlyWhenComplete() {
        LoginReq req = new LoginReq();
        req.setUserId(1L);
        req.setToken("x");
        byte[] frame = encode(CMD, 1, req);

        int split = GamePacketConstant.HEAD_LENGTH; // 头到了、body 还没到
        EmbeddedChannel decoder = new EmbeddedChannel(new GamePacketDecoder());

        assertTrue(!decoder.writeInbound(Unpooled.wrappedBuffer(frame, 0, split)));
        assertNull(decoder.readInbound(), "帧不完整时不应解出包");

        decoder.writeInbound(Unpooled.wrappedBuffer(frame, split, frame.length - split));
        GamePacket decoded = decoder.readInbound();
        decoder.finish();

        assertNotNull(decoded);
        assertEquals(1L, ((LoginReq) decoded.getBody()).getUserId());
    }
}
