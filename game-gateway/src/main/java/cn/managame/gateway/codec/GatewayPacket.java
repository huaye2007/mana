package cn.managame.gateway.codec;

/**
 * 外网帧的网关内表示，字段与 game-dev 的 {@code GamePacket} 完全一致：
 * {@code command | seq | code | flags | body}。
 *
 * <p>与游戏服不同，网关不反序列化 body——入站、出站的 body 都是原始
 * {@code byte[]}（空 body 用 {@link GatewayPacketConstant#EMPTY_BODY}），
 * 网关只看帧头做过滤和路由，body 对后端透传。</p>
 */
public class GatewayPacket {

    private int command;
    private int seq;
    private int code;
    private byte flags;
    private byte[] body;

    /** 组一帧：flags=0，空 body 用 {@link GatewayPacketConstant#EMPTY_BODY}。 */
    public static GatewayPacket of(int command, int seq, int code, byte[] body) {
        GatewayPacket packet = new GatewayPacket();
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

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body;
    }
}
