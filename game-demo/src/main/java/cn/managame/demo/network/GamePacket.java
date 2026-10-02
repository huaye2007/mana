package cn.managame.demo.network;

import java.util.Objects;

public class GamePacket {
    static final int HEADER_BYTES = 4 * Integer.BYTES;
    static final int DEFAULT_MAX_FRAME_LENGTH = 1024 * 1024;
    private int command;
    private int seq;
    private int code;
    private byte[] body = new byte[0];

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

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = Objects.requireNonNull(body, "body");
    }
}
