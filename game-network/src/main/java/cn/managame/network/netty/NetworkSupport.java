package cn.managame.network.netty;

import cn.managame.network.error.NetworkException;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.Channel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException;
import javax.net.ssl.SSLException;
import java.nio.channels.ClosedChannelException;
import java.net.SocketException;
import io.netty.util.concurrent.Future;
import java.net.URI;
import java.util.concurrent.TimeUnit;

final class NetworkSupport {
    static final System.Logger LOG = System.getLogger("cn.managame.network");
    static final int MAX_MESSAGE_SIZE = 1024 * 1024;
    static void log(String message, Throwable cause) {
        LOG.log(System.Logger.Level.ERROR, message, cause);
    }
    static void logTransportFailure(String message, Channel channel, Throwable cause) {
        Throwable detail = cause;
        // Unwrap codec wrappers, but never classify every decoder/programming error as bad input.
        for (int i = 0; i < 8 && detail instanceof DecoderException && detail.getCause() != null; i++)
            detail = detail.getCause();
        String reason;
        if (detail instanceof SSLException || detail instanceof WebSocketHandshakeException
                || detail instanceof CorruptedFrameException || detail instanceof TooLongFrameException)
            reason = "Protocol establishment rejected";
        else if (detail instanceof ClosedChannelException || detail instanceof SocketException
                || detail instanceof PrematureChannelClosureException)
            reason = "Peer disconnected";
        else {
            log(message + "; peer=" + channel.remoteAddress(), cause);
            return;
        }
        // Avoid formatting on the default path; do not log peer-supplied exception messages/headers.
        if (LOG.isLoggable(System.Logger.Level.DEBUG))
            LOG.log(System.Logger.Level.DEBUG, message + "; reason=" + reason + "; peer=" + channel.remoteAddress()
                    + "; type=" + detail.getClass().getSimpleName());
    }
    static int messageSize(int size) {
        if (size <= 0) throw new IllegalArgumentException("maxMessageSize must be positive");
        return size;
    }
    static String path(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.contains("?") || path.contains("#") || path.contains("*"))
            throw new IllegalArgumentException("WebSocket path must be absolute, without query, fragment or wildcard");
        URI uri = URI.create(path);
        if (uri.getRawAuthority() != null || !path.equals(uri.getRawPath()))
            throw new IllegalArgumentException("Invalid WebSocket path: " + path);
        return path;
    }
    static void checkNotEventLoop(EventLoopGroup group) {
        if (group != null) for (var executor : group) {
            if (executor.inEventLoop())
                throw new IllegalStateException("Blocking lifecycle operation on its EventLoop; use connectAsync for connections");
        }
    }
    static void shutdown(EventLoopGroup group) {
        await(group.shutdownGracefully(0, 5, TimeUnit.SECONDS), "EventLoop shutdown failed");
    }
    // Finish cleanup even when interrupted; the outer lifecycle operation reports interruption.
    static void await(Future<?> future, String message) {
        future.awaitUninterruptibly();
        if (!future.isSuccess()) throw new NetworkException(message, future.cause());
    }
    static void checkInterrupted(String operation) {
        if (Thread.currentThread().isInterrupted())
            throw new NetworkException(operation + " interrupted", new InterruptedException(operation));
    }
}
