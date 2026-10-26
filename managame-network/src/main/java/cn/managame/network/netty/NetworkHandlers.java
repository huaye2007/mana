package cn.managame.network.netty;

import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Ready-made handler chains for common game-server needs. Any Netty handler can be added directly as well. */
public final class NetworkHandlers {
    private NetworkHandlers() {}

    /**
     * Stream framing for TCP: each message is preceded by a 4-byte big-endian length that excludes itself.
     * Handlers after it read and write one whole message ByteBuf at a time. Frames above
     * {@code maxFrameLength} raise TooLongFrameException. Names: {@code frame-decoder}, {@code frame-encoder}.
     * WebSocket already delivers whole messages and does not need it.
     */
    public static HandlerChain lengthFieldFraming(int maxFrameLength) {
        if (maxFrameLength <= 0) throw new IllegalArgumentException("maxFrameLength must be positive");
        LengthFieldPrepender prepender = new LengthFieldPrepender(4);
        return HandlerChain.builder()
                .addLast("frame-decoder", () -> new LengthFieldBasedFrameDecoder(maxFrameLength, 0, 4, 0, 4))
                .addLast("frame-encoder", prepender)
                .build();
    }

    /**
     * Closes a connection that receives nothing for {@code timeout}, such as a client that stopped sending
     * heartbeats. The handler sees onException with ReadTimeoutException, then onInActive. Name: {@code read-timeout}.
     */
    public static HandlerChain readTimeout(Duration timeout) {
        long millis = timeout.toMillis();
        if (millis <= 0) throw new IllegalArgumentException("timeout must be at least one millisecond");
        return HandlerChain.of("read-timeout", () -> new ReadTimeoutHandler(millis, TimeUnit.MILLISECONDS));
    }
}
