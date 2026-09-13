package cn.managame.demo.protocol;

import java.util.Objects;

/** Shared client/server envelope. Body bytes are owned and independent of network buffers. */
public record GamePacket(int command, int requestId, int code, int flags, byte[] body) {
    public static final int REQUEST = 0;
    public static final int RESPONSE = 1;
    public static final int NOTIFY = 2;
    public static final int HEADER_BYTES = 16;
    public static final int MAX_FRAME_BYTES = 4096;
    public static final int MAX_BODY_BYTES = MAX_FRAME_BYTES - Integer.BYTES - HEADER_BYTES;

    public GamePacket {
        if (command <= 0 || code < 0) throw new IllegalArgumentException("Invalid command, requestId or code");
        if (flags != REQUEST && flags != RESPONSE && flags != NOTIFY) throw new IllegalArgumentException("Unsupported flags: " + flags);
        if (flags == NOTIFY) {
            if (requestId != 0 || code != 0) throw new IllegalArgumentException("Notification requestId and code must be zero");
        } else if (requestId <= 0) throw new IllegalArgumentException("Request and response requestId must be positive");
        if (flags == REQUEST && code != 0) throw new IllegalArgumentException("Request code must be zero");
        Objects.requireNonNull(body, "body");
        if (body.length > MAX_BODY_BYTES) throw new IllegalArgumentException("Body exceeds frame limit");
        body = body.clone();
    }
    @Override public byte[] body() { return body.clone(); }
    public boolean isResponse() { return flags == RESPONSE; }
    public boolean isNotify() { return flags == NOTIFY; }
}
