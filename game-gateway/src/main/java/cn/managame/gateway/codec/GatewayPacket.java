package cn.managame.gateway.codec;

import java.util.Arrays;

/** A transport envelope whose body remains opaque to the gateway. */
public final class GatewayPacket {
    private int command;
    private int seq;
    private int code;
    private byte flags;
    private byte[] body = GatewayPacketConstant.EMPTY_BODY;

    public GatewayPacket() { }

    public static GatewayPacket of(int command, int seq, int code, byte[] body) {
        GatewayPacket packet = new GatewayPacket();
        packet.command = command;
        packet.seq = seq;
        packet.code = code;
        packet.setBody(body);
        return packet;
    }

    public int getCommand() { return command; }
    public void setCommand(int command) { this.command = command; }
    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }
    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public byte getFlags() { return flags; }
    public void setFlags(byte flags) { this.flags = flags; }
    public byte[] getBody() { return body; }
    public void setBody(byte[] body) {
        this.body = body == null || body.length == 0 ? GatewayPacketConstant.EMPTY_BODY : Arrays.copyOf(body, body.length);
    }
}
