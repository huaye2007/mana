package com.github.huaye2007.mana.dev.protocol;

/**
 * 外网帧的进程内表示。
 *
 * <p>body 的形态按方向不同：入站（decoder 产出）是按 command 反序列化好的业务对象；
 * 出站（交给 encoder）必须是已序列化的 {@code byte[]}（空 body 用
 * {@link GamePacketConstant#EMPTY_BODY}），encoder 对其它类型直接抛错。</p>
 */
public class GamePacket {
    private int command;
    private int seq;
    private int code;
    private byte flags;
    private Object body;

    /** 组一帧出站包：flags=0，body 传已序列化字节（空 body 用 {@link GamePacketConstant#EMPTY_BODY}）。 */
    public static GamePacket of(int command, int seq, int code, byte[] body) {
        GamePacket packet = new GamePacket();
        packet.setCommand(command);
        packet.setSeq(seq);
        packet.setCode(code);
        packet.setBody(body);
        return packet;
    }

    public int getCommand() {
        return command;
    }

    public void setCommand(int command) {
        this.command = command;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public byte getFlags() {
        return flags;
    }

    public void setFlags(byte flags) {
        this.flags = flags;
    }

    public Object getBody() {
        return body;
    }

    public void setBody(Object body) {
        this.body = body;
    }
}
